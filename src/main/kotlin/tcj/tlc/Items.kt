package tcj.tlc

import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import misk.exceptions.BadRequestException
import misk.exceptions.NotFoundException
import misk.security.authz.Authenticated
import misk.web.Get
import misk.web.PathParam
import misk.web.Post
import misk.web.QueryParam
import misk.web.RequestBody
import misk.web.RequestContentType
import misk.web.ResponseContentType
import misk.web.actions.WebAction

/**
 * Items: the open loops. One row per thing that is waiting on a person, whatever raised it. Two kinds of source:
 *
 * - **captured** (`POST /api/items`): a todo, an idea, a question someone needs answered. The item is the record;
 *   it stays open until a person closes it.
 * - **derived** (`POST /api/items/sync`): a source reports its whole current set (services behind their branch,
 *   repos with unpushed work, tonight's chore). The condition is the record: an item not in the next report is
 *   closed by the source, and one a person closed stays closed for as long as the same condition lasts.
 *
 * Every time an item is shown, acted on, or preferred over another is an `item_events` row. That log is what a
 * ranking gets learned from later; [priority] is the fixed rule used until then.
 */
enum class ItemState { OPEN, DONE, DROPPED }

data class ItemDto(
  val token: String, val source: String, val key: String, val kind: String, val title: String, val detail: String?, val url: String?,
  val state: String, val snoozed_until: String?, val due_at: String?,
  /** The source's own hint, if it gave one (same scale as [score]). */
  val priority: Double?,
  /** What the list is ordered by. See [ItemStore.priority]. */
  val score: Double,
  val derived: Boolean, val created_at: String, val updated_at: String, val closed_at: String?, val closed_by: String?,
)
data class ItemEventDto(val at: String, val kind: String, val actor: String, val surface: String?, val other: String?, val detail: String?)
data class ItemDetail(val item: ItemDto, val events: List<ItemEventDto>)
data class ItemReq(val source: String?, val key: String?, val kind: String?, val title: String?, val detail: String?, val url: String?, val due_at: String?, val priority: Double?)
data class ItemSyncReq(val source: String?, val items: List<ItemReq>?)
data class ItemSyncResp(val source: String, val opened: Int, val updated: Int, val reopened: Int, val closed: Int, val open: Int)
data class ItemActionReq(val until: String?, val seconds: Long?, val surface: String?, val note: String?)
data class ItemShownReq(val tokens: List<String>?, val surface: String?)
data class ItemPickReq(val winner: String?, val loser: String?, val surface: String?)
data class ItemList(val items: List<ItemDto>, /** open / snoozed / done / dropped, before `limit`. */ val counts: Map<String, Int>)
data class Ack(val ok: Boolean = true, val n: Int = 1)

@Singleton
class ItemStore @Inject constructor(private val db: Db, private val clock: Clock) {
  companion object {
    /** Where a kind starts when its source gives no hint. Unknown kinds start at 2. */
    val KIND_WEIGHT = mapOf("question" to 3.0, "broken" to 3.0, "property" to 2.5, "deploy" to 2.0, "commit" to 2.0, "chore" to 2.0, "person" to 2.0, "todo" to 2.0, "idea" to 1.0)
    private val KIND_RE = Regex("[a-z][a-z0-9_-]{0,31}")
    private val SOURCE_RE = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    /**
     * The fixed rule: `max(base, hazard)`.
     * base = the source's hint, else the kind's weight, plus up to 1 for age (linear over two weeks), so an old
     * item drifts up past newer ones of its kind without ever jumping a kind above it.
     * hazard = 10 once overdue, else 10·e^(−hours to due / 24): about 3.7 a day out, 7.8 six hours out. A deadline
     * therefore wins over anything undated when it is close, and costs nothing when it is far.
     */
    fun priority(kind: String, hint: Double?, createdMs: Long, dueMs: Long?, nowMs: Long): Double {
      val base = (hint ?: KIND_WEIGHT[kind] ?: 2.0) + min(1.0, max(0.0, (nowMs - createdMs) / 86_400_000.0 / 14.0))
      val hazard = dueMs?.let { if (it <= nowMs) 10.0 else 10.0 * exp(-((it - nowMs) / 3_600_000.0) / 24.0) } ?: 0.0
      return Math.round(max(base, hazard) * 1000) / 1000.0
    }
  }

  private fun dto(rs: ResultSet, now: Long) = ItemDto(
    rs.getString("token"), rs.getString("source"), rs.getString("source_key"), rs.getString("kind"), rs.getString("title"), rs.getString("detail"), rs.getString("url"),
    rs.getString("state"), rs.isoOrNull("snoozed_until_ms"), rs.isoOrNull("due_at_ms"), rs.getDouble("priority").takeUnless { rs.wasNull() },
    priority(rs.getString("kind"), rs.getDouble("priority").takeUnless { rs.wasNull() }, rs.getLong("created_at_ms"), rs.longOrNull("due_at_ms"), now),
    rs.getBoolean("derived"), iso(rs.getLong("created_at_ms")), iso(rs.getLong("updated_at_ms")), rs.isoOrNull("closed_at_ms"), rs.getString("closed_by"),
  )

  private fun event(c: Connection, itemId: Long, kind: String, actor: Actor, surface: String? = null, other: Long? = null, detail: String? = null) {
    c.exec("INSERT INTO item_events (at_ms, item_id, kind, actor, surface, other_item_id, detail) VALUES (?,?,?,?,?,?,?)",
      listOf(clock.millis(), itemId, kind, actor.name.take(96), surface?.take(32), other, detail?.take(500)))
  }

  private class Row(val id: Long, val state: String, val closedBy: String?, val cleared: Boolean, val title: String, val detail: String?, val url: String?, val due: Long?, val priority: Double?, val kind: String)

  private fun find(c: Connection, source: String, key: String): Row? =
    c.select("SELECT * FROM items WHERE source = ? AND source_key = ? FOR UPDATE", listOf(source, key)) { rs ->
      Row(rs.getLong("id"), rs.getString("state"), rs.getString("closed_by"), rs.getBoolean("cleared"), rs.getString("title"), rs.getString("detail"), rs.getString("url"),
        rs.longOrNull("due_at_ms"), rs.getDouble("priority").takeUnless { rs.wasNull() }, rs.getString("kind"))
    }.firstOrNull()

  private fun idOf(c: Connection, token: String): Long =
    c.select("SELECT id FROM items WHERE token = ?", listOf(token)) { it.getLong(1) }.firstOrNull() ?: throw NotFoundException("no item $token")

  private class Clean(val source: String, val key: String, val kind: String, val title: String, val detail: String?, val url: String?, val due: Long?, val priority: Double?)

  private fun clean(r: ItemReq, source: String, i: Int? = null): Clean {
    val at = i?.let { "items[$it]." } ?: ""
    val kind = (r.kind ?: "todo").trim().lowercase()
    if (!KIND_RE.matches(kind)) throw BadRequestException("${at}kind '$kind' must look like a-z0-9_-")
    val title = r.title?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("${at}title is required")
    val key = r.key?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("${at}key is required")
    if (key.length > 255) throw BadRequestException("${at}key is over 255 chars")
    return Clean(source, key, kind, title.take(500), r.detail?.takeIf { it.isNotBlank() }, r.url?.takeIf { it.isNotBlank() }?.take(1000), r.due_at?.let(::parseTime), r.priority)
  }

  private fun sourceOf(actor: Actor, given: String?): String {
    val s = (given ?: actor.service ?: "capture").trim().lowercase()
    if (!SOURCE_RE.matches(s)) throw BadRequestException("source '$s' must look like a-z0-9._-")
    return s
  }

  /** Writes one item; returns "opened" | "updated" | "reopened" | "kept" (a person closed it and the condition has not cleared). */
  private fun put(c: Connection, actor: Actor, x: Clean, derived: Boolean): String {
    val now = clock.millis()
    val row = find(c, x.source, x.key)
    if (row == null) {
      val id = c.insert(
        "INSERT INTO items (created_at_ms, updated_at_ms, token, source, source_key, kind, title, detail, url, state, due_at_ms, priority, derived, last_seen_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        listOf(now, now, newToken("I"), x.source, x.key, x.kind, x.title, x.detail, x.url, ItemState.OPEN.name, x.due, x.priority, derived, now))
      event(c, id, "CREATED", actor)
      return "opened"
    }
    val changed = row.title != x.title || row.detail != x.detail || row.url != x.url || row.due != x.due || row.priority != x.priority || row.kind != x.kind
    // Closed by its source, or closed by a person and then seen to clear: the condition is back, so it is a new occurrence.
    val reopen = row.state != ItemState.OPEN.name && (row.closedBy == "source" || row.cleared || !derived)
    c.exec(
      "UPDATE items SET updated_at_ms = IF(?, ?, updated_at_ms), kind = ?, title = ?, detail = ?, url = ?, due_at_ms = ?, priority = ?, derived = ?, last_seen_ms = ?, cleared = 0" +
        (if (reopen) ", state = 'OPEN', closed_at_ms = NULL, closed_by = NULL, snoozed_until_ms = NULL, created_at_ms = ?" else "") + " WHERE id = ?",
      listOf(changed || reopen, now, x.kind, x.title, x.detail, x.url, x.due, x.priority, derived, now) + (if (reopen) listOf(now) else listOf()) + listOf(row.id))
    if (reopen) event(c, row.id, "REOPENED", actor)
    return when { reopen -> "reopened"; row.state != ItemState.OPEN.name -> "kept"; changed -> "updated"; else -> "same" }
  }

  fun capture(actor: Actor, r: ItemReq): ItemDto = db.tx { c ->
    val source = sourceOf(actor, r.source)
    val x = clean(r.copy(key = r.key ?: newToken("k")), source)
    put(c, actor, x, derived = false)
    c.select("SELECT * FROM items WHERE source = ? AND source_key = ?", listOf(source, x.key)) { dto(it, clock.millis()) }.first()
  }

  /** The source's whole current set. Anything of this source that is open and not listed has resolved itself. */
  fun sync(actor: Actor, req: ItemSyncReq): ItemSyncResp = db.tx { c ->
    val source = sourceOf(actor, req.source)
    val items = (req.items ?: throw BadRequestException("items is required (an empty list closes everything from this source)")).mapIndexed { i, r -> clean(r, source, i) }
    if (items.map { it.key }.toSet().size != items.size) throw BadRequestException("duplicate keys in one sync")
    val results = items.map { put(c, actor, it, derived = true) }
    val keys = items.map { it.key }.toSet()
    val now = clock.millis()
    var closed = 0
    for ((id, key, state) in c.select("SELECT id, source_key, state FROM items WHERE source = ? AND derived = 1 AND (state = 'OPEN' OR cleared = 0) FOR UPDATE", listOf(source)) { Triple(it.getLong(1), it.getString(2), it.getString(3)) }) {
      if (key in keys) continue
      if (state == ItemState.OPEN.name) {
        c.exec("UPDATE items SET state = 'DONE', closed_at_ms = ?, closed_by = 'source', updated_at_ms = ?, cleared = 1 WHERE id = ?", listOf(now, now, id))
        event(c, id, "RESOLVED", actor)
        closed++
      } else {
        c.exec("UPDATE items SET cleared = 1 WHERE id = ?", listOf(id))     // a person closed it; now the condition is gone too
      }
    }
    val open = c.select("SELECT COUNT(*) FROM items WHERE source = ? AND state = 'OPEN'", listOf(source)) { it.getInt(1) }.first()
    ItemSyncResp(source, results.count { it == "opened" }, results.count { it == "updated" }, results.count { it == "reopened" }, closed, open)
  }

  fun list(state: String?, source: String?, kind: String?, limit: Int): ItemList = db.tx { c ->
    val now = clock.millis()
    val counts = linkedMapOf("open" to 0, "snoozed" to 0, "done" to 0, "dropped" to 0)
    c.select("SELECT state, (snoozed_until_ms IS NOT NULL AND snoozed_until_ms > ?) AS z, COUNT(*) FROM items GROUP BY state, z", listOf(now)) {
      val k = when { it.getString(1) != "OPEN" -> it.getString(1).lowercase(); it.getBoolean(2) -> "snoozed"; else -> "open" }
      counts[k] = (counts[k] ?: 0) + it.getInt(3)
    }
    val where = ArrayList<String>()
    val args = ArrayList<Any?>()
    when ((state ?: "open").lowercase()) {
      "open" -> { where += "state = 'OPEN' AND (snoozed_until_ms IS NULL OR snoozed_until_ms <= ?)"; args += now }
      "snoozed" -> { where += "state = 'OPEN' AND snoozed_until_ms > ?"; args += now }
      "done" -> where += "state = 'DONE'"
      "dropped" -> where += "state = 'DROPPED'"
      "all" -> {}
      else -> throw BadRequestException("state is open|snoozed|done|dropped|all")
    }
    if (!source.isNullOrBlank()) { where += "source = ?"; args += source }
    if (!kind.isNullOrBlank()) { where += "kind = ?"; args += kind }
    // The score depends on the clock, so it is computed here and sorted in memory; open loops are counted in hundreds.
    val rows = c.select("SELECT * FROM items ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY id LIMIT 5000", args) { dto(it, now) }
    ItemList(rows.sortedWith(compareByDescending<ItemDto> { it.score }.thenBy { it.created_at }).take(limit), counts)
  }

  fun get(token: String): ItemDetail = db.tx { c ->
    val id = idOf(c, token)
    val item = c.select("SELECT * FROM items WHERE id = ?", listOf(id)) { dto(it, clock.millis()) }.first()
    val events = c.select("SELECT e.*, o.token AS other FROM item_events e LEFT JOIN items o ON o.id = e.other_item_id WHERE e.item_id = ? ORDER BY e.id DESC LIMIT 200", listOf(id)) {
      ItemEventDto(iso(it.getLong("at_ms")), it.getString("kind"), it.getString("actor"), it.getString("surface"), it.getString("other"), it.getString("detail"))
    }
    ItemDetail(item, events)
  }

  fun act(actor: Actor, token: String, verb: String, req: ItemActionReq): ItemDto = db.tx { c ->
    val id = idOf(c, token)
    val now = clock.millis()
    when (verb) {
      "done", "drop" -> {
        c.exec("UPDATE items SET state = ?, closed_at_ms = ?, closed_by = ?, updated_at_ms = ?, snoozed_until_ms = NULL, cleared = 0 WHERE id = ?",
          listOf(if (verb == "done") "DONE" else "DROPPED", now, actor.name.take(96), now, id))
        event(c, id, if (verb == "done") "DONE" else "DROPPED", actor, req.surface, detail = req.note)
      }
      "snooze" -> {
        val until = req.until?.let(::parseTime) ?: req.seconds?.let { now + it * 1000 } ?: throw BadRequestException("snooze needs until or seconds")
        if (until <= now) throw BadRequestException("snooze until a time in the future")
        c.exec("UPDATE items SET snoozed_until_ms = ?, updated_at_ms = ? WHERE id = ?", listOf(until, now, id))
        event(c, id, "SNOOZED", actor, req.surface, detail = iso(until))
      }
      "reopen" -> {
        c.exec("UPDATE items SET state = 'OPEN', closed_at_ms = NULL, closed_by = NULL, snoozed_until_ms = NULL, updated_at_ms = ? WHERE id = ?", listOf(now, id))
        event(c, id, "REOPENED", actor, req.surface, detail = req.note)
      }
      else -> throw BadRequestException("verb is done|drop|snooze|reopen")
    }
    c.select("SELECT * FROM items WHERE id = ?", listOf(id)) { dto(it, now) }.first()
  }

  /** These were put in front of the person just now. Being shown and passed over is a signal; so is where. */
  fun shown(actor: Actor, req: ItemShownReq): Ack = db.tx { c ->
    val tokens = req.tokens ?: throw BadRequestException("tokens is required")
    if (tokens.size > 200) throw BadRequestException("at most 200 tokens per call")
    tokens.forEachIndexed { i, t -> event(c, idOf(c, t), "SHOWN", actor, req.surface, detail = "pos=$i") }
    Ack(n = tokens.size)
  }

  /** Asked which of two matters more, the person chose `winner`. */
  fun pick(actor: Actor, req: ItemPickReq): Ack = db.tx { c ->
    val w = idOf(c, req.winner.required("winner"))
    val l = idOf(c, req.loser.required("loser"))
    if (w == l) throw BadRequestException("winner and loser are the same item")
    event(c, w, "PICKED", actor, req.surface, other = l)
    Ack()
  }
}

class ItemActions @Inject constructor(private val store: ItemStore, private val actors: Actors) : WebAction {
  @Post("/api/items") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun capture(@RequestBody req: ItemReq) = store.capture(actors.current(), req)

  @Post("/api/items/sync") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun sync(@RequestBody req: ItemSyncReq) = store.sync(actors.current(), req)

  @Post("/api/items/shown") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun shown(@RequestBody req: ItemShownReq) = store.shown(actors.current(), req)

  @Post("/api/items/pick") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun pick(@RequestBody req: ItemPickReq) = store.pick(actors.current(), req)

  @Get("/api/items") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun list(@QueryParam state: String?, @QueryParam source: String?, @QueryParam kind: String?, @QueryParam limit: Int?) = store.list(state, source, kind, limitOf(limit))

  @Get("/api/items/{token}") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun get(@PathParam token: String) = store.get(token)

  @Post("/api/items/{token}/{verb}") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun act(@PathParam token: String, @PathParam verb: String, @RequestBody req: ItemActionReq) = store.act(actors.current(), token, verb, req)
}
