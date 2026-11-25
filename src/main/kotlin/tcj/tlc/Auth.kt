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
import tcj.oidc.OidcVerifier

const val USER = "tlc_user"
const val ADMIN = "tlc_admin"
const val USER_HEADER = "X-TLC-User"
const val SERVICE_HEADER = "X-TLC-Service"

const val API_TOKEN_PREFIX = "tlc_"

private val LDAP_RE = Regex("[a-z0-9][a-z0-9._-]{0,63}")

fun sha256Hex(s: String): String =
  java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Who is acting. A human (`ldap`), a service, or a service acting for a human (both). TLC records
 * both where the schema has room for both (taggings); elsewhere [name] is what gets written.
 */
data class Actor(val ldap: String?, val service: String?, val admin: Boolean) {
  val name: String get() = ldap ?: "svc:$service"
  fun requireHuman(what: String): String = ldap ?: throw BadRequestException("$what needs a human: send $USER_HEADER")
}

/**
 * Two modes, chosen by config:
 *
 * - `tlc.oidc` set: identity comes from a bearer JWT verified against the issuer (see
 *   [OidcVerifier]). X-TLC-User alone means nothing. A human is their username claim; a token whose
 *   `sub` is listed in `tlc.oidc_services` is that service, and may name the human it acts for in
 *   X-TLC-User (it never gets admin that way).
 * - `tlc.api_tokens`: a bearer token starting `tlc_` whose SHA-256 is listed there is that service,
 *   with the same rules as an `oidc_services` caller. Checked before the issuer, so these callers
 *   keep working while the issuer is down. An unknown `tlc_` token is nobody, in either mode.
 * - `tlc.proxy_auth`: a request carrying the proxy's secret is the person the proxy names (admin by
 *   `tlc.admins` or by a group in `oidc_admin_groups`). The secret is what makes the header mean
 *   something; the proxy must also strip these headers from what clients send.
 * - Setting any of `oidc` / `proxy_auth` turns the trusted headers off.
 * - `tlc.oidc` unset: trusted headers. Identity is whatever X-TLC-User / X-TLC-Service say, which
 *   is only sound on localhost or behind a proxy that overwrites them. See docs/operations.md.
 */
@Singleton
class Actors @Inject constructor(
  private val httpCall: @JvmSuppressWildcards ActionScoped<HttpCall>,
  private val config: TlcConfig,
  clock: Clock,
) {
  private val verifier: OidcVerifier? = config.tlc.oidc?.let { OidcVerifier(it, clock) }

  fun currentOrNull(): Actor? {
    val h = httpCall.get().requestHeaders
    val headerLdap = h[USER_HEADER]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (headerLdap != null && !LDAP_RE.matches(headerLdap)) return null

    val bearer = h["Authorization"]?.trim()?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
    if (bearer != null && bearer.startsWith(API_TOKEN_PREFIX)) {
      val service = config.tlc.api_tokens[sha256Hex(bearer)] ?: return null
      return Actor(headerLdap, service, admin = false)
    }

    config.tlc.proxy_auth?.let { p ->
      val secret = h[p.secret_header]
      if (secret != null) {
        if (!java.security.MessageDigest.isEqual(sha256Hex(secret).toByteArray(), p.secret_sha256.lowercase().toByteArray())) return null
        val ldap = h[p.user_header]?.trim()?.lowercase()?.takeIf(LDAP_RE::matches) ?: return null
        val groups = p.groups_header?.let { h[it] }.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return Actor(ldap, null, ldap in config.tlc.admins || groups.any { it in config.tlc.oidc_admin_groups })
      }
    }

    if (verifier != null) {
      val id = verifier.authenticate { h[it] } ?: return null
      config.tlc.oidc_services[id.subject]?.let { service -> return Actor(headerLdap, service, admin = false) }
      val ldap = id.username?.lowercase()?.takeIf(LDAP_RE::matches) ?: return null
      return Actor(ldap, null, ldap in config.tlc.admins || id.groups.any { it in config.tlc.oidc_admin_groups })
    }

    if (config.tlc.proxy_auth != null) return null
    val service = h[SERVICE_HEADER]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (headerLdap == null && service == null) return null
    if (service != null && !LDAP_RE.matches(service)) return null
    return Actor(headerLdap, service, headerLdap != null && headerLdap in config.tlc.admins)
  }

  fun current(): Actor = currentOrNull()
    ?: throw UnauthorizedException(if (verifier != null || config.tlc.proxy_auth != null) "send Authorization: Bearer <token>" else "send $USER_HEADER and/or $SERVICE_HEADER")
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
