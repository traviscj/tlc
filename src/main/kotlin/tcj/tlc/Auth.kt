package tcj.tlc

import java.sql.Connection
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import misk.MiskCaller
import misk.exceptions.BadRequestException
import misk.exceptions.UnauthorizedException
import misk.scope.ActionScoped
import misk.security.authz.MiskCallerAuthenticator
import misk.web.HttpCall

const val USER = "tlc_user"
const val ADMIN = "tlc_admin"
const val USER_HEADER = "X-TLC-User"
const val SERVICE_HEADER = "X-TLC-Service"

private val LDAP_RE = Regex("[a-z0-9][a-z0-9._-]{0,63}")

/**
 * Who is acting. A human (`ldap`), a service, or a service acting for a human (both). TLC records
 * both where the schema has room for both (taggings); elsewhere [name] is what gets written.
 */
data class Actor(val ldap: String?, val service: String?, val admin: Boolean) {
  val name: String get() = ldap ?: "svc:$service"
  fun requireHuman(what: String): String = ldap ?: throw BadRequestException("$what needs a human: send $USER_HEADER")
}

/**
 * Trusted-header auth: identity is whatever X-TLC-User / X-TLC-Service say. That is only sound
 * behind something that authenticates the caller and overwrites these headers (SSO proxy, service
 * mesh); TLC itself does not verify them. See docs/operations.md.
 */
@Singleton
class Actors @Inject constructor(
  private val httpCall: @JvmSuppressWildcards ActionScoped<HttpCall>,
  private val config: TlcConfig,
) {
  fun currentOrNull(): Actor? {
    val h = httpCall.get().requestHeaders
    val ldap = h[USER_HEADER]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val service = h[SERVICE_HEADER]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (ldap == null && service == null) return null
    if (ldap != null && !LDAP_RE.matches(ldap)) return null
    if (service != null && !LDAP_RE.matches(service)) return null
    return Actor(ldap, service, ldap != null && ldap in config.tlc.admins)
  }

  fun current(): Actor = currentOrNull() ?: throw UnauthorizedException("send $USER_HEADER and/or $SERVICE_HEADER")
}

class TlcAuthenticator @Inject constructor(private val actors: Actors) : MiskCallerAuthenticator {
  override fun getAuthenticatedCaller(): MiskCaller? {
    val a = actors.currentOrNull() ?: return null
    return MiskCaller(user = a.name, capabilities = if (a.admin) setOf(USER, ADMIN) else setOf(USER))
  }
}

data class TlcerDto(val token: String, val ldap: String, val display_name: String?, val created_at: String, val last_seen_at: String, val version: Int)

/** `tlcers`: everyone who has ever acted through TLC. Rows appear on first write, not by signup. */
@Singleton
class Tlcers @Inject constructor(private val db: Db, private val clock: Clock) {
  /** Call inside the acting transaction; no-op for pure service callers. */
  fun touch(c: Connection, actor: Actor) {
    val ldap = actor.ldap ?: return
    val now = clock.millis()
    c.exec(
      """
      INSERT INTO tlcers (created_at_ms, updated_at_ms, token, ldap, last_seen_at_ms) VALUES (?,?,?,?,?)
      ON DUPLICATE KEY UPDATE last_seen_at_ms = VALUES(last_seen_at_ms)
      """.trimIndent(),
      listOf(now, now, newToken("U"), ldap, now),
    )
  }

  fun list(): List<TlcerDto> = db.tx { c ->
    c.select("SELECT * FROM tlcers ORDER BY last_seen_at_ms DESC, id DESC LIMIT 1000") { rs ->
      TlcerDto(rs.getString("token"), rs.getString("ldap"), rs.getString("display_name"), iso(rs.getLong("created_at_ms")), iso(rs.getLong("last_seen_at_ms")), rs.getInt("version"))
    }
  }

  fun setDisplayName(actor: Actor, name: String?) = db.tx { c ->
    touch(c, actor)
    c.exec("UPDATE tlcers SET display_name = ?, updated_at_ms = ?, version = version + 1 WHERE ldap = ?", listOf(name, clock.millis(), actor.requireHuman("a profile")))
  }
}
