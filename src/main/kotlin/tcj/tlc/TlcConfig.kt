package tcj.tlc

import javax.inject.Qualifier
import misk.config.Config
import misk.jdbc.DataSourceConfig
import misk.web.WebConfig
import tcj.oidc.OidcAuthConfig

data class TlcConfig(
  val web: WebConfig,
  val data_source: DataSourceConfig,
  val tlc: TlcSettings = TlcSettings(),
) : Config

data class TlcSettings(
  val public_url: String = "http://localhost:8790",
  /** Period of the background loops: feed publishing (misk-feedz) and ratification expiry + notification delivery. */
  val background_interval_ms: Long = 500,
  /** Data shards assigned at write time; feed consumers select by modulus. */
  val feed_shards: Int = 16,
  /** ldaps holding the admin capability (approval rules, directory stand-in, type destroy). */
  val admins: List<String> = listOf(),
  /**
   * false: `service/entity/id` refs against an unregistered (service, entity) are a 404, so a typo
   * can't mint a table TLC "knows about". true: first reference registers the type.
   */
  val auto_register_entity_types: Boolean = false,
  /**
   * The requirement types this deployment offers when a rule is written (names from [Requirement]).
   * null = all of them. Rules that already use a type taken off the menu keep working.
   */
  val requirements: List<String>? = null,
  /** A reply to a comment already at this depth is filed as its sibling instead of nesting further. */
  val max_comment_depth: Int = 16,
  /**
   * Items a screen should not show to whoever walks past: an item whose title, source or key matches one of these
   * regexes (case-insensitive) is `private`, as is any item tagged `private`; the tag `public` overrides a match.
   * See "Redaction" in docs/items.md.
   */
  val item_private_patterns: List<String> = listOf(),
  /** Slack incoming-webhook URL. null = SLACK notifications are logged, not sent. */
  val slack_webhook_url: String? = null,
  /** An ownership claim nobody has re-affirmed for this long reads LAPSED. */
  val ownership_lapse_days: Int = 90,
  /** Default time a break-glass (mode=RATIFY) action stands before it must be reverted. */
  val ratify_ttl_seconds: Long = 4 * 3600,
  /** Set to authenticate callers by OIDC bearer JWT (Pocket ID). null = trusted-header auth: dev and tests only. */
  val oidc: OidcAuthConfig? = null,
  /** With `oidc`: members of these issuer groups are admins, in addition to `admins`. */
  val oidc_admin_groups: List<String> = listOf(),
  /** With `oidc`: token `sub` -> service name, for machine callers (client-credentials tokens). */
  val oidc_services: Map<String, String> = mapOf(),
  /**
   * Static bearer tokens for machine callers that should not depend on the issuer (hooks, cron):
   * SHA-256 hex of the token -> service name. The token itself must start with `tlc_` and is never
   * stored. `scripts/tlc mint-token <service>` prints a token and the line to put here.
   */
  val api_tokens: Map<String, String> = mapOf(),
  /**
   * People arriving through an authenticating reverse proxy (oauth2-proxy in forward-auth mode): the
   * proxy names the signed-in user in a header and proves it is the proxy with a shared secret. A
   * proxy session outlives the access token it started with, so the token itself is no use here.
   */
  val proxy_auth: ProxyAuthConfig? = null,
) {
  override fun toString() = "TlcSettings(public_url=$public_url, admins=$admins, oidc=${oidc?.issuer ?: "off"}, proxy_auth=${if (proxy_auth != null) "on" else "off"}, slack_webhook=${if (slack_webhook_url != null) "████" else "none"})"
}

data class ProxyAuthConfig(
  /** SHA-256 hex of the value the proxy sends in [secret_header]. Without a match the other headers mean nothing. */
  val secret_sha256: String,
  val secret_header: String = "X-TLC-Proxy-Secret",
  val user_header: String = "X-Remote-User",
  /** Comma-separated issuer groups; members of `oidc_admin_groups` are admins. null = not sent. */
  val groups_header: String? = "X-Remote-Groups",
) {
  override fun toString() = "ProxyAuthConfig(user_header=$user_header, groups_header=$groups_header, secret=████)"
}

@Qualifier
@kotlin.annotation.Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class TlcDb
