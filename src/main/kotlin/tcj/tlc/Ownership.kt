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
import misk.web.Post
import misk.web.QueryParam
import misk.web.RequestBody
import misk.web.RequestContentType
import misk.web.ResponseContentType
import misk.web.actions.WebAction

// ================================================================================== edges

data class EdgeDto(val token: String, val from: String, val to: String, val kind: String, val created_by: String, val note: String?, val created_at: String)

private val KIND_RE = Regex("[A-Z][A-Z0-9_]{0,31}")

/**
 * The dependency graph between external records: `from -> to` means "to is built on from", so a
 * failure (or a change) at `from` propagates to `to`. signal -> rule -> trigger -> hook.
 *
 * TLC is a mirror here, not the master: source services push the edges they know. Edges are
 * current-state only (no effective dating) and cycles are tolerated; traversal just visits once.
 */
@Singleton
class EdgeStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val entities: EntityStore,
  private val tlcers: Tlcers,
  private val logs: LogStore,
) {
  class Hop(val entityId: Long, val depth: Int, val kind: String?, val parentId: Long?)

  private fun kindOf(s: String?): String = (s ?: "FEEDS").trim().uppercase().also { if (!KIND_RE.matches(it)) throw BadRequestException("kind must match ${KIND_RE.pattern}") }

  private fun dto(c: Connection, rs: ResultSet) = EdgeDto(
    rs.getString("token"), entities.refOf(c, rs.getLong("from_entity_id")), entities.refOf(c, rs.getLong("to_entity_id")), rs.getString("kind"),
    rs.getString("created_by"), rs.getString("note"), iso(rs.getLong("created_at_ms")),
  )

  /** Idempotent. Both ends are vivified like any other write. */
  fun put(actor: Actor, from: String, to: String, kind: String?, note: String?): EdgeDto = db.tx { c ->
    tlcers.touch(c, actor)
    val k = kindOf(kind)
    val f = entities.resolveEntity(c, from, create = true)
    val t = entities.resolveEntity(c, to, create = true)
    if (f.entityId == t.entityId) throw BadRequestException("an edge needs two different records")
    val now = clock.millis()
    val n = c.exec(
      "INSERT IGNORE INTO entity_edges (created_at_ms, updated_at_ms, token, from_entity_id, to_entity_id, kind, created_by, note) VALUES (?,?,?,?,?,?,?,?)",
      listOf(now, now, newToken("EG"), f.entityId, t.entityId, k, actor.name, note),
    )
    if (n > 0) {
      logs.internal(c, t, LogLevel.INFO, "tlc.edges", "now depends on ${f.ref} ($k), per ${actor.name}")
      logs.internal(c, f, LogLevel.INFO, "tlc.edges", "now feeds ${t.ref} ($k), per ${actor.name}")
    }
    c.select("SELECT * FROM entity_edges WHERE from_entity_id = ? AND to_entity_id = ? AND kind = ?", listOf(f.entityId, t.entityId, k)) { dto(c, it) }.first()
  }

  fun remove(actor: Actor, from: String, to: String, kind: String?): Int = db.tx { c ->
    val f = entities.resolveOrNull(c, from)?.takeIf { it.entityId != null } ?: return@tx 0
    val t = entities.resolveOrNull(c, to)?.takeIf { it.entityId != null } ?: return@tx 0
    val n = c.exec("DELETE FROM entity_edges WHERE from_entity_id = ? AND to_entity_id = ? ${if (kind != null) "AND kind = ?" else ""}", listOfNotNull(f.entityId, t.entityId, kind?.let(::kindOf)))
    if (n > 0) logs.internal(c, t, LogLevel.INFO, "tlc.edges", "no longer depends on ${f.ref}, per ${actor.name}")
    n
  }

  /** Edges touching [target] (either end), or every edge. */
  fun list(target: String?, limit: Int): List<EdgeDto> = db.tx { c ->
    if (target == null) return@tx c.select("SELECT * FROM entity_edges ORDER BY id DESC LIMIT $limit") { dto(c, it) }
    val id = entities.resolveOrNull(c, target)?.entityId ?: return@tx listOf()
    c.select("SELECT * FROM entity_edges WHERE from_entity_id = ? OR to_entity_id = ? ORDER BY id LIMIT $limit", listOf(id, id)) { dto(c, it) }
  }

  /** BFS from [start] (depth 0, included). [down] follows from->to. Stops at [maxDepth] or [MAX_NODES]. */
  fun closure(c: Connection, start: Long, down: Boolean, maxDepth: Int): List<Hop> {
    val (a, b) = if (down) "from_entity_id" to "to_entity_id" else "to_entity_id" to "from_entity_id"
    val out = arrayListOf(Hop(start, 0, null, null))
    val seen = hashSetOf(start)
    var frontier = listOf(start)
    var depth = 0
    while (frontier.isNotEmpty() && depth < maxDepth && out.size < MAX_NODES) {
      depth++
      val rows = c.select("SELECT $a, $b, kind FROM entity_edges WHERE $a IN (${frontier.joinToString(",")}) ORDER BY id") { Triple(it.getLong(1), it.getLong(2), it.getString(3)) }
      val next = ArrayList<Long>()
      for ((p, n, kind) in rows) if (seen.add(n)) { out += Hop(n, depth, kind, p); next += n }
      frontier = next
    }
    return out
  }

  companion object {
    const val MAX_NODES = 5000
  }
}

// ============================================================================== ownership

data class OwnershipOpDto(
  val token: String,
  /** `service/entity/id`, or the type wildcard (`service/entity/` then `*`) for a type-wide default. */
  val target: String,
  val aspect: String,
  val owner: String,
  val role: String,
  val operation: String,
  val ldap: String?,
  val service: String?,
  val note: String?,
  val effective_at: String,
  val created_at: String,
  val feed_sync_id: Long?,
  val shard: Int,
)

data class HolderDto(
  /** A directory group. */
  val owner: String,
  val role: String,
  val since: String,
  /** Last CLAIM or AFFIRM. Ownership nobody has re-affirmed for `lapse_days` is LAPSED. */
  val affirmed_at: String,
  val age_days: Long,
  val lapsed: Boolean,
  val by: String?,
  /** DIRECT = claimed on this record; TYPE = inherited from the type-wide default. */
  val source: String,
)

data class CellDto(
  val target: String,
  val aspect: String,
  /** OWNED (exactly one live owner) | CONTESTED (two or more) | UNOWNED | LAPSED (one, stale). */
  val status: String,
  val source: String,
  val owners: List<HolderDto>,
  val stakeholders: List<HolderDto>,
  /** What owning this aspect means for this type of record, if someone wrote it down (`tlc own expect … --desc`). */
  val description: String? = null,
)

data class OwnershipView(val target: String, val at: String, val known_at: String?, val lapse_days: Int, val cells: List<CellDto>)
data class OwnershipResult(/** false = already held; recorded as an AFFIRM, or nothing to release. */ val changed: Boolean, val operation: String?, val cell: CellDto)
data class AffirmResult(val affirmed: Int, val owner: String)
data class AspectDto(val entity_type: String, val aspect: String, val description: String?, val created_by: String, val created_at: String, val deleted_at: String?)

data class CoverageRow(
  val entity_type: String,
  val aspect: String,
  /** records of the type x this aspect */
  val cells: Int,
  val owned: Int,
  val lapsed: Int,
  val contested: Int,
  val unowned: Int,
  /** of the cells with an owner: claimed on the record itself vs inherited from the type default */
  val direct: Int,
  val inherited: Int,
  /** owned / cells: exactly one live owner. */
  val pct_owned: Double,
)

data class OwnerRow(val owner: String, val owned: Int, val lapsed: Int, val contested: Int, val stakeholder: Int)
data class Coverage(val at: String, val lapse_days: Int, val total: CoverageRow, val rows: List<CoverageRow>, val owners: List<OwnerRow>)
data class NodeDto(val target: String, val depth: Int, /** edge kind that led here */ val via: String?, val parent: String?, val cells: List<CellDto>)
data class AffectedParty(val owner: String, val roles: List<String>, val aspects: List<String>, val records: Int, val nearest: Int, val contested: Boolean)
data class Affected(val target: String, val direction: String, val nodes: List<NodeDto>, val parties: List<AffectedParty>, val truncated: Boolean)

/** One line of an approval rule: which aspect's owner must consent (or just be told) for this action. */
data class RuleAspectDto(
  val aspect: String,
  /** CONSENT: the aspect's owner becomes an approving group. NOTIFY: told, not asked. */
  val mode: String = "CONSENT",
  /** TARGET: owners of the record itself. DOWNSTREAM: also owners of everything built on it. */
  val scope: String = "TARGET",
  /** Group that stands in when a CONSENT aspect has no owner. Without one, filing is a 409. */
  val fallback_group: String? = null,
  /**
   * Impact gate. With a threshold, [mode] applies only when the declared impact on this aspect has
   * |magnitude| > threshold. At or below it: CONSENT is demoted to NOTIFY (told, with the number),
   * NOTIFY is skipped. Undeclared or unknown impact counts as above. See docs/ownership.md.
   */
  val threshold: Double? = null,
  /** What the threshold is measured in, e.g. "flags/day". If set, a declared impact must use the same unit. */
  val unit: String? = null,
)

/** A group pulled into one approvable by ownership, frozen at filing time. */
data class PartyDto(
  val directory_group: String,
  val mode: String,
  val aspects: List<String>,
  /** Worst state among the cells that pulled it in: CONTESTED > LAPSED > UNOWNED (fallback) > OWNED; STAKEHOLDER for notify-only. */
  val status: String,
  val source: String,
  val via: List<String>,
  val slack_channel: String?,
  val email: String?,
)

private val ASPECT_RE = Regex("[a-z0-9][a-z0-9_.:-]{0,95}")
private val OWNER_RE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
private const val DAY_MS = 86_400_000L
private val STATUS_RANK = listOf("STAKEHOLDER", "OWNED", "UNOWNED", "LAPSED", "CONTESTED")

private class OwnOp(val id: Long, val scopeKey: String, val typeId: Long, val entityId: Long?, val aspect: String, val owner: String, val role: String, val op: String, val by: String?, val eff: Long)
private data class Held(val owner: String, val role: String, val since: Long, val affirmed: Long, val by: String?)
private typealias OwnState = Map<String, Map<String, Map<String, Held>>> // scope_key -> aspect -> owner -> held

/**
 * Ownership is per *aspect* of a record, not per record: ENG owns `availability` of a rule, OPS owns
 * `disposition`, DS owns `efficacy`. A row is a claim by a directory group, effective-dated and
 * append-only exactly like taggings, so "who owned this on the 3rd" is the same query as "who owns
 * this", and `known_at` answers what TLC would have said then.
 *
 * Roles: OWNER is accountable (and is asked to consent when an approval rule names the aspect);
 * STAKEHOLDER is told. The one invariant worth having is *at most one OWNER per (record, aspect)*,
 * and it is deliberately not enforced at write time: a second claim is recorded and the cell reads
 * CONTESTED until someone releases. Refusing the write would only hide the disagreement.
 *
 * A claim on the type wildcard (`service/entity/` then `*`) is the type-wide default; a claim on a record overrides the
 * default's OWNER for that aspect (stakeholders are unioned).
 */
@Singleton
class OwnershipStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: TlcConfig,
  private val entities: EntityStore,
  private val tlcers: Tlcers,
  private val logs: LogStore,
  private val directory: Directory,
  private val edges: EdgeStore,
) {
  private class Scope(val key: String, val typeId: Long, val entityId: Long?, val ref: String) {
    val target: Target? get() = entityId?.let { Target(it, typeId, null, ref) }
  }

  private fun aspectOf(s: String): String = s.trim().lowercase().also { if (!ASPECT_RE.matches(it)) throw BadRequestException("aspect must match ${ASPECT_RE.pattern}") }
  private fun ownerOf(s: String): String = s.trim().also { if (!OWNER_RE.matches(it)) throw BadRequestException("owner must be a directory group matching ${OWNER_RE.pattern}") }
  private fun roleOf(s: String?): String = (s ?: "OWNER").trim().uppercase().also { if (it != "OWNER" && it != "STAKEHOLDER") throw BadRequestException("role is OWNER or STAKEHOLDER") }

  private fun typeRef(c: Connection, typeId: Long): String =
    c.select("SELECT source_service, source_entity FROM external_entity_types WHERE id = ?", listOf(typeId)) { "${it.getString(1)}/${it.getString(2)}" }.first()

  private fun scope(c: Connection, ref: String, create: Boolean): Scope? {
    val r = ref.trim()
    if (r.endsWith("/*")) {
      val typeId = entities.typeId(c, r.dropLast(2))
      return Scope("ET:$typeId", typeId, null, typeRef(c, typeId) + "/*")
    }
    val t = try { entities.resolveEntity(c, r, create) } catch (e: NotFoundException) { if (create) throw e else return null }
    return Scope(t.key, t.entityTypeId!!, t.entityId, t.ref)
  }

  private fun refOfScope(c: Connection, key: String, cache: MutableMap<String, String>): String = cache.getOrPut(key) {
    if (key.startsWith("ET:")) typeRef(c, key.substring(3).toLong()) + "/*" else entities.refOfKey(c, key)
  }

  // --------------------------------------------------------------------------- op log

  private fun ops(c: Connection, where: String, args: List<Any?>, at: Long?, knownAt: Long?): List<OwnOp> {
    val a = ArrayList(args)
    var w = where
    if (at != null) { w += " AND effective_at_ms <= ?"; a += at }
    if (knownAt != null) { w += " AND created_at_ms <= ?"; a += knownAt }
    return c.select("SELECT * FROM ownerships WHERE $w ORDER BY effective_at_ms, id", a) { rs ->
      OwnOp(rs.getLong("id"), rs.getString("scope_key"), rs.getLong("external_entity_type_id"), rs.longOrNull("external_entity_id"), rs.getString("aspect"), rs.getString("owner"), rs.getString("role"), rs.getString("operation"), rs.getString("ldap") ?: rs.getString("service")?.let { "svc:$it" }, rs.getLong("effective_at_ms"))
    }
  }

  private fun fold(ops: List<OwnOp>): OwnState {
    val state = HashMap<String, HashMap<String, LinkedHashMap<String, Held>>>()
    for (o in ops) {
      val m = state.getOrPut(o.scopeKey) { HashMap() }.getOrPut(o.aspect) { LinkedHashMap() }
      when (o.op) {
        // Re-claiming in the same role is an affirmation: `since` must not move.
        "CLAIM" -> m[o.owner] = m[o.owner]?.takeIf { it.role == o.role }?.copy(affirmed = o.eff) ?: Held(o.owner, o.role, o.eff, o.eff, o.by)
        "AFFIRM" -> m[o.owner]?.let { m[o.owner] = it.copy(affirmed = o.eff) }
        else -> m.remove(o.owner)
      }
    }
    return state
  }

  private fun cell(ref: String, aspect: String, direct: Map<String, Held>?, type: Map<String, Held>?, at: Long): CellDto {
    val lapse = config.tlc.ownership_lapse_days * DAY_MS
    fun h(x: Held, src: String) = HolderDto(x.owner, x.role, iso(x.since), iso(x.affirmed), (at - x.affirmed).coerceAtLeast(0) / DAY_MS, at - x.affirmed > lapse, x.by, src)
    val d = direct.orEmpty().values
    val t = type.orEmpty().values
    val owners = d.filter { it.role == "OWNER" }.map { h(it, "DIRECT") }.ifEmpty { t.filter { it.role == "OWNER" }.map { h(it, "TYPE") } }
    val taken = owners.map { it.owner }.toSet()
    val stake = LinkedHashMap<String, HolderDto>()
    t.filter { it.role == "STAKEHOLDER" }.forEach { stake[it.owner] = h(it, "TYPE") }
    d.filter { it.role == "STAKEHOLDER" }.forEach { stake[it.owner] = h(it, "DIRECT") }
    val status = when {
      owners.isEmpty() -> "UNOWNED"
      owners.size > 1 -> "CONTESTED"
      owners[0].lapsed -> "LAPSED"
      else -> "OWNED"
    }
    return CellDto(ref, aspect, status, owners.firstOrNull()?.source ?: "NONE", owners, stake.values.filter { it.owner !in taken })
  }

  private fun descriptions(c: Connection, typeId: Long): Map<String, String?> =
    c.select("SELECT aspect, description FROM ownership_aspects WHERE external_entity_type_id = ? AND deleted_at_ms IS NULL", listOf(typeId)) { it.getString(1) to it.getString(2) }.toMap()

  private fun expected(c: Connection, typeId: Long): List<String> =
    c.select("SELECT aspect FROM ownership_aspects WHERE external_entity_type_id = ? AND deleted_at_ms IS NULL ORDER BY aspect", listOf(typeId)) { it.getString(1) }

  /** Every cell of one scope: the type's expected aspects plus anything actually claimed. */
  private fun cellsOf(c: Connection, s: Scope, at: Long, knownAt: Long?): List<CellDto> {
    val typeKey = "ET:${s.typeId}"
    val st = fold(ops(c, "scope_key IN (?, ?)", listOf(s.key, typeKey), at, knownAt))
    val direct = st[s.key].orEmpty()
    val type = if (s.entityId != null) st[typeKey].orEmpty() else mapOf()
    val aspects = (expected(c, s.typeId) + direct.filterValues { it.isNotEmpty() }.keys + type.filterValues { it.isNotEmpty() }.keys).toSortedSet()
    val desc = descriptions(c, s.typeId)
    return aspects.map { cell(s.ref, it, direct[it], type[it], at).copy(description = desc[it]) }
  }

  private fun cellsOfEntity(c: Connection, entityId: Long, at: Long): List<CellDto> {
    val typeId = c.select("SELECT external_entity_type_id FROM external_entities WHERE id = ?", listOf(entityId)) { it.getLong(1) }.first()
    return cellsOf(c, Scope("E:$entityId", typeId, entityId, entities.refOf(c, entityId)), at, null)
  }

  /** Every (record, expected aspect) cell of a type. One query for the ops, folded in memory. */
  private fun cellsOfType(c: Connection, typeId: Long, at: Long, knownAt: Long?): List<CellDto> {
    val st = fold(ops(c, "external_entity_type_id = ?", listOf(typeId), at, knownAt))
    val type = st["ET:$typeId"].orEmpty()
    val aspects = expected(c, typeId)
    val tref = typeRef(c, typeId)
    val rows = c.select("SELECT id, source_entity_id FROM external_entities WHERE external_entity_type_id = ? AND deleted_at_ms IS NULL ORDER BY id LIMIT $MAX_RECORDS", listOf(typeId)) { it.getLong(1) to it.getString(2) }
    val desc = descriptions(c, typeId)
    return rows.flatMap { (id, sid) -> aspects.map { a -> cell("$tref/$sid", a, st["E:$id"]?.get(a), type[a], at).copy(description = desc[a]) } }
  }

  // ---------------------------------------------------------------------------- writes

  /** A human may only speak for a group they are in. Admins and service callers (the source of truth syncing in) may speak for any. */
  private fun mayActFor(c: Connection, actor: Actor, owner: String) {
    if (actor.admin || actor.service != null) return
    if (owner !in directory.memberOf(c, actor.ldap!!)) forbidden("${actor.ldap} is not in $owner: only its members, an admin or a service can claim or release for it")
  }

  /** The type row lock serializes ownership writes per type, so read-then-append below is safe. */
  private fun lock(c: Connection, typeId: Long) {
    c.select("SELECT id FROM external_entity_types WHERE id = ? FOR UPDATE", listOf(typeId)) { 1 }
  }

  private fun write(c: Connection, typeId: Long, entityId: Long?, key: String, aspect: String, owner: String, role: String, op: String, actor: Actor, note: String?, eff: Long) {
    val now = clock.millis()
    c.insert(
      "INSERT INTO ownerships (created_at_ms, updated_at_ms, token, external_entity_type_id, external_entity_id, scope_key, aspect, owner, role, operation, ldap, service, note, effective_at_ms, shard) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
      listOf(now, now, newToken("OW"), typeId, entityId, key, aspect, owner, role, op, actor.ldap, actor.service, note, eff, shardOf(key, config.tlc.feed_shards)),
    )
  }

  private fun heldAt(c: Connection, s: Scope, aspect: String, owner: String, at: Long): Held? =
    fold(ops(c, "scope_key = ? AND aspect = ? AND owner = ?", listOf(s.key, aspect, owner), at, null))[s.key]?.get(aspect)?.get(owner)

  private fun cellNow(c: Connection, s: Scope, aspect: String): CellDto =
    cellsOf(c, s, clock.millis(), null).firstOrNull { it.aspect == aspect } ?: cell(s.ref, aspect, null, null, clock.millis())

  private fun audit(c: Connection, s: Scope, level: LogLevel, msg: String) {
    s.target?.let { logs.internal(c, it, level, "tlc.ownership", msg) }
  }

  fun claim(actor: Actor, targetRef: String, aspect: String, owner: String, role: String?, effectiveAt: Long?, note: String?): OwnershipResult = db.tx { c ->
    tlcers.touch(c, actor)
    val a = aspectOf(aspect)
    val o = ownerOf(owner)
    val r = roleOf(role)
    mayActFor(c, actor, o)
    val s = scope(c, targetRef, create = true)!!
    lock(c, s.typeId)
    // Deliberately does not add the aspect to the type's *expected* set: one stray claim must not
    // put every other record of the type into the coverage denominator as UNOWNED. See [expect].
    val now = clock.millis()
    val eff = effectiveAt ?: now
    val op = if (heldAt(c, s, a, o, eff)?.role == r) "AFFIRM" else "CLAIM"
    write(c, s.typeId, s.entityId, s.key, a, o, r, op, actor, note, eff)
    val cell = cellNow(c, s, a)
    if (op == "CLAIM") {
      audit(c, s, LogLevel.INFO, "$o ${if (r == "OWNER") "claims ownership of" else "is a stakeholder in"} '$a', per ${actor.name}${if (eff != now) " effective ${iso(eff)}" else ""}")
      if (r == "OWNER" && cell.status == "CONTESTED") audit(c, s, LogLevel.WARN, "'$a' is now CONTESTED: ${cell.owners.joinToString { it.owner }}")
    }
    OwnershipResult(op == "CLAIM", op, cell)
  }

  fun release(actor: Actor, targetRef: String, aspect: String, owner: String, effectiveAt: Long?, note: String?): OwnershipResult = db.tx { c ->
    tlcers.touch(c, actor)
    val a = aspectOf(aspect)
    val o = ownerOf(owner)
    mayActFor(c, actor, o)
    val s = scope(c, targetRef, create = false) ?: throw NotFoundException("TLC has no record of $targetRef")
    lock(c, s.typeId)
    val eff = effectiveAt ?: clock.millis()
    val held = heldAt(c, s, a, o, eff) ?: return@tx OwnershipResult(false, null, cellNow(c, s, a))
    write(c, s.typeId, s.entityId, s.key, a, o, held.role, "RELEASE", actor, note, eff)
    val cell = cellNow(c, s, a)
    audit(c, s, LogLevel.INFO, "$o releases '$a' (${held.role}), per ${actor.name}")
    if (held.role == "OWNER" && cell.status == "UNOWNED") audit(c, s, LogLevel.WARN, "'$a' is now UNOWNED")
    OwnershipResult(true, "RELEASE", cell)
  }

  /** Release + claim at one instant, so there is no moment with zero or two owners. The giver (or an admin / service) does it. */
  fun transfer(actor: Actor, targetRef: String, aspect: String, from: String, to: String, effectiveAt: Long?, note: String?): OwnershipResult = db.tx { c ->
    tlcers.touch(c, actor)
    val a = aspectOf(aspect)
    val f = ownerOf(from)
    val t = ownerOf(to)
    if (f == t) throw BadRequestException("from and to are the same group")
    mayActFor(c, actor, f)
    val s = scope(c, targetRef, create = false) ?: throw NotFoundException("TLC has no record of $targetRef")
    lock(c, s.typeId)
    val eff = effectiveAt ?: clock.millis()
    val held = heldAt(c, s, a, f, eff) ?: throw ConflictException("$f does not hold '$a' on ${s.ref} at ${iso(eff)}")
    write(c, s.typeId, s.entityId, s.key, a, f, held.role, "RELEASE", actor, note, eff)
    write(c, s.typeId, s.entityId, s.key, a, t, held.role, "CLAIM", actor, note, eff)
    audit(c, s, LogLevel.INFO, "'$a' (${held.role}) transferred $f -> $t by ${actor.name}")
    OwnershipResult(true, "TRANSFER", cellNow(c, s, a))
  }

  /** "Yes, still ours." Resets the lapse clock on everything [owner] holds, optionally narrowed to a target and/or aspect. */
  fun affirm(actor: Actor, owner: String, targetRef: String?, aspect: String?): AffirmResult = db.tx { c ->
    tlcers.touch(c, actor)
    val o = ownerOf(owner)
    mayActFor(c, actor, o)
    val where = ArrayList<String>().apply { add("owner = ?") }
    val args = ArrayList<Any?>().apply { add(o) }
    if (targetRef != null) { where += "scope_key = ?"; args += (scope(c, targetRef, create = false)?.key ?: throw NotFoundException("TLC has no record of $targetRef")) }
    if (aspect != null) { where += "aspect = ?"; args += aspectOf(aspect) }
    val now = clock.millis()
    val all = ops(c, where.joinToString(" AND "), args, now, null)
    val meta = all.associateBy { it.scopeKey }
    var n = 0
    for ((key, byAspect) in fold(all)) for ((a, holders) in byAspect) {
      val h = holders[o] ?: continue
      val m = meta.getValue(key)
      write(c, m.typeId, m.entityId, key, a, o, h.role, "AFFIRM", actor, null, now)
      n++
    }
    AffirmResult(n, o)
  }

  // ----------------------------------------------------------------------------- reads

  fun of(targetRef: String, at: Long?, knownAt: Long?): OwnershipView = db.tx { c ->
    val t = at ?: clock.millis()
    val s = scope(c, targetRef, create = false)
    OwnershipView(s?.ref ?: targetRef, iso(t), knownAt?.let(::iso), config.tlc.ownership_lapse_days, s?.let { cellsOf(c, it, t, knownAt) }.orEmpty())
  }

  /** The raw claim log, newest first. */
  fun history(targetRef: String?, aspect: String?, owner: String?, limit: Int): List<OwnershipOpDto> = db.tx { c ->
    val where = ArrayList<String>()
    val args = ArrayList<Any?>()
    if (targetRef != null) { where += "scope_key = ?"; args += (scope(c, targetRef, create = false)?.key ?: return@tx listOf()) }
    if (aspect != null) { where += "aspect = ?"; args += aspectOf(aspect) }
    if (owner != null) { where += "owner = ?"; args += owner }
    opDtos(c, "${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY id DESC LIMIT $limit", args)
  }

  private fun opDtos(c: Connection, tail: String, args: List<Any?>): List<OwnershipOpDto> {
    val cache = HashMap<String, String>()
    val rows = c.select("SELECT g.* FROM ownerships g $tail", args) { rs ->
      OwnershipOpDto(rs.getString("token"), rs.getString("scope_key"), rs.getString("aspect"), rs.getString("owner"), rs.getString("role"), rs.getString("operation"), rs.getString("ldap"), rs.getString("service"), rs.getString("note"), iso(rs.getLong("effective_at_ms")), iso(rs.getLong("created_at_ms")), rs.longOrNull("feed_sync_id"), rs.getInt("shard"))
    }
    return rows.map { it.copy(target = refOfScope(c, it.target, cache)) }
  }

  fun feed(after: Long, limit: Int, shardSql: String): List<OwnershipOpDto> = db.tx { c ->
    opDtos(c, "WHERE g.feed_sync_id > ? $shardSql ORDER BY g.feed_sync_id LIMIT $limit", listOf(after))
  }

  private fun typeIds(c: Connection, type: String?): List<Long> =
    if (type != null) listOf(entities.typeId(c, type))
    else c.select("SELECT DISTINCT t.id FROM external_entity_types t JOIN ownership_aspects a ON a.external_entity_type_id = t.id WHERE t.deleted_at_ms IS NULL AND a.deleted_at_ms IS NULL ORDER BY t.id") { it.getLong(1) }

  /** Cells that are not cleanly owned: the worklist. */
  fun gaps(type: String?, aspect: String?, status: String?, owner: String?, at: Long?, limit: Int): List<CellDto> = db.tx { c ->
    val t = at ?: clock.millis()
    val want = status?.uppercase()?.split(',')?.toSet()
    typeIds(c, type).asSequence().flatMap { cellsOfType(c, it, t, null) }
      .filter { cl -> (want?.contains(cl.status) ?: (cl.status != "OWNED")) && (aspect == null || cl.aspect == aspect) && (owner == null || cl.owners.any { it.owner == owner }) }
      .take(limit).toList()
  }

  /** Everything a group holds, including what it inherits from a type-wide default. */
  fun ownedBy(owner: String, at: Long?, limit: Int): List<CellDto> = db.tx { c ->
    val t = at ?: clock.millis()
    val types = c.select("SELECT DISTINCT external_entity_type_id FROM ownerships WHERE owner = ?", listOf(owner)) { it.getLong(1) }
    types.asSequence().flatMap { cellsOfType(c, it, t, null) }.filter { cl -> cl.owners.any { it.owner == owner } || cl.stakeholders.any { it.owner == owner } }.take(limit).toList()
  }

  fun coverage(type: String?, at: Long?, knownAt: Long?): Coverage = db.tx { c ->
    val t = at ?: clock.millis()
    val rows = ArrayList<CoverageRow>()
    val owners = HashMap<String, IntArray>() // owned, lapsed, contested, stakeholder
    for (typeId in typeIds(c, type)) {
      val tref = typeRef(c, typeId)
      val cells = cellsOfType(c, typeId, t, knownAt)
      for ((aspect, cs) in cells.groupBy { it.aspect }.toSortedMap()) rows += row(tref, aspect, cs)
      for (cl in cells) {
        val slot = when (cl.status) { "OWNED" -> 0; "LAPSED" -> 1; else -> 2 }
        cl.owners.forEach { owners.getOrPut(it.owner) { IntArray(4) }[slot]++ }
        cl.stakeholders.forEach { owners.getOrPut(it.owner) { IntArray(4) }[3]++ }
      }
    }
    val total = rows.fold(CoverageRow("*", "*", 0, 0, 0, 0, 0, 0, 0, 0.0)) { a, r -> a.copy(cells = a.cells + r.cells, owned = a.owned + r.owned, lapsed = a.lapsed + r.lapsed, contested = a.contested + r.contested, unowned = a.unowned + r.unowned, direct = a.direct + r.direct, inherited = a.inherited + r.inherited) }
    Coverage(iso(t), config.tlc.ownership_lapse_days, total.copy(pct_owned = pct(total.owned, total.cells)), rows, owners.map { (o, n) -> OwnerRow(o, n[0], n[1], n[2], n[3]) }.sortedWith(compareByDescending<OwnerRow> { it.owned }.thenBy { it.owner }))
  }

  private fun pct(n: Int, d: Int): Double = if (d == 0) 0.0 else Math.round(1000.0 * n / d) / 10.0

  private fun row(type: String, aspect: String, cs: List<CellDto>): CoverageRow {
    val by = cs.groupingBy { it.status }.eachCount()
    val owned = by["OWNED"] ?: 0
    return CoverageRow(type, aspect, cs.size, owned, by["LAPSED"] ?: 0, by["CONTESTED"] ?: 0, by["UNOWNED"] ?: 0, cs.count { it.source == "DIRECT" }, cs.count { it.source == "TYPE" }, pct(owned, cs.size))
  }

  /**
   * Blast radius: [targetRef] plus everything built on it (direction=down) or everything it is built
   * on (up), each with its ownership cells, and the distinct parties across the lot.
   */
  fun affected(targetRef: String, direction: String?, depth: Int?): Affected = db.tx { c ->
    val down = when ((direction ?: "down").lowercase()) { "down" -> true; "up" -> false; else -> throw BadRequestException("direction is down or up") }
    val s = scope(c, targetRef, create = false)?.takeIf { it.entityId != null } ?: throw NotFoundException("TLC has no record of $targetRef")
    val hops = edges.closure(c, s.entityId!!, down, (depth ?: 16).coerceIn(0, 64))
    val now = clock.millis()
    val refs = HashMap<Long, String>()
    val nodes = hops.map { h -> NodeDto(refs.getOrPut(h.entityId) { entities.refOf(c, h.entityId) }, h.depth, h.kind, h.parentId?.let(refs::get), cellsOfEntity(c, h.entityId, now)) }
    class Acc(var nearest: Int) { val roles = sortedSetOf<String>(); val aspects = sortedSetOf<String>(); val records = hashSetOf<String>(); var contested = false }
    val acc = LinkedHashMap<String, Acc>()
    for (n in nodes) for (cl in n.cells) for (h in cl.owners + cl.stakeholders) {
      val x = acc.getOrPut(h.owner) { Acc(n.depth) }
      x.roles += h.role; x.aspects += cl.aspect; x.records += n.target; x.nearest = minOf(x.nearest, n.depth)
      if (h.role == "OWNER" && cl.status == "CONTESTED") x.contested = true
    }
    Affected(s.ref, if (down) "down" else "up", nodes, acc.map { (o, x) -> AffectedParty(o, x.roles.toList(), x.aspects.toList(), x.records.size, x.nearest, x.contested) }.sortedWith(compareBy<AffectedParty> { it.nearest }.thenBy { it.owner }), hops.size >= EdgeStore.MAX_NODES)
  }

  // ------------------------------------------------------------------- for approvals

  /**
   * Who an action on [entityId] has to go through, per the rule's aspects, as of now. Owners of a
   * CONSENT aspect become approving groups; owners of a NOTIFY aspect and all stakeholders are told.
   * A CONTESTED aspect yields every claimant (they all have to agree — that is what contested costs).
   */
  fun resolveParties(c: Connection, entityId: Long, ruleAspects: List<RuleAspectDto>): List<PartyDto> {
    if (ruleAspects.isEmpty()) return listOf()
    val now = clock.millis()
    val ref = entities.refOf(c, entityId)
    val closure by lazy { edges.closure(c, entityId, true, 64).map { it.entityId } }
    val cells = HashMap<Long, List<CellDto>>()
    class Acc { var consent = false; val aspects = sortedSetOf<String>(); val via = linkedSetOf<String>(); var status = ""; var source = "DIRECT" }
    val acc = LinkedHashMap<String, Acc>()
    fun add(group: String, consent: Boolean, aspect: String, status: String, source: String, via: String) {
      val x = acc.getOrPut(group) { Acc() }
      x.aspects += aspect; x.via += via
      // CONSENT outranks NOTIFY; within a level, keep the worst cell status that pulled the group in.
      if ((consent && !x.consent) || (consent == x.consent && STATUS_RANK.indexOf(status) > STATUS_RANK.indexOf(x.status))) { x.status = status; x.source = source }
      x.consent = x.consent || consent
    }
    for (ra in ruleAspects) {
      var owners = 0
      for (id in if (ra.scope == "DOWNSTREAM") closure else listOf(entityId)) {
        val cl = cells.getOrPut(id) { cellsOfEntity(c, id, now) }.firstOrNull { it.aspect == ra.aspect } ?: continue
        for (o in cl.owners) { add(o.owner, ra.mode == "CONSENT", ra.aspect, cl.status, o.source, cl.target); owners++ }
        for (st in cl.stakeholders) add(st.owner, false, ra.aspect, "STAKEHOLDER", st.source, cl.target)
      }
      if (owners == 0 && ra.mode == "CONSENT") {
        val fb = ra.fallback_group ?: throw ConflictException("nobody owns '${ra.aspect}' of $ref${if (ra.scope == "DOWNSTREAM") " or anything downstream" else ""}: claim it (POST /api/ownership/claim) or give the rule a fallback_group for that aspect")
        add(fb, true, ra.aspect, "UNOWNED", "FALLBACK", ref)
      }
    }
    return acc.map { (g, x) ->
      val (slack, email) = directory.contact(c, g)
      PartyDto(g, if (x.consent) "CONSENT" else "NOTIFY", x.aspects.toList(), x.status, x.source, x.via.take(8), slack, email)
    }
  }

  /** Groups owning any aspect of [entityId] or of anything built on it: the people entitled to pull the cord. */
  fun ownersDownstream(c: Connection, entityId: Long): Set<String> {
    val now = clock.millis()
    return edges.closure(c, entityId, true, 64).flatMapTo(sortedSetOf()) { h -> cellsOfEntity(c, h.entityId, now).flatMap { cl -> cl.owners.map { it.owner } } }
  }

  // --------------------------------------------------------------------------- aspects

  private fun aspectDtos(c: Connection, where: String, args: List<Any?>): List<AspectDto> =
    c.select("SELECT a.*, t.source_service, t.source_entity FROM ownership_aspects a JOIN external_entity_types t ON t.id = a.external_entity_type_id $where ORDER BY t.source_service, t.source_entity, a.aspect", args) {
      AspectDto("${it.getString("source_service")}/${it.getString("source_entity")}", it.getString("aspect"), it.getString("description"), it.getString("created_by"), iso(it.getLong("created_at_ms")), it.isoOrNull("deleted_at_ms"))
    }

  fun aspects(type: String?): List<AspectDto> = db.tx { c ->
    if (type == null) aspectDtos(c, "WHERE a.deleted_at_ms IS NULL", listOf()) else aspectDtos(c, "WHERE a.deleted_at_ms IS NULL AND a.external_entity_type_id = ?", listOf(entities.typeId(c, type)))
  }

  /** Declares that every record of [type] is expected to have an owner for [aspect]: it enters the coverage denominator. Idempotent; revives a dropped one. */
  fun expect(actor: Actor, type: String, aspect: String, description: String?): AspectDto = db.tx { c ->
    val typeId = entities.typeId(c, type)
    val a = aspectOf(aspect)
    val now = clock.millis()
    c.exec(
      """
      INSERT INTO ownership_aspects (created_at_ms, updated_at_ms, external_entity_type_id, aspect, description, created_by) VALUES (?,?,?,?,?,?)
      ON DUPLICATE KEY UPDATE deleted_at_ms = NULL, description = COALESCE(VALUES(description), description), updated_at_ms = VALUES(updated_at_ms), version = version + 1
      """.trimIndent(),
      listOf(now, now, typeId, a, description, actor.name),
    )
    aspectDtos(c, "WHERE a.external_entity_type_id = ? AND a.aspect = ?", listOf(typeId, a)).first()
  }

  /** Takes the aspect out of the denominator. Existing claims stay in the log and still show on the records that have them. */
  fun unexpect(type: String, aspect: String): AspectDto = db.tx { c ->
    val typeId = entities.typeId(c, type)
    val a = aspectOf(aspect)
    c.exec("UPDATE ownership_aspects SET deleted_at_ms = ?, updated_at_ms = ?, version = version + 1 WHERE external_entity_type_id = ? AND aspect = ? AND deleted_at_ms IS NULL", listOf(clock.millis(), clock.millis(), typeId, a))
    aspectDtos(c, "WHERE a.external_entity_type_id = ? AND a.aspect = ?", listOf(typeId, a)).firstOrNull() ?: throw NotFoundException("$type has no aspect '$a'")
  }

  companion object {
    /** Coverage folds a whole type in memory; past this many records it reports on the oldest N. Materialize if that ever bites. */
    const val MAX_RECORDS = 20000
  }
}

// ================================================================================ actions

data class OwnershipReq(val target: String?, val aspect: String?, val owner: String?, val role: String?, val from: String?, val to: String?, val effective_at: String?, val note: String?)
data class OwnershipOps(val ownerships: List<OwnershipOpDto>)
data class CellList(val cells: List<CellDto>)
data class AspectReq(val type: String?, val aspect: String?, val description: String?)
data class AspectList(val aspects: List<AspectDto>)
data class EdgeReq(val from: String?, val to: String?, val kind: String?, val note: String?)
data class EdgeList(val edges: List<EdgeDto>)
data class Removed(val removed: Int)

class OwnershipActions @Inject constructor(private val store: OwnershipStore, private val actors: Actors) : WebAction {
  @Post("/api/ownership/claim") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun claim(@RequestBody r: OwnershipReq) = store.claim(actors.current(), r.target.required("target"), r.aspect.required("aspect"), r.owner.required("owner"), r.role, r.effective_at?.let(::parseTime), r.note)

  @Post("/api/ownership/release") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun release(@RequestBody r: OwnershipReq) = store.release(actors.current(), r.target.required("target"), r.aspect.required("aspect"), r.owner.required("owner"), r.effective_at?.let(::parseTime), r.note)

  @Post("/api/ownership/transfer") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun transfer(@RequestBody r: OwnershipReq) = store.transfer(actors.current(), r.target.required("target"), r.aspect.required("aspect"), r.from.required("from"), r.to.required("to"), r.effective_at?.let(::parseTime), r.note)

  /** `target` and `aspect` optional: omitted = everything the group holds. */
  @Post("/api/ownership/affirm") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun affirm(@RequestBody r: OwnershipReq) = store.affirm(actors.current(), r.owner.required("owner"), r.target, r.aspect)

  /** `target` is `service/entity/id`, an E- token, or the type wildcard (`service/entity/` then `*`) for the type default. */
  @Get("/api/ownership") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun of(@QueryParam target: String, @QueryParam at: String?, @QueryParam known_at: String?) = store.of(target, at?.let(::parseTime), known_at?.let(::parseTime))

  @Get("/api/ownership/history") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun history(@QueryParam target: String?, @QueryParam aspect: String?, @QueryParam owner: String?, @QueryParam limit: Int?) = OwnershipOps(store.history(target, aspect, owner, limitOf(limit)))

  @Get("/api/ownership/coverage") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun coverage(@QueryParam type: String?, @QueryParam at: String?, @QueryParam known_at: String?) = store.coverage(type, at?.let(::parseTime), known_at?.let(::parseTime))

  /** Default: everything not OWNED. `status=UNOWNED,CONTESTED` narrows; `status=OWNED` inverts. */
  @Get("/api/ownership/gaps") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun gaps(@QueryParam type: String?, @QueryParam aspect: String?, @QueryParam status: String?, @QueryParam owner: String?, @QueryParam at: String?, @QueryParam limit: Int?) =
    CellList(store.gaps(type, aspect, status, owner, at?.let(::parseTime), limitOf(limit)))

  @Get("/api/ownership/owned-by") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun ownedBy(@QueryParam owner: String, @QueryParam at: String?, @QueryParam limit: Int?) = CellList(store.ownedBy(owner, at?.let(::parseTime), limitOf(limit)))

  @Get("/api/ownership/affected") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun affected(@QueryParam target: String, @QueryParam direction: String?, @QueryParam depth: Int?) = store.affected(target, direction, depth)

  @Get("/api/ownership/aspects") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun aspects(@QueryParam type: String?) = AspectList(store.aspects(type))

  @Post("/api/ownership/aspects") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun expect(@RequestBody r: AspectReq) = store.expect(actors.current(), r.type.required("type"), r.aspect.required("aspect"), r.description)

  @Delete("/api/ownership/aspects") @Authenticated(capabilities = [ADMIN]) @ResponseContentType(JSON)
  fun unexpect(@QueryParam type: String, @QueryParam aspect: String) = store.unexpect(type, aspect)
}

class EdgeActions @Inject constructor(private val store: EdgeStore, private val actors: Actors) : WebAction {
  /** `from` feeds `to`: `{"from": "sentinel/signals/velocity_24h", "to": "sentinel/rules/R1", "kind": "FEEDS"}`. Idempotent. */
  @Post("/api/edges") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun put(@RequestBody r: EdgeReq) = store.put(actors.current(), r.from.required("from"), r.to.required("to"), r.kind, r.note)

  @Get("/api/edges") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun list(@QueryParam target: String?, @QueryParam limit: Int?) = EdgeList(store.list(target, limitOf(limit)))

  @Delete("/api/edges") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun remove(@QueryParam from: String, @QueryParam to: String, @QueryParam kind: String?) = Removed(store.remove(actors.current(), from, to, kind))
}
