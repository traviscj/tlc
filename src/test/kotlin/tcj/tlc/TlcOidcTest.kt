package tcj.tlc

import com.google.inject.Module
import javax.inject.Inject
import misk.MiskTestingServiceModule
import misk.config.ConfigModule
import misk.config.MiskConfig
import misk.inject.KAbstractModule
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import misk.web.WebServerTestingModule
import misk.web.jetty.JettyService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tcj.oidc.FakeIdp
import tcj.oidc.OidcAuthConfig
import wisp.deployment.TESTING

class TlcOidcTestModule : KAbstractModule() {
  override fun configure() {
    val base = MiskConfig.load<TlcConfig>("tlc", TESTING, listOf<String>())
    val config = base.copy(
      tlc = base.tlc.copy(
        oidc = OidcAuthConfig(issuer = FakeIdp.issuer, audiences = listOf("tlc")),
        oidc_admin_groups = listOf("tlc-admins"),
        oidc_services = mapOf("client-sentinel" to "sentinel"),
      ),
    )
    install(MiskTestingServiceModule())
    install(WebServerTestingModule())
    install(ConfigModule.create("tlc", config))
    install(TlcModule(config))
  }
}

/** The service with `tlc.oidc` set: identity is a verified bearer token, and the trusted headers stop counting. */
@MiskTest(startService = true)
class TlcOidcTest {
  @MiskTestModule val module: Module = TlcOidcTestModule()

  @Inject lateinit var jetty: JettyService
  @Inject lateinit var json: Json
  @Inject lateinit var clock: misk.time.FakeClock

  private val http = OkHttpClient()

  @BeforeEach
  fun setUp() {
    clock.setNow(java.time.Instant.parse("2026-11-10T12:00:00Z"))
    FakeIdp.userinfo.clear()
  }

  private fun token(sub: String, username: String? = null, groups: List<String> = listOf(), aud: String = "tlc") =
    FakeIdp.token(
      // Like Pocket ID: an ID token carries the profile claims, an access token only says who (sub) and for whom (aud).
      mapOf("iss" to FakeIdp.issuer, "aud" to aud, "sub" to sub, "exp" to clock.instant().epochSecond + 3600) +
        (username?.let { mapOf("preferred_username" to it, "groups" to groups) } ?: mapOf()),
    )

  @Suppress("UNCHECKED_CAST")
  private fun me(vararg headers: Pair<String, String>): Pair<Int, Map<String, Any?>> {
    val req = Request.Builder().url(jetty.httpServerUrl.resolve("/api/me")!!)
    headers.forEach { (k, v) -> req.header(k, v) }
    http.newCall(req.build()).execute().use { r ->
      val text = r.body.string()
      return r.code to (if (text.startsWith("{")) json.parse(text) as Map<String, Any?> else mapOf())
    }
  }

  @Test
  fun `the trusted headers no longer identify anyone`() {
    assertThat(me().first).isEqualTo(401)
    assertThat(me("X-TLC-User" to "root").first).isEqualTo(401)
    assertThat(me("X-TLC-Service" to "sentinel").first).isEqualTo(401)
    assertThat(me("Authorization" to "Bearer ${token("u-alice", "alice", aud = "another-client")}").first).isEqualTo(401)
    // A valid token cannot be used to become someone else by adding the header.
    val (code, body) = me("Authorization" to "Bearer ${token("u-alice", "alice")}", "X-TLC-User" to "root")
    assertThat(code).isEqualTo(200)
    assertThat(body["ldap"]).isEqualTo("alice")
    assertThat(body["admin"]).isEqualTo(false)
  }

  @Test
  fun `a human is their username claim, and admin by group or by the admins list`() {
    val (code, alice) = me("Authorization" to "Bearer ${token("u-alice", "Alice")}")
    assertThat(code).isEqualTo(200)
    assertThat(alice["ldap"]).isEqualTo("alice")
    assertThat(alice["service"]).isNull()
    assertThat(alice["admin"]).isEqualTo(false)

    assertThat(me("Authorization" to "Bearer ${token("u-bob", "bob", groups = listOf("tlc-admins"))}").second["admin"]).isEqualTo(true)
    assertThat(me("Authorization" to "Bearer ${token("u-root", "root")}").second["admin"]).describedAs("tlc.admins in tlc-testing.yaml").isEqualTo(true)
    // A username that is not a usable ldap is not a caller.
    assertThat(me("Authorization" to "Bearer ${token("u-odd", "Not A Valid Ldap!")}").first).isEqualTo(401)
  }

  @Test
  fun `an access token without a username works through userinfo and the proxy header`() {
    FakeIdp.userinfo["u-carol"] = mapOf("preferred_username" to "carol", "groups" to listOf("tlc-admins"))
    val (code, carol) = me("X-Auth-Request-Access-Token" to token("u-carol"))
    assertThat(code).isEqualTo(200)
    assertThat(carol["ldap"]).isEqualTo("carol")
    assertThat(carol["admin"]).isEqualTo(true)
    // No username anywhere: authenticated by the issuer, but not someone TLC can name.
    assertThat(me("Authorization" to "Bearer ${token("u-nobody")}").first).isEqualTo(401)
  }

  @Test
  fun `a registered service token is the service, and may act for a human without inheriting admin`() {
    val svc = "Bearer ${token("client-sentinel")}"
    val (code, alone) = me("Authorization" to svc)
    assertThat(code).isEqualTo(200)
    assertThat(alone["service"]).isEqualTo("sentinel")
    assertThat(alone["ldap"]).isNull()

    val acting = me("Authorization" to svc, "X-TLC-User" to "root").second
    assertThat(acting["service"]).isEqualTo("sentinel")
    assertThat(acting["ldap"]).isEqualTo("root")
    assertThat(acting["admin"]).isEqualTo(false)
  }
}
