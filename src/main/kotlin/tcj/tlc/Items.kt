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
import misk.exceptions.WebActionException
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
 *
 * An item can be **delegated** (`…/delegate`): handed to an agent to do. That only records the request
 * (`assigned_to`, `run_state = QUEUED`); a worker elsewhere claims it (`…/run` RUNNING, which succeeds once),
 * does the work and reports DONE, NEEDS_YOU or FAILED with a result. The item stays open either way: the person
 * who asked reads the result and closes it. An item with a result waiting to be read ranks [RESULT_BOOST] higher.
 *
 * **Codes.** Every item has a short code (`8a5`, written `OL#8a5`) that is easy to say and type: three or more
 * characters from an alphabet without 0 1 i l o. It is a fixed scramble of the row id ([ItemCode]), so it needs no
 * column and never changes; everything that takes a token takes a code.
 *
 * **Notes.** An item has one comment thread (a TLC thread whose target is the item): context from the person, the
 * question an agent came back with, the answer. `…/note` appends to it, and can queue the agent again in the same
 * call, which is how a question gets answered. A worker's results are appended too, so the thread reads in order.
 *
 * **Tags.** A few short slugs per item (`apt`, `home`, `fix`, …) to group and filter by. A new item with none gets
 * one from its kind or source ([ItemStore.defaultTags]); `home` and `fix` nudge the order ([ItemStore.TAG_WEIGHT]).
 *
 * `items` is a feed (see Feeds.kt): every change a reader can see NULLs `feed_sync_id` and the item is published
 * again in its new state. A sync that changes nothing publishes nothing, and neither does time passing (a snooze
 * running out, a deadline nearing): the feed carries `snoozed_until` and `due_at` for consumers to apply themselves.
 */
enum class ItemState { OPEN, DONE, DROPPED }

/** The short code of an item: a bijection between row ids and strings over an alphabet with no look-alikes. */
object ItemCode {
  const val ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"       // no 0 1 i l o
  private const val MIN_LEN = 3
  private val BASE = ALPHABET.length.toLong()
  private const val MULT = 7919L                                // coprime with 31, so x -> x*MULT+ADD is a permutation mod 31^n
  private const val ADD = 12345L
  private val RE = Regex("(?i)(?:ol#)?([${ALPHABET}]{3,8})")

  private fun pow(n: Int): Long { var p = 1L; repeat(n) { p *= BASE }; return p }

  /** Ids 1..31^3 get three characters, the next 31^4 get four, and so on. */
  fun of(id: Long): String {
    var x = id - 1
    var len = MIN_LEN
    while (x >= pow(len)) { x -= pow(len); len++ }
    var v = (x * MULT + ADD) % pow(len)
    val out = CharArray(len)
    for (k in len - 1 downTo 0) { out[k] = ALPHABET[(v % BASE).toInt()]; v /= BASE }
    return String(out)
  }

  /** The id a code stands for, or null if [s] is not a code (`8a5`, `OL#8a5`, any case). */
  fun idOf(s: String): Long? {
    val code = RE.matchEntire(s.trim())?.groupValues?.get(1)?.lowercase() ?: return null
    val len = code.length
    var v = 0L
    for (ch in code) v = v * BASE + ALPHABET.indexOf(ch)
    val m = pow(len)
    val inv = java.math.BigInteger.valueOf(MULT).modInverse(java.math.BigInteger.valueOf(m)).toLong()
    val x = Math.floorMod((v - ADD) % m * inv, m)
    var offset = 0L
    for (n in MIN_LEN until len) offset += pow(n)
    return x + offset + 1
  }
}

data class ItemDto(
  val token: String, val source: String, val key: String, val kind: String, val title: String, val detail: String?, val url: String?,
  val state: String, val snoozed_until: String?, val due_at: String?,
  /** The source's own hint, if it gave one (same scale as [score]). */
  val priority: Double?,
  /** What the list is ordered by. See [ItemStore.priority]. */
  val score: Double,
  val derived: Boolean, val created_at: String, val updated_at: String, val closed_at: String?, val closed_by: String?,
  /** Who it was handed to, where that stands (QUEUED, RUNNING, DONE, NEEDS_YOU, FAILED), since when, and what came back. */
  val assigned_to: String? = null, val run_state: String? = null, val run_at: String? = null, val run_result: String? = null,
  /** Position in the `items` feed; null between a change and its publication (about half a second). */
  val feed_sync_id: Long? = null, val shard: Int = 0,
  /** Short code, written `OL#<code>`; accepted wherever a token is. */
  val code: String = "",
  val tags: List<String> = listOf(),
  /** Comments on the item's thread, and the thread itself (null until the first note). */
  val notes: Int = 0, val thread: String? = null,
  /** The agent session of the last run, when the worker reported one: what to resume to continue or look inside it. */
  val run_session: String? = null,
)
data class ItemNoteDto(val at: String, val author: String, val text: String)
data class ItemEventDto(val at: String, val kind: String, val actor: String, val surface: String?, val other: String?, val detail: String?)
data class ItemDetail(val item: ItemDto, val events: List<ItemEventDto>, val notes: List<ItemNoteDto> = listOf())
data class ItemReq(val source: String?, val key: String?, val kind: String?, val title: String?, val detail: String?, val url: String?, val due_at: String?, val priority: Double?, val tags: List<String>? = null)
data class ItemSyncReq(val source: String?, val items: List<ItemReq>?)
data class ItemSyncResp(val source: String, val opened: Int, val updated: Int, val reopened: Int, val closed: Int, val open: Int)
data class ItemActionReq(val until: String?, val seconds: Long?, val surface: String?, val note: String?, val to: String? = null,
  /** `tag`: slugs to add and to remove. */ val tags: List<String>? = null, val untag: List<String>? = null)
/** A note on the item's thread. [delegate] queues the agent again afterwards (answering its question); null leaves the run alone. */
data class ItemNoteReq(val text: String?, val surface: String?, val delegate: Boolean? = null, val to: String? = null)
data class ItemRunReq(val state: String?, val result: String?, val session: String? = null)
data class ItemShownReq(val tokens: List<String>?, val surface: String?)
data class ItemPickReq(val winner: String?, val loser: String?, val surface: String?)
data class ItemList(val items: List<ItemDto>, /** open / snoozed / done / dropped, before `limit`. */ val counts: Map<String, Int>,
  /** Open, unsnoozed items per tag (`-` = untagged), before any `tag` filter. */ val tags: Map<String, Int> = mapOf())
data class Ack(val ok: Boolean = true, val n: Int = 1)

@Singleton
class ItemStore @Inject constructor(private val db: Db, private val clock: Clock, private val config: TlcConfig, private val comments: CommentStore) {
  companion object {
    /** Where a kind starts when its source gives no hint. Unknown kinds start at 2. */
    val KIND_WEIGHT = mapOf("question" to 3.0, "broken" to 3.0, "property" to 2.5, "deploy" to 2.0, "commit" to 2.0, "chore" to 2.0, "person" to 2.0, "todo" to 2.0, "idea" to 1.0)
    val RUN_STATES = setOf("RUNNING", "DONE", "NEEDS_YOU", "FAILED")
    /** Added to the score while a delegated run's result is waiting to be read. */
    const val RESULT_BOOST = 1.5
    private val KIND_RE = Regex("[a-z][a-z0-9_-]{0,31}")
    private val SOURCE_RE = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val TAG_RE = Regex("[a-z][a-z0-9-]{0,23}")
    /** Added to the base score: a home chore sits above a fix of the same kind and age. */
    val TAG_WEIGHT = mapOf("home" to 0.25, "fix" to -0.25)

    /** The tag a new item gets when its writer gave none. Anything else starts untagged. */
    fun defaultTags(source: String, kind: String): List<String> = when {
      kind == "property" || source.startsWith("inforad.prop") -> listOf("apt")
      kind == "chore" -> listOf("home")
      kind == "person" -> listOf("family")
      kind == "idea" -> listOf("idea")
      kind in setOf("deploy", "commit", "broken") -> listOf("fix")
      else -> listOf()
    }

    fun cleanTags(tags: List<String>, at: String = ""): List<String> = tags.map { it.trim().lowercase().removePrefix("#") }.filter { it.isNotEmpty() }.distinct().sorted().also { ts ->
      ts.firstOrNull { !TAG_RE.matches(it) }?.let { throw BadRequestException("${at}tag '$it' must look like a-z0-9-") }
      if (ts.joinToString(" ").length > 255) throw BadRequestException("${at}too many tags")
    }

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

  private fun tagsOf(s: String?): List<String> = s?.split(' ')?.filter { it.isNotEmpty() } ?: listOf()

  private fun dto(rs: ResultSet, now: Long) = ItemDto(
    rs.getString("token"), rs.getString("source"), rs.getString("source_key"), rs.getString("kind"), rs.getString("title"), rs.getString("detail"), rs.getString("url"),
    rs.getString("state"), rs.isoOrNull("snoozed_until_ms"), rs.isoOrNull("due_at_ms"), rs.getDouble("priority").takeUnless { rs.wasNull() },
    priority(rs.getString("kind"), rs.getDouble("priority").takeUnless { rs.wasNull() }, rs.getLong("created_at_ms"), rs.longOrNull("due_at_ms"), now) +
      (if (rs.getString("run_state") in setOf("DONE", "NEEDS_YOU", "FAILED")) RESULT_BOOST else 0.0) +
      tagsOf(rs.getString("tags")).sumOf { TAG_WEIGHT[it] ?: 0.0 },
    rs.getBoolean("derived"), iso(rs.getLong("created_at_ms")), iso(rs.getLong("updated_at_ms")), rs.isoOrNull("closed_at_ms"), rs.getString("closed_by"),
    rs.getString("assigned_to"), rs.getString("run_state"), rs.isoOrNull("run_at_ms"), rs.getString("run_result"),
    rs.longOrNull("feed_sync_id"), rs.getInt("shard"),
    ItemCode.of(rs.getLong("id")), tagsOf(rs.getString("tags")), rs.getInt("notes"), rs.getString("thread_token"), rs.getString("run_session"),
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

  /** By token (`I-…`) or by short code (`8a5`, `OL#8a5`). */
  private fun idOf(c: Connection, token: String): Long =
    (if (token.startsWith("I-")) c.select("SELECT id FROM items WHERE token = ?", listOf(token)) { it.getLong(1) }.firstOrNull()
    else ItemCode.idOf(token)?.let { id -> c.select("SELECT id FROM items WHERE id = ?", listOf(id)) { it.getLong(1) }.firstOrNull() })
      ?: throw NotFoundException("no item $token")

  private class Clean(val source: String, val key: String, val kind: String, val title: String, val detail: String?, val url: String?, val due: Long?, val priority: Double?, val tags: List<String>?)

  private fun clean(r: ItemReq, source: String, i: Int? = null): Clean {
    val at = i?.let { "items[$it]." } ?: ""
    val kind = (r.kind ?: "todo").trim().lowercase()
    if (!KIND_RE.matches(kind)) throw BadRequestException("${at}kind '$kind' must look like a-z0-9_-")
    val title = r.title?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("${at}title is required")
    val key = r.key?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("${at}key is required")
    if (key.length > 255) throw BadRequestException("${at}key is over 255 chars")
    return Clean(source, key, kind, title.take(500), r.detail?.takeIf { it.isNotBlank() }, r.url?.takeIf { it.isNotBlank() }?.take(1000), r.due_at?.let(::parseTime), r.priority, r.tags?.let { cleanTags(it, at) })
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
      val token = newToken("I")
      val id = c.insert(
        "INSERT INTO items (created_at_ms, updated_at_ms, token, source, source_key, kind, title, detail, url, state, due_at_ms, priority, derived, last_seen_ms, shard, tags) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        listOf(now, now, token, x.source, x.key, x.kind, x.title, x.detail, x.url, ItemState.OPEN.name, x.due, x.priority, derived, now, shardOf(token, config.tlc.feed_shards),
          (x.tags ?: defaultTags(x.source, x.kind)).joinToString(" ").ifEmpty { null }))
      event(c, id, "CREATED", actor)
      return "opened"
    }
    val changed = row.title != x.title || row.detail != x.detail || row.url != x.url || row.due != x.due || row.priority != x.priority || row.kind != x.kind
    // Closed by its source, or closed by a person and then seen to clear: the condition is back, so it is a new occurrence.
    val reopen = row.state != ItemState.OPEN.name && (row.closedBy == "source" || row.cleared || !derived)
    c.exec(
      "UPDATE items SET updated_at_ms = IF(?, ?, updated_at_ms), feed_sync_id = IF(?, NULL, feed_sync_id), kind = ?, title = ?, detail = ?, url = ?, due_at_ms = ?, priority = ?, derived = ?, last_seen_ms = ?, cleared = 0" +
        (if (reopen) ", state = 'OPEN', closed_at_ms = NULL, closed_by = NULL, snoozed_until_ms = NULL, created_at_ms = ?" else "") + " WHERE id = ?",
      listOf(changed || reopen, now, changed || reopen, x.kind, x.title, x.detail, x.url, x.due, x.priority, derived, now) + (if (reopen) listOf(now) else listOf()) + listOf(row.id))
    if (!derived && x.tags != null) c.exec("UPDATE items SET tags = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ? AND NOT (tags <=> ?)",
      listOf(x.tags.joinToString(" ").ifEmpty { null }, now, row.id, x.tags.joinToString(" ").ifEmpty { null }))
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
        c.exec("UPDATE items SET state = 'DONE', closed_at_ms = ?, closed_by = 'source', updated_at_ms = ?, feed_sync_id = NULL, cleared = 1 WHERE id = ?", listOf(now, now, id))
        event(c, id, "RESOLVED", actor)
        closed++
      } else {
        c.exec("UPDATE items SET cleared = 1 WHERE id = ?", listOf(id))     // a person closed it; now the condition is gone too
      }
    }
    val open = c.select("SELECT COUNT(*) FROM items WHERE source = ? AND state = 'OPEN'", listOf(source)) { it.getInt(1) }.first()
    ItemSyncResp(source, results.count { it == "opened" }, results.count { it == "updated" }, results.count { it == "reopened" }, closed, open)
  }

  fun list(state: String?, source: String?, kind: String?, limit: Int, run: String? = null, assigned: String? = null, tag: String? = null): ItemList = db.tx { c ->
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
    if (!run.isNullOrBlank()) { where += "run_state = ?"; args += run.uppercase() }
    if (!assigned.isNullOrBlank()) { where += "assigned_to = ?"; args += assigned }
    if (tag == "-") where += "tags IS NULL"
    else if (!tag.isNullOrBlank()) { where += "CONCAT(' ', tags, ' ') LIKE ?"; args += "% ${tag.trim().lowercase()} %" }
    val byTag = sortedMapOf<String, Int>()
    c.select("SELECT tags, COUNT(*) FROM items WHERE state = 'OPEN' AND (snoozed_until_ms IS NULL OR snoozed_until_ms <= ?) GROUP BY tags", listOf(now)) {
      for (t in tagsOf(it.getString(1)).ifEmpty { listOf("-") }) byTag[t] = (byTag[t] ?: 0) + it.getInt(2)
    }
    // The score depends on the clock, so it is computed here and sorted in memory; open loops are counted in hundreds.
    val rows = c.select("SELECT * FROM items ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY id LIMIT 5000", args) { dto(it, now) }
    ItemList(rows.sortedWith(compareByDescending<ItemDto> { it.score }.thenBy { it.created_at }).take(limit), counts, byTag)
  }

  fun get(token: String): ItemDetail = db.tx { c ->
    val id = idOf(c, token)
    val item = c.select("SELECT * FROM items WHERE id = ?", listOf(id)) { dto(it, clock.millis()) }.first()
    val events = c.select("SELECT e.*, o.token AS other FROM item_events e LEFT JOIN items o ON o.id = e.other_item_id WHERE e.item_id = ? ORDER BY e.id DESC LIMIT 200", listOf(id)) {
      ItemEventDto(iso(it.getLong("at_ms")), it.getString("kind"), it.getString("actor"), it.getString("surface"), it.getString("other"), it.getString("detail"))
    }
    item to events
  }.let { (item, events) ->
    val notes = item.thread?.let { t -> comments.getThread(t, null).comments.filter { it.deleted_at == null }.map { ItemNoteDto(it.commented_at, it.author, it.content) } } ?: listOf()
    ItemDetail(item, events, notes)
  }

  /** Appends [text] to the item's thread, opening the thread on the first note. Returns the thread token. */
  private fun append(actor: Actor, token: String, text: String): String {
    val (id, itemToken, title, thread) = db.tx { c ->
      val id = idOf(c, token)
      c.select("SELECT token, title, thread_token FROM items WHERE id = ?", listOf(id)) { listOf(id, it.getString(1), it.getString(2), it.getString(3)) }.first()
    }
    val t = (thread as String?) ?: comments.createThread(actor, "OL#${ItemCode.of(id as Long)} ${title as String}".take(255), null, itemToken as String).token
    comments.createComment(actor, t, null, text.take(16000))
    db.tx { c -> c.exec("UPDATE items SET thread_token = ?, notes = notes + 1, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf(t, clock.millis(), id)) }
    return t
  }

  /** Context from a person, or the answer to what an agent asked. With `delegate` the agent is queued again to act on it. */
  fun note(actor: Actor, token: String, req: ItemNoteReq): ItemDto {
    val text = req.text?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("text is required")
    append(actor, token, text)
    db.tx { c -> event(c, idOf(c, token), "NOTED", actor, req.surface, detail = text) }
    if (req.delegate == true) {
      // answering a question that is still queued or running is fine: the note is in the thread the worker reads
      try { act(actor, token, "delegate", ItemActionReq(null, null, req.surface, "answered", req.to)) } catch (e: WebActionException) { if (e.code != 409) throw e }
    }
    return get(token).item
  }

  fun act(actor: Actor, token: String, verb: String, req: ItemActionReq): ItemDto = db.tx { c ->
    val id = idOf(c, token)
    val now = clock.millis()
    when (verb) {
      "done", "drop" -> {
        c.exec("UPDATE items SET state = ?, closed_at_ms = ?, closed_by = ?, updated_at_ms = ?, feed_sync_id = NULL, snoozed_until_ms = NULL, cleared = 0 WHERE id = ?",
          listOf(if (verb == "done") "DONE" else "DROPPED", now, actor.name.take(96), now, id))
        event(c, id, if (verb == "done") "DONE" else "DROPPED", actor, req.surface, detail = req.note)
      }
      "snooze" -> {
        val until = req.until?.let(::parseTime) ?: req.seconds?.let { now + it * 1000 } ?: throw BadRequestException("snooze needs until or seconds")
        if (until <= now) throw BadRequestException("snooze until a time in the future")
        c.exec("UPDATE items SET snoozed_until_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf(until, now, id))
        event(c, id, "SNOOZED", actor, req.surface, detail = iso(until))
      }
      "reopen" -> {
        c.exec("UPDATE items SET state = 'OPEN', closed_at_ms = NULL, closed_by = NULL, snoozed_until_ms = NULL, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf(now, id))
        event(c, id, "REOPENED", actor, req.surface, detail = req.note)
      }
      "delegate" -> {
        val to = (req.to ?: "claude").trim().lowercase()
        if (!SOURCE_RE.matches(to)) throw BadRequestException("to '$to' must look like a-z0-9._-")
        // Asking again while a run is in flight would start a second one; after a result it is a fresh request.
        val n = c.exec("UPDATE items SET assigned_to = ?, run_state = 'QUEUED', run_at_ms = ?, run_result = NULL, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ? AND state = 'OPEN' AND (run_state IS NULL OR run_state NOT IN ('QUEUED','RUNNING'))",
          listOf(to, now, now, id))
        if (n == 0) throw WebActionException(409, "already queued or running, or not open", "already queued or running, or not open")
        event(c, id, "DELEGATED", actor, req.surface, detail = listOfNotNull(to, req.note).joinToString(": "))
      }
      "recall" -> {
        val n = c.exec("UPDATE items SET run_state = NULL, assigned_to = NULL, run_at_ms = NULL, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ? AND run_state = 'QUEUED'", listOf(now, id))
        if (n == 0) throw WebActionException(409, "nothing queued to recall", "nothing queued to recall")
        event(c, id, "RECALLED", actor, req.surface)
      }
      "tag" -> {
        val cur = c.select("SELECT tags FROM items WHERE id = ? FOR UPDATE", listOf(id)) { tagsOf(it.getString(1)) }.first()
        val next = cleanTags((cur - cleanTags(req.untag ?: listOf()).toSet()) + cleanTags(req.tags ?: listOf()))
        if (next != cur) {
          c.exec("UPDATE items SET tags = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf(next.joinToString(" ").ifEmpty { null }, now, id))
          event(c, id, "TAGGED", actor, req.surface, detail = next.joinToString(" "))
        }
      }
      else -> throw BadRequestException("verb is done|drop|snooze|reopen|delegate|recall|tag")
    }
    c.select("SELECT * FROM items WHERE id = ?", listOf(id)) { dto(it, now) }.first()
  }

  /** The `items` feed: each item's latest state, in the order items last changed. `score` is as of the read. */
  fun feed(after: Long, limit: Int, shardSql: String): List<ItemDto> = db.tx { c ->
    c.select("SELECT g.* FROM items g WHERE g.feed_sync_id > ? $shardSql ORDER BY g.feed_sync_id LIMIT $limit", listOf(after)) { dto(it, clock.millis()) }
  }

  /** The worker's side of a delegation. RUNNING is a claim: it succeeds for exactly one caller. */
  fun run(actor: Actor, token: String, req: ItemRunReq): ItemDto = db.tx { c ->
    val id = idOf(c, token)
    val now = clock.millis()
    val state = req.state.required("state").uppercase()
    if (state !in RUN_STATES) throw BadRequestException("state is ${RUN_STATES.joinToString("|")}")
    val session = req.session?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
    // A worker whose session outlives the claim (a background agent) names it up front; one that only knows it at the end sends it then.
    val n = if (state == "RUNNING") c.exec("UPDATE items SET run_state = 'RUNNING', run_at_ms = ?, run_session = COALESCE(?, run_session), updated_at_ms = ?, feed_sync_id = NULL WHERE id = ? AND run_state = 'QUEUED'", listOf(now, session, now, id))
    else c.exec("UPDATE items SET run_state = ?, run_at_ms = ?, run_result = ?, run_session = COALESCE(?, run_session), updated_at_ms = ?, feed_sync_id = NULL WHERE id = ? AND run_state = 'RUNNING'",
      listOf(state, now, req.result?.take(16000), session, now, id))
    if (n == 0 && state == "RUNNING" && session != null) {
      // the claimant, a moment later, saying which session it started: not a second claim
      if (c.exec("UPDATE items SET run_session = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ? AND run_state = 'RUNNING'", listOf(session, now, id)) == 1)
        return@tx c.select("SELECT * FROM items WHERE id = ?", listOf(id)) { dto(it, now) }.first()
    }
    if (n == 0) throw WebActionException(409, "item is not ${if (state == "RUNNING") "QUEUED" else "RUNNING"}", "item is not ${if (state == "RUNNING") "QUEUED" else "RUNNING"}")
    event(c, id, "RUN", actor, detail = state)
    c.select("SELECT * FROM items WHERE id = ?", listOf(id)) { dto(it, now) }.first()
  }.let { item ->
    // the result joins the thread, so question, answer and next result read in order
    if (item.run_state != "RUNNING" && !req.result.isNullOrBlank()) { append(actor, item.token, "[${item.run_state}] ${req.result}"); get(item.token).item } else item
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
  fun list(@QueryParam state: String?, @QueryParam source: String?, @QueryParam kind: String?, @QueryParam limit: Int?, @QueryParam run: String?, @QueryParam assigned: String?, @QueryParam tag: String?) =
    store.list(state, source, kind, limitOf(limit), run, assigned, tag)

  @Post("/api/items/{token}/run") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun run(@PathParam token: String, @RequestBody req: ItemRunReq) = store.run(actors.current(), token, req)

  @Post("/api/items/{token}/note") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun note(@PathParam token: String, @RequestBody req: ItemNoteReq) = store.note(actors.current(), token, req)

  @Get("/api/items/{token}") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun get(@PathParam token: String) = store.get(token)

  @Post("/api/items/{token}/{verb}") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun act(@PathParam token: String, @PathParam verb: String, @RequestBody req: ItemActionReq) = store.act(actors.current(), token, verb, req)
}
