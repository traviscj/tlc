package tcj.tlc

import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import misk.exceptions.BadRequestException
import misk.exceptions.ConflictException
import misk.exceptions.NotFoundException
import misk.security.authz.Authenticated
import misk.web.Delete
import misk.web.Get
import misk.web.Patch
import misk.web.PathParam
import misk.web.Post
import misk.web.QueryParam
import misk.web.RequestBody
import misk.web.RequestContentType
import misk.web.ResponseContentType
import misk.web.actions.WebAction

data class TagDto(
  val token: String,
  val tag_name: String,
  val description: String?,
  val created_by: String,
  val effective_at: String,
  /** Set once retired: existing taggings stay readable, new ADDs at or after this instant are refused. */
  val ineffective_at: String?,
  /** Targets carrying the tag right now. */
  val tagged_now: Long,
  val created_at: String,
  val updated_at: String,
  val version: Int,
)

data class TaggingDto(
  val token: String,
  val target: String,
  val tag: String,
  val operation: String,
  val ldap: String?,
  val service: String?,
  val note: String?,
  val effective_at: String,
  /** When TLC learned of it. Differs from effective_at for back/forward-dated operations. */
  val created_at: String,
  val feed_sync_id: Long?,
  val shard: Int,
)

data class TagResult(
  /** false = the target was already in the requested state as of effective_at; nothing was written. */
  val changed: Boolean,
  val target: String,
  val tag: String,
  /** State as of the operation's effective_at. */
  val tagged: Boolean,
  /** State as of now. Differs from `tagged` when a later-effective operation already exists. */
  val tagged_now: Boolean,
  val tagging: TaggingDto?,
)

data class IsTagged(val target: String, val tag: String, val at: String, val known_at: String?, val tagged: Boolean, val since: String?, val by: String?, val tagging: String?)

/** Half-open [from, to). `to` null = still tagged. */
data class TagInterval(val from: String, val to: String?, val added_by: String?, val removed_by: String?)

data class WasTagged(
  val target: String,
  val tag: String,
  /** Query window; null bound = unbounded. */
  val from: String?,
  val to: String?,
  /** Tagged at any instant in the window. */
  val was_tagged: Boolean,
  /** Tagged for the entire (bounded) window. */
  val throughout: Boolean,
  val intervals: List<TagInterval>,
)

data class TagState(val tag: String, val since: String, val by: String?)
data class TagsOf(val target: String, val at: String, val tags: List<TagState>)
data class TargetState(val target: String, val since: String, val by: String?)
data class TargetsOf(val tag: String, val at: String, val targets: List<TargetState>)

private val TAG_RE = Regex("[a-z0-9][a-z0-9_.:-]{0,95}")

private data class Op(val id: Long, val token: String, val targetKey: String, val tagId: Long, val add: Boolean, val by: String?, val eff: Long)

/**
 * Tags are effective-dated and taggings are an append-only operation log: a target's state for a
 * tag at instant t is the last ADD|REMOVE with effective_at <= t, ties broken by insertion order.
 * Nothing is ever updated or deleted, so "was tagged" is the same query as "is tagged" with a
 * different t, and `known_at` (filter on created_at) gives the answer TLC would have given then.
 */
@Singleton
class TagStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: TlcConfig,
  private val entities: EntityStore,
  private val tlcers: Tlcers,
  private val logs: LogStore,
) {
  private val tagSelect = "SELECT t.* FROM tags t"

  private fun norm(name: String): String = name.trim().lowercase().also {
    if (!TAG_RE.matches(it)) throw BadRequestException("tag_name must match ${TAG_RE.pattern}")
  }

  private fun selectTags(c: Connection, where: String, args: List<Any?> = listOf()): List<TagDto> {
    val now = clock.millis()
    val rows = c.select("$tagSelect $where", args) { rs ->
      rs.getLong("id") to TagDto(
        rs.getString("token"), rs.getString("tag_name"), rs.getString("description"), rs.getString("created_by"),
        iso(rs.getLong("effective_at_ms")), rs.isoOrNull("ineffective_at_ms"), 0, iso(rs.getLong("created_at_ms")),
        iso(rs.getLong("updated_at_ms")), rs.getInt("version"),
      )
    }
    return rows.map { (id, dto) -> dto.copy(tagged_now = current(fold(ops(c, tagId = id, at = now))).size.toLong()) }
  }

  private fun tagByName(c: Connection, name: String): TagDto =
    selectTags(c, "WHERE t.tag_name = ?", listOf(norm(name))).firstOrNull() ?: throw NotFoundException("no tag '$name'")

  fun create(actor: Actor, name: String, description: String?, effectiveAt: Long?): TagDto = db.tx { c ->
    tlcers.touch(c, actor)
    createIn(c, actor, norm(name), description, effectiveAt)
    tagByName(c, name)
  }

  private fun createIn(c: Connection, actor: Actor, name: String, description: String?, effectiveAt: Long?) {
    val now = clock.millis()
    val existing = c.select("SELECT id, ineffective_at_ms FROM tags WHERE tag_name = ? FOR UPDATE", listOf(name)) { it.getLong(1) to it.longOrNull("ineffective_at_ms") }.firstOrNull()
    if (existing == null) {
      c.insert("INSERT INTO tags (created_at_ms, updated_at_ms, token, tag_name, description, created_by, effective_at_ms) VALUES (?,?,?,?,?,?,?)", listOf(now, now, newToken("TG"), name, description, actor.name, effectiveAt ?: now))
    } else if (existing.second != null) {
      // Un-retire. effective_at is left alone: the tag was effective before, and history hangs off that.
      c.exec("UPDATE tags SET ineffective_at_ms = NULL, description = COALESCE(?, description), updated_at_ms = ?, version = version + 1 WHERE id = ?", listOf(description, now, existing.first))
    } else {
      throw ConflictException("tag '$name' already exists")
    }
  }

  fun list(includeIneffective: Boolean): List<TagDto> = db.tx { c ->
    selectTags(c, "${if (includeIneffective) "" else "WHERE t.ineffective_at_ms IS NULL OR t.ineffective_at_ms > ${clock.millis()}"} ORDER BY t.tag_name")
  }

  fun get(name: String): TagDto = db.tx { c -> tagByName(c, name) }

  fun update(name: String, description: String?): TagDto = db.tx { c ->
    val t = tagByName(c, name)
    c.exec("UPDATE tags SET description = ?, updated_at_ms = ?, version = version + 1 WHERE token = ?", listOf(description, clock.millis(), t.token))
    tagByName(c, name)
  }

  /** Destroy = retire. The tag stops accepting ADDs from [at]; what is tagged stays tagged until untagged. */
  fun retire(name: String, at: Long?): TagDto = db.tx { c ->
    val t = tagByName(c, name)
    c.exec("UPDATE tags SET ineffective_at_ms = ?, updated_at_ms = ?, version = version + 1 WHERE token = ?", listOf(at ?: clock.millis(), clock.millis(), t.token))
    tagByName(c, name)
  }

  // ---------------------------------------------------------------------------- operations

  fun apply(actor: Actor, targetRef: String, tagName: String, add: Boolean, effectiveAt: Long?, note: String?, createTag: Boolean): TagResult = db.tx { c ->
    tlcers.touch(c, actor)
    val name = norm(tagName)
    val now = clock.millis()
    val eff = effectiveAt ?: now
    // The tag row lock serializes every operation on this tag, so check-then-insert below is safe.
    var tag = c.select("SELECT id, effective_at_ms, ineffective_at_ms FROM tags WHERE tag_name = ? FOR UPDATE", listOf(name)) { Triple(it.getLong(1), it.getLong(2), it.longOrNull("ineffective_at_ms")) }.firstOrNull()
    if (tag == null && add && createTag) {
      createIn(c, actor, name, null, minOf(eff, now))
      tag = c.select("SELECT id, effective_at_ms, ineffective_at_ms FROM tags WHERE tag_name = ? FOR UPDATE", listOf(name)) { Triple(it.getLong(1), it.getLong(2), it.longOrNull("ineffective_at_ms")) }.first()
    }
    if (tag == null) throw NotFoundException("no tag '$name' (POST /api/tags, or pass create_tag=true)")
    val (tagId, tagEff, tagIneff) = tag
    if (add && (eff < tagEff || (tagIneff != null && eff >= tagIneff))) {
      throw ConflictException("tag '$name' is not effective at ${iso(eff)} (effective ${iso(tagEff)}..${tagIneff?.let(::iso) ?: ""})")
    }
    // Untagging something TLC has never seen is a no-op, not a reason to mint a record for it.
    val target = if (add) entities.resolve(c, targetRef, create = true)
    else entities.resolveOrNull(c, targetRef) ?: return@tx TagResult(false, targetRef, name, false, false, null)
    val before = last(c, target.key, tagId, eff, null)?.add ?: false
    var dto: TaggingDto? = null
    if (before != add) {
      val id = c.insert(
        """
        INSERT INTO taggings (created_at_ms, updated_at_ms, token, taggable_id, external_entity_id, target_key, tag_id, operation, ldap, service, note, effective_at_ms, shard)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent(),
        listOf(now, now, newToken("TGG"), target.subjectToken, target.entityId, target.key, tagId, if (add) "ADD" else "REMOVE", actor.ldap, actor.service, note, eff, shardOf(target.key, config.tlc.feed_shards)),
      )
      dto = c.select("$taggingSelect WHERE g.id = ?", listOf(id)) { tagging(c, it) }.first()
      logs.internal(c, target, LogLevel.INFO, "tlc.tags", "${if (add) "tagged" else "untagged"} '$name' by ${actor.name}${if (eff != now) " effective ${iso(eff)}" else ""}")
    }
    TagResult(dto != null, target.ref, name, add, last(c, target.key, tagId, clock.millis(), null)?.add ?: false, dto)
  }

  private val taggingSelect = "SELECT g.*, t.tag_name FROM taggings g JOIN tags t ON t.id = g.tag_id"

  private fun tagging(c: Connection, rs: ResultSet) = TaggingDto(
    rs.getString("token"), entities.refOfKey(c, rs.getString("target_key")), rs.getString("tag_name"), rs.getString("operation"),
    rs.getString("ldap"), rs.getString("service"), rs.getString("note"), iso(rs.getLong("effective_at_ms")), iso(rs.getLong("created_at_ms")),
    rs.longOrNull("feed_sync_id"), rs.getInt("shard"),
  )

  private fun op(rs: ResultSet) = Op(rs.getLong("id"), rs.getString("token"), rs.getString("target_key"), rs.getLong("tag_id"), rs.getString("operation") == "ADD", rs.getString("ldap") ?: rs.getString("service")?.let { "svc:$it" }, rs.getLong("effective_at_ms"))

  /** The operation deciding (target, tag) at [at], as known at [knownAt] (null = now). */
  private fun last(c: Connection, targetKey: String, tagId: Long, at: Long, knownAt: Long?): Op? =
    c.select(
      "SELECT * FROM taggings WHERE target_key = ? AND tag_id = ? AND effective_at_ms <= ? ${if (knownAt != null) "AND created_at_ms <= $knownAt" else ""} ORDER BY effective_at_ms DESC, id DESC LIMIT 1",
      listOf(targetKey, tagId, at), ::op,
    ).firstOrNull()

  /** Operations in effect order, optionally bounded by effective time and knowledge time. */
  private fun ops(c: Connection, targetKey: String? = null, tagId: Long? = null, at: Long? = null, knownAt: Long? = null): List<Op> {
    val where = ArrayList<String>()
    val args = ArrayList<Any?>()
    if (targetKey != null) { where += "target_key = ?"; args += targetKey }
    if (tagId != null) { where += "tag_id = ?"; args += tagId }
    if (at != null) { where += "effective_at_ms <= ?"; args += at }
    if (knownAt != null) { where += "created_at_ms <= ?"; args += knownAt }
    return c.select("SELECT * FROM taggings WHERE ${where.joinToString(" AND ")} ORDER BY effective_at_ms, id", args, ::op)
  }

  /** (target, tag) -> deciding op, from ops in effect order. */
  private fun fold(ops: List<Op>): Map<Pair<String, Long>, Op> {
    val state = LinkedHashMap<Pair<String, Long>, Op>()
    for (o in ops) {
      val k = o.targetKey to o.tagId
      // A redundant ADD-after-ADD must not move `since` forward.
      if (state[k]?.add == true && o.add) continue
      state[k] = o
    }
    return state
  }

  private fun current(state: Map<Pair<String, Long>, Op>) = state.filterValues { it.add }

  private fun tagId(c: Connection, name: String): Long =
    c.select("SELECT id FROM tags WHERE tag_name = ?", listOf(norm(name))) { it.getLong(1) }.firstOrNull() ?: throw NotFoundException("no tag '$name'")

  fun isTagged(targetRef: String, tagName: String, at: Long?, knownAt: Long?): IsTagged = db.tx { c ->
    val t = at ?: clock.millis()
    val tagId = tagId(c, tagName)
    val target = entities.resolveOrNull(c, targetRef)
    // `since` must survive redundant ADDs, so fold rather than read only the last op.
    val op = target?.let { fold(ops(c, it.key, tagId, t, knownAt))[it.key to tagId] }
    val on = op?.add == true
    IsTagged(target?.ref ?: targetRef, norm(tagName), iso(t), knownAt?.let(::iso), on, op?.takeIf { on }?.let { iso(it.eff) }, op?.takeIf { on }?.by, op?.takeIf { on }?.token)
  }

  fun wasTagged(targetRef: String, tagName: String, from: Long?, to: Long?, knownAt: Long?): WasTagged = db.tx { c ->
    if (from != null && to != null && to < from) throw BadRequestException("to is before from")
    val tagId = tagId(c, tagName)
    val target = entities.resolveOrNull(c, targetRef)
    val all = target?.let { intervals(ops(c, it.key, tagId, null, knownAt)) }.orEmpty()
    // Closed query window [from, to] against half-open intervals [a, b).
    val hit = all.filter { (a, b, _, _) -> (to == null || a <= to) && (b == null || from == null || b > from) }
    val throughout = from != null && to != null && all.any { (a, b, _, _) -> a <= from && (b == null || b > to) }
    WasTagged(target?.ref ?: targetRef, norm(tagName), from?.let(::iso), to?.let(::iso), hit.isNotEmpty(), throughout, hit.map { TagInterval(iso(it.from), it.to?.let(::iso), it.addedBy, it.removedBy) })
  }

  private data class Span(val from: Long, val to: Long?, val addedBy: String?, val removedBy: String?)

  private fun intervals(ops: List<Op>): List<Span> {
    val out = ArrayList<Span>()
    var open: Op? = null
    for (o in ops) {
      if (o.add && open == null) open = o
      else if (!o.add && open != null) {
        // Zero-length spans (ADD and REMOVE effective at the same instant) never held.
        if (o.eff > open.eff) out += Span(open.eff, o.eff, open.by, o.by)
        open = null
      }
    }
    open?.let { out += Span(it.eff, null, it.by, null) }
    return out
  }

  fun tagsOf(targetRef: String, at: Long?, knownAt: Long?): TagsOf = db.tx { c ->
    val t = at ?: clock.millis()
    val target = entities.resolveOrNull(c, targetRef) ?: return@tx TagsOf(targetRef, iso(t), listOf())
    val names = c.select("SELECT id, tag_name FROM tags") { it.getLong(1) to it.getString(2) }.toMap()
    val tags = current(fold(ops(c, targetKey = target.key, at = t, knownAt = knownAt))).values.map { TagState(names.getValue(it.tagId), iso(it.eff), it.by) }.sortedBy { it.tag }
    TagsOf(target.ref, iso(t), tags)
  }

  fun targetsOf(tagName: String, at: Long?, knownAt: Long?, limit: Int): TargetsOf = db.tx { c ->
    val t = at ?: clock.millis()
    // Folds the tag's whole operation log: fine to ~1e5 ops/tag; past that, materialize current state.
    val live = current(fold(ops(c, tagId = tagId(c, tagName), at = t, knownAt = knownAt))).values.sortedByDescending { it.eff }.take(limit)
    TargetsOf(norm(tagName), iso(t), live.map { TargetState(entities.refOfKey(c, it.targetKey), iso(it.eff), it.by) })
  }

  /** The raw operation log, newest first. */
  fun history(targetRef: String?, tagName: String?, limit: Int): List<TaggingDto> = db.tx { c ->
    val where = ArrayList<String>()
    val args = ArrayList<Any?>()
    if (targetRef != null) { where += "g.target_key = ?"; args += (entities.resolveOrNull(c, targetRef)?.key ?: return@tx listOf()) }
    if (tagName != null) { where += "g.tag_id = ?"; args += tagId(c, tagName) }
    c.select("$taggingSelect ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY g.id DESC LIMIT $limit", args) { tagging(c, it) }
  }

  fun feed(after: Long, limit: Int, shardSql: String): List<TaggingDto> = db.tx { c ->
    c.select("$taggingSelect WHERE g.feed_sync_id > ? $shardSql ORDER BY g.feed_sync_id LIMIT $limit", listOf(after)) { tagging(c, it) }
  }
}

// ------------------------------------------------------------------------------------ actions

data class TagReq(val tag_name: String?, val description: String?, val effective_at: String?)
data class TagList(val tags: List<TagDto>)
data class TagOpReq(val target: String?, val tag: String?, val effective_at: String?, val note: String?, val create_tag: Boolean?)
data class TaggingList(val taggings: List<TaggingDto>)

class TagActions @Inject constructor(private val store: TagStore, private val actors: Actors) : WebAction {
  @Post("/api/tags") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun create(@RequestBody req: TagReq) = store.create(actors.current(), req.tag_name.required("tag_name"), req.description, req.effective_at?.let(::parseTime))

  @Get("/api/tags") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun list(@QueryParam include_ineffective: Boolean?) = TagList(store.list(include_ineffective == true))

  @Get("/api/tags/{name}") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun get(@PathParam name: String) = store.get(name)

  @Patch("/api/tags/{name}") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun update(@PathParam name: String, @RequestBody req: TagReq) = store.update(name, req.description)

  @Delete("/api/tags/{name}") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun retire(@PathParam name: String, @QueryParam at: String?) = store.retire(name, at?.let(::parseTime))
}

class TaggingActions @Inject constructor(private val store: TagStore, private val actors: Actors) : WebAction {
  @Post("/api/taggings/tag") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun tag(@RequestBody req: TagOpReq) = op(req, true)

  @Post("/api/taggings/untag") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun untag(@RequestBody req: TagOpReq) = op(req, false)

  private fun op(req: TagOpReq, add: Boolean) =
    store.apply(actors.current(), req.target.required("target"), req.tag.required("tag"), add, req.effective_at?.let(::parseTime), req.note, req.create_tag == true)

  /** `at` omitted = now ("is tagged"); `at` in the past = "was tagged at". */
  @Get("/api/taggings/is-tagged") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun isTagged(@QueryParam target: String, @QueryParam tag: String, @QueryParam at: String?, @QueryParam known_at: String?) =
    store.isTagged(target, tag, at?.let(::parseTime), known_at?.let(::parseTime))

  /** "Was it ever tagged / tagged during [from, to]", with the intervals. */
  @Get("/api/taggings/was-tagged") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun wasTagged(@QueryParam target: String, @QueryParam tag: String, @QueryParam from: String?, @QueryParam to: String?, @QueryParam known_at: String?) =
    store.wasTagged(target, tag, from?.let(::parseTime), to?.let(::parseTime), known_at?.let(::parseTime))

  @Get("/api/taggings/tags") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun tagsOf(@QueryParam target: String, @QueryParam at: String?, @QueryParam known_at: String?) = store.tagsOf(target, at?.let(::parseTime), known_at?.let(::parseTime))

  @Get("/api/taggings/targets") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun targetsOf(@QueryParam tag: String, @QueryParam at: String?, @QueryParam known_at: String?, @QueryParam limit: Int?) =
    store.targetsOf(tag, at?.let(::parseTime), known_at?.let(::parseTime), limitOf(limit))

  @Get("/api/taggings") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun history(@QueryParam target: String?, @QueryParam tag: String?, @QueryParam limit: Int?) = TaggingList(store.history(target, tag, limitOf(limit)))
}
