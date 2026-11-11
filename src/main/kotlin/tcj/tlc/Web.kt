package tcj.tlc

import javax.inject.Inject
import misk.exceptions.NotFoundException
import misk.resources.ResourceLoader
import misk.security.authz.Authenticated
import misk.security.authz.Unauthenticated
import misk.web.Get
import misk.web.QueryParam
import misk.web.ResponseContentType
import misk.web.actions.WebAction
import misk.web.mediatype.MediaTypes

/**
 * Static pages. They carry no data and need no auth; each talks to the JSON API as whoever the
 * X-TLC-User header says (injected by the fronting proxy in a real deployment, typed into the
 * page's "as" box in dev).
 */
class WebActions @Inject constructor(private val resources: ResourceLoader) : WebAction {
  private fun page(name: String) = resources.utf8("classpath:/web/$name") ?: throw NotFoundException("$name not bundled")

  /** Dense operator console over everything. */
  @Get("/") @Unauthenticated @ResponseContentType(MediaTypes.TEXT_HTML)
  fun console() = page("console.html")

  /** Embeddable widget: `<iframe src="/iframe?target=sentinel/proposed_changes/1234&panels=approvals,tags,comments,logs">`. */
  @Get("/iframe") @Unauthenticated @ResponseContentType(MediaTypes.TEXT_HTML)
  fun iframe() = page("iframe.html")

  @Get("/static/tlc.js") @Unauthenticated @ResponseContentType("application/javascript;charset=utf-8")
  fun js() = page("tlc.js")

  @Get("/static/tlc.css") @Unauthenticated @ResponseContentType("text/css;charset=utf-8")
  fun css() = page("tlc.css")

  /** A pretend source-service page that embeds the widget. */
  @Get("/demo") @Unauthenticated @ResponseContentType(MediaTypes.TEXT_HTML)
  fun demo() = page("demo.html")
}

data class EnumValue(val name: String, val description: String)
data class InfoDto(val settings: Map<String, Any?>, val counts: Map<String, Long>, val requirements: List<EnumValue>, val states: List<EnumValue>, val log_levels: List<String>, val feeds: List<String>, val token_prefixes: Map<String, String>)

/** Everything a target has, in one round trip (what the iframe renders). */
data class Summary(val target: String, val known: Boolean, val entity: EntityDto?, val tags: List<TagState>, val threads: List<ThreadDto>, val approvables: List<ApprovableDto>, val logs: List<LogEntryDto>, val ownership: List<CellDto> = listOf())

class InfoActions @Inject constructor(
  private val config: TlcConfig,
  private val db: Db,
  private val entities: EntityStore,
  private val tags: TagStore,
  private val comments: CommentStore,
  private val approvals: ApprovalStore,
  private val logs: LogStore,
  private val ownership: OwnershipStore,
) : WebAction {
  @Get("/api/info") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun info(): InfoDto {
    val s = config.tlc
    val tables = listOf("external_entity_types", "external_entities", "tlcers", "tags", "taggings", "log_entries", "threads", "comments", "likes", "approval_rules", "approvables", "approvals", "notifications", "ownerships", "entity_edges")
    val counts = db.tx { c -> tables.associateWith { t -> c.select("SELECT COUNT(*) FROM $t") { it.getLong(1) }.first() } }
    return InfoDto(
      settings = linkedMapOf("public_url" to s.public_url, "admins" to s.admins, "auto_register_entity_types" to s.auto_register_entity_types, "feed_shards" to s.feed_shards, "max_comment_depth" to s.max_comment_depth, "background_interval_ms" to s.background_interval_ms, "slack_webhook" to (s.slack_webhook_url != null), "ownership_lapse_days" to s.ownership_lapse_days, "ratify_ttl_seconds" to s.ratify_ttl_seconds),
      counts = counts,
      requirements = Requirement.entries.map { EnumValue(it.name, it.doc) },
      states = listOf(
        EnumValue("PENDING", "initial; waiting on approvals"),
        EnumValue("APPROVED", "requirement met; may still drop back to PENDING if an approval is withdrawn"),
        EnumValue("EXECUTED", "terminal; the change was claimed for execution exactly once"),
        EnumValue("DECLINED", "terminal; refused or withdrawn"),
        EnumValue("ACTED", "mode=RATIFY (break-glass): already done; waiting to be ratified before expires_at"),
        EnumValue("RATIFIED", "terminal; the break-glass action was blessed and stands"),
        EnumValue("REVERTED", "terminal; not ratified in time (or withdrawn) — the source service must undo it"),
      ),
      log_levels = LogLevel.entries.map { it.name },
      feeds = FEEDS.keys.toList(),
      token_prefixes = linkedMapOf("ET-" to "entity type", "E-" to "entity", "U-" to "tlcer", "TG-" to "tag", "TGG-" to "tagging", "T-" to "thread", "C-" to "comment", "L-" to "like", "AR-" to "approval rule", "AP-" to "approvable", "A-" to "approval", "N-" to "notification", "OW-" to "ownership claim", "EG-" to "edge"),
    )
  }

  @Get("/api/summary") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun summary(@QueryParam target: String, @QueryParam log_limit: Int?): Summary {
    val entity = try { entities.get(target) } catch (e: NotFoundException) { null }
    val ref = entity?.ref ?: target
    return Summary(
      ref, entity != null, entity,
      tags.tagsOf(target, null, null).tags,
      comments.listThreads(target, null, false, 50),
      if (entity != null) approvals.list(target, null, null, null, null, 50) else listOf(),
      if (entity != null) logs.query(target, null, null, null, null, null, null, null, null, null, limitOf(log_limit, 50)).entries else listOf(),
      if (entity != null) ownership.of(target, null, null).cells else listOf(),
    )
  }
}
