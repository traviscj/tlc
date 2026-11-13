package tcj.tlc

import javax.inject.Qualifier
import misk.config.Config
import misk.jdbc.DataSourceConfig
import misk.web.WebConfig

data class TlcConfig(
  val web: WebConfig,
  val data_source: DataSourceConfig,
  val tlc: TlcSettings = TlcSettings(),
) : Config

data class TlcSettings(
  val public_url: String = "http://localhost:8790",
  /** Period of the one background loop: feed publishing + notification delivery. */
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
  /** Slack incoming-webhook URL. null = SLACK notifications are logged, not sent. */
  val slack_webhook_url: String? = null,
  /** An ownership claim nobody has re-affirmed for this long reads LAPSED. */
  val ownership_lapse_days: Int = 90,
  /** Default time a break-glass (mode=RATIFY) action stands before it must be reverted. */
  val ratify_ttl_seconds: Long = 4 * 3600,
) {
  override fun toString() = "TlcSettings(public_url=$public_url, admins=$admins, slack_webhook=${if (slack_webhook_url != null) "████" else "none"})"
}

@Qualifier
@kotlin.annotation.Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class TlcDb
