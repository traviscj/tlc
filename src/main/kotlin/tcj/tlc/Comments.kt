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

data class ThreadDto(
  val token: String,
  val title: String,
  val body: String?,
  val creator: String,
  /** What the thread is about: `service/entity/id`, an internal token, or null for a free-standing thread. */
  val target: String?,
  /** Live (non-deleted) comment count, maintained in the writing transaction. */
  val cached_comments: Int,
  val last_comment_at: String?,
  val created_at: String,
  val updated_at: String,
  val deleted_at: String?,
  val version: Int,
  val feed_sync_id: Long?,
  val shard: Int,
  /** The revision the target was at when the thread was opened (`…/1234@3`, `AP-…@3`); null if TLC knew of none. */
  val revision: String? = null,
)

data class CommentDto(
  val comment_token: String,
  val thread: String,
  val parent_comment: String?,
  /** Materialized ancestor path (zero-padded ids, self last). ORDER BY this = depth-first, oldest first. */
  val thread_path: String,
  val depth: Int,
  /** Empty once deleted; the row stays so replies keep their place. */
  val content: String,
  val author: String,
  val commented_at: String,
  val edited_at: String?,
  val deleted_at: String?,
  val likes: Int,
  val liked_by_me: Boolean,
  val version: Int,
  val feed_sync_id: Long?,
  val shard: Int,
  /** The revision the thread's target was at when this was written: a comment made at @3 still says so once the target is at @4. */
  val revision: String? = null,
)

data class ThreadView(val thread: ThreadDto, val comments: List<CommentDto>)
data class LikeDto(val token: String, val comment_token: String, val liked_by: String, val liked_at: String, val unliked_at: String?, val version: Int)
data class LikeResult(val changed: Boolean, val liked: Boolean, val likes: Int)

private fun seg(id: Long) = "%010d".format(id)

@Singleton
class CommentStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: TlcConfig,
  private val entities: EntityStore,
  private val tlcers: Tlcers,
  private val logs: LogStore,
) {
  // ----------------------------------------------------------------------------- threads

  private fun thread(c: Connection, rs: ResultSet) = ThreadDto(
    rs.getString("token"), rs.getString("title"), rs.getString("body"), rs.getString("creator"),
    rs.getString("target_key")?.let { entities.refOfKey(c, it) }, rs.getInt("cached_comments"), rs.isoOrNull("last_comment_at_ms"),
    iso(rs.getLong("created_at_ms")), iso(rs.getLong("updated_at_ms")), rs.isoOrNull("deleted_at_ms"), rs.getInt("version"),
    rs.longOrNull("feed_sync_id"), rs.getInt("shard"), rs.getString("target_revision"),
  )

  /** What revision the thing behind a target_key is at right now: an approvable's own, or the latest TLC was told of for a record. */
  private fun revisionOfKey(c: Connection, key: String?): String? = when {
    key == null -> null
    key.startsWith("E:") -> c.select("SELECT revision FROM external_entities WHERE id = ?", listOf(key.substring(2).toLong())) { it.getString(1) }.firstOrNull()
    key.startsWith("AP-") -> c.select("SELECT revision FROM approvables WHERE token = ?", listOf(key)) { it.getString(1) }.firstOrNull()
    else -> null
  }

  private fun threadByToken(c: Connection, token: String): ThreadDto =
    c.select("SELECT * FROM threads WHERE token = ?", listOf(token)) { thread(c, it) }.firstOrNull() ?: throw NotFoundException("no thread '$token'")

  /** [revision]: say which revision of the target this is about; otherwise it is stamped with the current one. */
  fun createThread(actor: Actor, title: String, body: String?, targetRef: String?, revision: String? = null): ThreadDto = db.tx { c ->
    tlcers.touch(c, actor)
    if (title.length > 255) throw BadRequestException("title is at most 255 chars")
    val target = targetRef?.let { entities.resolve(c, it, create = true) }
    val now = clock.millis()
    val token = newToken("T")
    c.insert(
      "INSERT INTO threads (created_at_ms, updated_at_ms, token, title, body, creator, subject_token, external_entity_id, target_key, shard, target_revision) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
      listOf(now, now, token, title, body, actor.name, target?.subjectToken, target?.entityId, target?.key, shardOf(token, config.tlc.feed_shards), revision?.trim()?.removePrefix("@")?.take(64)?.takeIf { it.isNotEmpty() } ?: revisionOfKey(c, target?.key)),
    )
    target?.let { logs.internal(c, it, LogLevel.INFO, "tlc.comments", "thread $token opened by ${actor.name}: $title") }
    threadByToken(c, token)
  }

  fun listThreads(targetRef: String?, creator: String?, includeDeleted: Boolean, limit: Int): List<ThreadDto> = db.tx { c ->
    val where = ArrayList<String>()
    val args = ArrayList<Any?>()
    if (!includeDeleted) where += "deleted_at_ms IS NULL"
    if (targetRef != null) { where += "target_key = ?"; args += (entities.resolveOrNull(c, targetRef)?.key ?: return@tx listOf()) }
    if (creator != null) { where += "creator = ?"; args += creator }
    c.select("SELECT * FROM threads ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY COALESCE(last_comment_at_ms, created_at_ms) DESC, id DESC LIMIT $limit", args) { thread(c, it) }
  }

  fun getThread(token: String, viewer: Actor?): ThreadView = db.tx { c ->
    val t = threadByToken(c, token)
    ThreadView(t, comments(c, "WHERE t.token = ? ORDER BY m.thread_path", listOf(token), viewer))
  }

  private fun mayEdit(actor: Actor, owner: String, what: String) {
    if (actor.name != owner && !actor.admin) forbidden("only $owner (or an admin) may change this $what")
  }

  fun updateThread(actor: Actor, token: String, title: String?, body: String?): ThreadDto = db.tx { c ->
    val t = threadByToken(c, token)
    mayEdit(actor, t.creator, "thread")
    if (t.deleted_at != null) throw ConflictException("thread $token was deleted")
    c.exec("UPDATE threads SET title = COALESCE(?, title), body = COALESCE(?, body), updated_at_ms = ?, version = version + 1, feed_sync_id = NULL WHERE token = ?", listOf(title, body, clock.millis(), token))
    threadByToken(c, token)
  }

  fun destroyThread(actor: Actor, token: String): ThreadDto = db.tx { c ->
    val t = threadByToken(c, token)
    mayEdit(actor, t.creator, "thread")
    if (t.deleted_at == null) c.exec("UPDATE threads SET deleted_at_ms = ?, updated_at_ms = ?, version = version + 1, feed_sync_id = NULL WHERE token = ?", listOf(clock.millis(), clock.millis(), token))
    threadByToken(c, token)
  }

  // ---------------------------------------------------------------------------- comments

  private val commentSelect = """
    SELECT m.*, t.token AS thread_token, p.comment_token AS parent_token
    FROM comments m JOIN threads t ON t.id = m.thread_id LEFT JOIN comments p ON p.id = m.parent_comment_id
  """.trimIndent()

  private fun comments(c: Connection, tail: String, args: List<Any?>, viewer: Actor?): List<CommentDto> {
    val rows = c.select("$commentSelect $tail", args) { rs ->
      rs.getLong("id") to CommentDto(
        rs.getString("comment_token"), rs.getString("thread_token"), rs.getString("parent_token"), rs.getString("thread_path"), rs.getInt("depth"),
        rs.getString("content"), rs.getString("author"), iso(rs.getLong("commented_at_ms")), rs.isoOrNull("edited_at_ms"), rs.isoOrNull("deleted_at_ms"),
        rs.getInt("cached_likes"), false, rs.getInt("version"), rs.longOrNull("feed_sync_id"), rs.getInt("shard"), rs.getString("target_revision"),
      )
    }
    if (viewer == null || rows.isEmpty()) return rows.map { it.second }
    val mine = c.select("SELECT comment_id FROM likes WHERE liked_by = ? AND active = 1 AND comment_id IN (${rows.joinToString(",") { it.first.toString() }})", listOf(viewer.name)) { it.getLong(1) }.toSet()
    return rows.map { (id, dto) -> if (id in mine) dto.copy(liked_by_me = true) else dto }
  }

  private fun commentByToken(c: Connection, token: String, viewer: Actor?): CommentDto =
    comments(c, "WHERE m.comment_token = ?", listOf(token), viewer).firstOrNull() ?: throw NotFoundException("no comment '$token'")

  fun createComment(actor: Actor, threadToken: String?, parentToken: String?, content: String): CommentDto = db.tx { c ->
    tlcers.touch(c, actor)
    if (content.isBlank()) throw BadRequestException("content is required")
    data class Parent(val id: Long, val threadId: Long, val path: String, val depth: Int, val parentId: Long?, val deleted: Boolean)
    var parent = parentToken?.let { pt ->
      c.select("SELECT id, thread_id, thread_path, depth, parent_comment_id, deleted_at_ms FROM comments WHERE comment_token = ?", listOf(pt)) {
        Parent(it.getLong(1), it.getLong(2), it.getString(3), it.getInt(4), it.longOrNull("parent_comment_id"), it.longOrNull("deleted_at_ms") != null)
      }.firstOrNull() ?: throw NotFoundException("no comment '$pt'")
    }
    // The thread row lock serializes writers per thread: cached_comments can't drift.
    val t = (if (threadToken != null) c.select("SELECT id, token, deleted_at_ms, target_key FROM threads WHERE token = ? FOR UPDATE", listOf(threadToken)) { rs -> listOf(rs.getLong(1), rs.getString(2), rs.longOrNull("deleted_at_ms"), rs.getString(4)) }
    else if (parent != null) c.select("SELECT id, token, deleted_at_ms, target_key FROM threads WHERE id = ? FOR UPDATE", listOf(parent.threadId)) { rs -> listOf(rs.getLong(1), rs.getString(2), rs.longOrNull("deleted_at_ms"), rs.getString(4)) }
    else throw BadRequestException("thread or parent_comment is required")).firstOrNull() ?: throw NotFoundException("no thread '$threadToken'")
    val threadId = t[0] as Long
    val tToken = t[1] as String
    if (t[2] != null) throw ConflictException("thread $tToken was deleted")
    if (parent != null && parent.threadId != threadId) throw BadRequestException("parent_comment $parentToken is in a different thread")
    if (parent != null && parent.deleted) throw ConflictException("can't reply to a deleted comment")
    if (parent != null && parent.depth >= config.tlc.max_comment_depth) {
      // Too deep to nest: file as a sibling of the comment being replied to.
      parent = parent.parentId?.let { pid -> c.select("SELECT id, thread_id, thread_path, depth, parent_comment_id FROM comments WHERE id = ?", listOf(pid)) { Parent(it.getLong(1), it.getLong(2), it.getString(3), it.getInt(4), it.longOrNull("parent_comment_id"), false) }.first() }
    }
    val now = clock.millis()
    val token = newToken("C")
    val id = c.insert(
      "INSERT INTO comments (created_at_ms, updated_at_ms, comment_token, thread_id, parent_comment_id, depth, content, author, commented_at_ms, shard, target_revision) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
      listOf(now, now, token, threadId, parent?.id, (parent?.depth ?: -1) + 1, content, actor.name, now, shardOf(tToken, config.tlc.feed_shards), revisionOfKey(c, t[3] as String?)),
    )
    c.exec("UPDATE comments SET thread_path = ? WHERE id = ?", listOf(if (parent == null) seg(id) else "${parent.path}/${seg(id)}", id))
    c.exec("UPDATE threads SET cached_comments = cached_comments + 1, last_comment_at_ms = ?, updated_at_ms = ?, version = version + 1, feed_sync_id = NULL WHERE id = ?", listOf(now, now, threadId))
    commentByToken(c, token, actor)
  }

  fun getComment(token: String, viewer: Actor?): CommentDto = db.tx { c -> commentByToken(c, token, viewer) }

  fun listComments(threadToken: String?, author: String?, viewer: Actor?, limit: Int): List<CommentDto> = db.tx { c ->
    val where = ArrayList<String>()
    val args = ArrayList<Any?>()
    if (threadToken != null) { where += "t.token = ?"; args += threadToken }
    if (author != null) { where += "m.author = ?"; args += author }
    if (where.isEmpty()) throw BadRequestException("thread or author is required")
    comments(c, "WHERE ${where.joinToString(" AND ")} ORDER BY ${if (threadToken != null) "m.thread_path" else "m.id DESC"} LIMIT $limit", args, viewer)
  }

  fun updateComment(actor: Actor, token: String, content: String): CommentDto = db.tx { c ->
    if (content.isBlank()) throw BadRequestException("content is required")
    val m = commentByToken(c, token, null)
    mayEdit(actor, m.author, "comment")
    if (m.deleted_at != null) throw ConflictException("comment $token was deleted")
    if (m.content != content) c.exec("UPDATE comments SET content = ?, edited_at_ms = ?, updated_at_ms = ?, version = version + 1, feed_sync_id = NULL WHERE comment_token = ?", listOf(content, clock.millis(), clock.millis(), token))
    commentByToken(c, token, actor)
  }

  /** Tombstones: the content is erased for real, the row stays so replies keep their parent. */
  fun destroyComment(actor: Actor, token: String): CommentDto = db.tx { c ->
    val m = commentByToken(c, token, null)
    mayEdit(actor, m.author, "comment")
    if (m.deleted_at == null) {
      val now = clock.millis()
      c.select("SELECT id FROM threads WHERE token = ? FOR UPDATE", listOf(m.thread)) { it.getLong(1) }
      val n = c.exec("UPDATE comments SET content = '', deleted_at_ms = ?, updated_at_ms = ?, version = version + 1, feed_sync_id = NULL WHERE comment_token = ? AND deleted_at_ms IS NULL", listOf(now, now, token))
      if (n == 1) c.exec("UPDATE threads SET cached_comments = cached_comments - 1, updated_at_ms = ?, version = version + 1, feed_sync_id = NULL WHERE token = ?", listOf(now, m.thread))
    }
    commentByToken(c, token, actor)
  }

  // ------------------------------------------------------------------------------- likes

  /**
   * One row per like *episode*: unliking stamps unliked_at, re-liking inserts a fresh row. The
   * generated `active` column + unique key allow at most one open episode per (comment, liker).
   */
  fun like(actor: Actor, token: String, on: Boolean): LikeResult = db.tx { c ->
    tlcers.touch(c, actor)
    // Comment row lock: serializes likes per comment so cached_likes stays exact.
    val (id, deleted) = c.select("SELECT id, deleted_at_ms FROM comments WHERE comment_token = ? FOR UPDATE", listOf(token)) { it.getLong(1) to (it.longOrNull("deleted_at_ms") != null) }.firstOrNull() ?: throw NotFoundException("no comment '$token'")
    val open = c.select("SELECT id FROM likes WHERE comment_id = ? AND liked_by = ? AND active = 1", listOf(id, actor.name)) { it.getLong(1) }.firstOrNull()
    val now = clock.millis()
    val changed = when {
      on && open == null -> {
        if (deleted) throw ConflictException("comment $token was deleted")
        c.insert("INSERT INTO likes (created_at_ms, updated_at_ms, token, comment_id, liked_by, liked_at_ms) VALUES (?,?,?,?,?,?)", listOf(now, now, newToken("L"), id, actor.name, now)); true
      }
      !on && open != null -> { c.exec("UPDATE likes SET unliked_at_ms = ?, updated_at_ms = ?, version = version + 1 WHERE id = ?", listOf(now, now, open)); true }
      else -> false
    }
    if (changed) c.exec("UPDATE comments SET cached_likes = cached_likes + ?, updated_at_ms = ?, version = version + 1, feed_sync_id = NULL WHERE id = ?", listOf(if (on) 1 else -1, now, id))
    LikeResult(changed, on, c.select("SELECT cached_likes FROM comments WHERE id = ?", listOf(id)) { it.getInt(1) }.first())
  }

  fun likes(token: String, includeUnliked: Boolean): List<LikeDto> = db.tx { c ->
    commentByToken(c, token, null)
    c.select(
      "SELECT l.* FROM likes l JOIN comments m ON m.id = l.comment_id WHERE m.comment_token = ? ${if (includeUnliked) "" else "AND l.active = 1"} ORDER BY l.id",
      listOf(token),
    ) { rs -> LikeDto(rs.getString("token"), token, rs.getString("liked_by"), iso(rs.getLong("liked_at_ms")), rs.isoOrNull("unliked_at_ms"), rs.getInt("version")) }
  }

  fun threadFeed(after: Long, limit: Int, shardSql: String): List<ThreadDto> = db.tx { c ->
    c.select("SELECT g.* FROM threads g WHERE g.feed_sync_id > ? $shardSql ORDER BY g.feed_sync_id LIMIT $limit", listOf(after)) { thread(c, it) }
  }

  fun commentFeed(after: Long, limit: Int, shardSql: String): List<CommentDto> = db.tx { c ->
    comments(c, "WHERE m.feed_sync_id > ? ${shardSql.replace("g.", "m.")} ORDER BY m.feed_sync_id LIMIT $limit", listOf(after), null)
  }
}

// ------------------------------------------------------------------------------------ actions

data class ThreadReq(val title: String?, val body: String?, val target: String?, val revision: String? = null)
data class ThreadList(val threads: List<ThreadDto>)
data class CommentReq(val thread: String?, val parent_comment: String?, val content: String?)
data class CommentList(val comments: List<CommentDto>)
data class LikeList(val likes: List<LikeDto>)

/** REST spelling of the nouns x verbs grid. The `tlc` CLI maps create/read/update/destroy and mk/ls/edit/rm onto these. */
class ThreadActions @Inject constructor(private val store: CommentStore, private val actors: Actors) : WebAction {
  @Post("/api/threads") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun create(@RequestBody req: ThreadReq) = store.createThread(actors.current(), req.title.required("title"), req.body, req.target, req.revision)

  @Get("/api/threads") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun list(@QueryParam target: String?, @QueryParam creator: String?, @QueryParam include_deleted: Boolean?, @QueryParam limit: Int?) =
    ThreadList(store.listThreads(target, creator, include_deleted == true, limitOf(limit)))

  /** The thread plus every comment, depth-first. */
  @Get("/api/threads/{token}") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun get(@PathParam token: String) = store.getThread(token, actors.current())

  @Patch("/api/threads/{token}") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun update(@PathParam token: String, @RequestBody req: ThreadReq) = store.updateThread(actors.current(), token, req.title, req.body)

  @Delete("/api/threads/{token}") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun destroy(@PathParam token: String) = store.destroyThread(actors.current(), token)

  @Post("/api/threads/{token}/comments") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun comment(@PathParam token: String, @RequestBody req: CommentReq) = store.createComment(actors.current(), token, req.parent_comment, req.content.required("content"))
}

class CommentActions @Inject constructor(private val store: CommentStore, private val actors: Actors) : WebAction {
  @Post("/api/comments") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun create(@RequestBody req: CommentReq) = store.createComment(actors.current(), req.thread, req.parent_comment, req.content.required("content"))

  @Get("/api/comments") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun list(@QueryParam thread: String?, @QueryParam author: String?, @QueryParam limit: Int?) = CommentList(store.listComments(thread, author, actors.current(), limitOf(limit)))

  @Get("/api/comments/{token}") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun get(@PathParam token: String) = store.getComment(token, actors.current())

  @Patch("/api/comments/{token}") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun update(@PathParam token: String, @RequestBody req: CommentReq) = store.updateComment(actors.current(), token, req.content.required("content"))

  @Delete("/api/comments/{token}") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun destroy(@PathParam token: String) = store.destroyComment(actors.current(), token)

  @Post("/api/comments/{token}/like") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun like(@PathParam token: String) = store.like(actors.current(), token, true)

  @Post("/api/comments/{token}/unlike") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun unlike(@PathParam token: String) = store.like(actors.current(), token, false)

  @Get("/api/comments/{token}/likes") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun likes(@PathParam token: String, @QueryParam include_unliked: Boolean?) = LikeList(store.likes(token, include_unliked == true))
}
