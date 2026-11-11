package tcj.tlc

import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import misk.exceptions.BadRequestException
import misk.security.authz.Authenticated
import misk.web.Get
import misk.web.Post
import misk.web.QueryParam
import misk.web.RequestBody
import misk.web.RequestContentType
import misk.web.ResponseContentType
import misk.web.actions.WebAction

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, FATAL;
  companion object {
    fun parse(s: String?): LogLevel = when (val u = (s ?: "INFO").trim().uppercase()) {
      "WARNING" -> WARN
      "ERR" -> ERROR
      else -> entries.firstOrNull { it.name == u } ?: throw BadRequestException("bad log level '$s' (have: ${entries.joinToString()})")
    }
  }
}

data class LogEntryDto(
  val id: Long,
  val timestamp: String,
  val level: String,
  val message: String,
  val source: String,
  /** `service/entity` when the entry is scoped to a type (always set when `target` is). */
  val entity_type: String?,
  /** `service/entity/id` when the entry is about one record. */
  val target: String?,
  /** Server receive time; `timestamp` is the caller's clock. */
  val created_at: String,
)

data class LogEntryReq(val timestamp: String?, val level: String?, val message: String?, val source: String?, val target: String?, val entity_type: String?)
data class LogAppendReq(
  val entries: List<LogEntryReq>?,
  // single-entry shorthand
  val timestamp: String?, val level: String?, val message: String?, val source: String?, val target: String?, val entity_type: String?,
)
data class LogAppendResp(val appended: Int, val first_id: Long?, val last_id: Long?)
data class LogPage(val entries: List<LogEntryDto>, /** Pass as `after_id` to tail. Unchanged when the page is empty. */ val cursor: Long)

/**
 * Append-only log entries, optionally scoped to an entity type or a single external record.
 * `id` is the cursor: it is assigned in commit order per connection but two concurrent appenders can
 * commit out of id order, so a tailer that must not miss entries should re-read a small overlap.
 * (Feeds solve that properly; logs are deliberately the cheap path.)
 */
@Singleton
class LogStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val entities: EntityStore,
) {
  private val select = """
    SELECT l.*, t.source_service, t.source_entity, e.source_entity_id
    FROM log_entries l
    LEFT JOIN external_entity_types t ON t.id = l.external_entity_type_id
    LEFT JOIN external_entities e ON e.id = l.external_entity_id
  """.trimIndent()

  private fun dto(rs: ResultSet): LogEntryDto {
    val type = rs.getString("source_service")?.let { "$it/${rs.getString("source_entity")}" }
    return LogEntryDto(
      rs.getLong("id"), iso(rs.getLong("timestamp_ms")), LogLevel.entries[rs.getInt("level")].name, rs.getString("message"), rs.getString("source"),
      type, rs.getString("source_entity_id")?.let { "$type/$it" }, iso(rs.getLong("created_at_ms")),
    )
  }

  private fun insert(c: Connection, ts: Long, level: LogLevel, message: String, source: String, typeId: Long?, entityId: Long?): Long =
    c.insert(
      "INSERT INTO log_entries (created_at_ms, timestamp_ms, level, message, source, external_entity_type_id, external_entity_id) VALUES (?,?,?,?,?,?,?)",
      listOf(clock.millis(), ts, level.ordinal, message, source, typeId, entityId),
    )

  /** TLC's own audit trail (tag/approval/comment events), written in the transaction that made the change. */
  fun internal(c: Connection, target: Target, level: LogLevel, source: String, message: String) {
    if (target.entityId == null) return
    insert(c, clock.millis(), level, message, source, target.entityTypeId, target.entityId)
  }

  fun append(actor: Actor, reqs: List<LogEntryReq>): LogAppendResp = db.tx { c ->
    if (reqs.isEmpty()) throw BadRequestException("no entries")
    if (reqs.size > 1000) throw BadRequestException("at most 1000 entries per call")
    val ids = reqs.mapIndexed { i, r ->
      val message = r.message?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("entries[$i].message is required")
      if (message.length > 16384) throw BadRequestException("entries[$i].message is over 16384 chars")
      val (typeId, entityId) = when {
        r.target != null -> entities.resolveEntity(c, r.target, create = true).let { it.entityTypeId to it.entityId }
        r.entity_type != null -> entities.typeId(c, r.entity_type) to null
        else -> null to null
      }
      // source defaults to the calling service: that is who is speaking, unless it says otherwise.
      val source = r.source?.takeIf { it.isNotBlank() } ?: actor.service ?: actor.name
      insert(c, r.timestamp?.let(::parseTime) ?: clock.millis(), LogLevel.parse(r.level), message, source.take(96), typeId, entityId)
    }
    LogAppendResp(ids.size, ids.first(), ids.last())
  }

  fun query(
    target: String?, entityType: String?, minLevel: String?, source: String?, q: String?, since: Long?, until: Long?,
    afterId: Long?, beforeId: Long?, order: String?, limit: Int,
  ): LogPage = db.tx { c ->
    val where = ArrayList<String>()
    val args = ArrayList<Any?>()
    if (target != null) {
      val t = entities.resolveOrNull(c, target) ?: return@tx LogPage(listOf(), afterId ?: 0)
      where += "l.external_entity_id = ?"; args += (t.entityId ?: throw BadRequestException("logs attach to external entities, not '$target'"))
    }
    if (entityType != null) { where += "l.external_entity_type_id = ?"; args += entities.typeId(c, entityType) }
    if (minLevel != null) { where += "l.level >= ?"; args += LogLevel.parse(minLevel).ordinal }
    if (!source.isNullOrBlank()) {
      // `source=tlc.` style prefix match: trailing dot or star means "and everything under it".
      if (source.endsWith("*") || source.endsWith(".")) { where += "l.source LIKE ?"; args += source.trimEnd('*') + "%" } else { where += "l.source = ?"; args += source }
    }
    if (!q.isNullOrBlank()) { where += "l.message LIKE ?"; args += "%$q%" }
    if (since != null) { where += "l.timestamp_ms >= ?"; args += since }
    if (until != null) { where += "l.timestamp_ms < ?"; args += until }
    if (afterId != null) { where += "l.id > ?"; args += afterId }
    if (beforeId != null) { where += "l.id < ?"; args += beforeId }
    // Tailing (after_id) reads forward; everything else defaults to newest-first.
    val asc = when (order?.lowercase()) { "asc" -> true; "desc" -> false; null -> afterId != null; else -> throw BadRequestException("order is asc|desc") }
    val rows = c.select("$select ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY l.id ${if (asc) "ASC" else "DESC"} LIMIT $limit", args, ::dto)
    LogPage(rows, rows.maxOfOrNull { it.id } ?: afterId ?: 0)
  }
}

class LogActions @Inject constructor(private val store: LogStore, private val actors: Actors) : WebAction {
  /** Body is one entry, or `{"entries": [...]}` for a batch (all-or-nothing). */
  @Post("/api/logs") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun append(@RequestBody req: LogAppendReq): LogAppendResp =
    store.append(actors.current(), req.entries ?: listOf(LogEntryReq(req.timestamp, req.level, req.message, req.source, req.target, req.entity_type)))

  @Get("/api/logs") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun query(
    @QueryParam target: String?, @QueryParam entity_type: String?, @QueryParam level: String?, @QueryParam source: String?, @QueryParam q: String?,
    @QueryParam since: String?, @QueryParam until: String?, @QueryParam after_id: Long?, @QueryParam before_id: Long?, @QueryParam order: String?, @QueryParam limit: Int?,
  ) = store.query(target, entity_type, level, source, q, since?.let(::parseTime), until?.let(::parseTime), after_id, before_id, order, limitOf(limit))
}
