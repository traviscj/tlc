package tcj.tlc

import com.google.common.util.concurrent.AbstractIdleService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import misk.exceptions.BadRequestException
import misk.exceptions.NotFoundException
import misk.logging.getLogger
import misk.security.authz.Authenticated
import misk.web.Get
import misk.web.PathParam
import misk.web.Post
import misk.web.QueryParam
import misk.web.ResponseContentType
import misk.web.actions.WebAction

/** feed name -> backing table. Every feed table carries (id, feed_sync_id, shard). */
val FEEDS = linkedMapOf("taggings" to "taggings", "threads" to "threads", "comments" to "comments", "approvables" to "approvables", "ownerships" to "ownerships")

fun feedTable(feed: String): String = FEEDS[feed] ?: throw NotFoundException("no feed '$feed' (have: ${FEEDS.keys.joinToString()})")

/**
 * The tables are the feed. Every mutation NULLs a row's feed_sync_id; this assigns fresh,
 * monotonically increasing ids from `sequences`, one transaction per batch. A republished row gets
 * a new, higher id, so a consumer paging `feed_sync_id > cursor` sees every row's latest state at
 * least once (intermediate states may be skipped: it is a state feed, not an event log).
 *
 * Ids are allocated and committed under the sequence row lock, so they become visible in order: a
 * consumer never sees N+1 and later has N appear behind its cursor. That holds for any number of
 * publishers on one database.
 */
@Singleton
class FeedPublisher @Inject constructor(private val db: Db) {
  /** Drains feeds; returns rows published. A paused feed is skipped unless [force]. */
  @Synchronized
  fun publishAll(only: String? = null, force: Boolean = false): Int = FEEDS.keys.filter { only == null || it == only }.sumOf { feed ->
    var total = 0
    do {
      val n = publishBatch(feed, force)
      total += n
    } while (n == BATCH)
    total
  }

  /** Persisted per feed, so a pause survives restarts. */
  fun setEnabled(feed: String, enabled: Boolean) {
    feedTable(feed)
    db.tx { c ->
      c.exec("INSERT IGNORE INTO sequences (name, value) VALUES (?, 0)", listOf(feed))
      c.exec("UPDATE sequences SET publishing_enabled = ? WHERE name = ?", listOf(enabled, feed))
    }
  }

  private fun publishBatch(feed: String, force: Boolean): Int = db.tx { c ->
    val table = feedTable(feed)
    // Cheap unlocked probe first so an idle feed costs one index lookup per tick.
    if (c.select("SELECT id FROM $table WHERE feed_sync_id IS NULL LIMIT 1") { 1 }.isEmpty()) return@tx 0
    c.exec("INSERT IGNORE INTO sequences (name, value) VALUES (?, 0)", listOf(feed))
    val (current, enabled) = c.select("SELECT value, publishing_enabled FROM sequences WHERE name = ? FOR UPDATE", listOf(feed)) { it.getLong(1) to it.getBoolean(2) }.first()
    if (!enabled && !force) return@tx 0
    val pending = c.select("SELECT id FROM $table WHERE feed_sync_id IS NULL ORDER BY id LIMIT $BATCH") { it.getLong(1) }
    var seq = current
    c.prepareStatement("UPDATE $table SET feed_sync_id = ? WHERE id = ? AND feed_sync_id IS NULL").use { ps ->
      for (id in pending) {
        ps.setLong(1, ++seq)
        ps.setLong(2, id)
        ps.addBatch()
      }
      ps.executeBatch()
    }
    c.exec("UPDATE sequences SET value = ? WHERE name = ?", listOf(seq, feed))
    pending.size
  }

  companion object {
    private const val BATCH = 500
  }
}

data class FeedPage(val feed: String, val entries: List<Any>, /** Pass as the next `after`. Unchanged when the page is empty. */ val cursor: Long)
data class FeedStatusDto(val feed: String, val sequence: Long, val rows: Long, val pending: Long, val publishing_enabled: Boolean)
data class FeedsStatus(val feeds: List<FeedStatusDto>)
data class Published(val published: Int)

@Singleton
class FeedService @Inject constructor(
  private val db: Db,
  private val tags: TagStore,
  private val comments: CommentStore,
  private val approvals: ApprovalStore,
  private val ownership: OwnershipStore,
) {
  fun fetch(feed: String, after: Long, limit: Int, shard: Int?, shardCount: Int?): FeedPage {
    feedTable(feed)
    if ((shard == null) != (shardCount == null)) throw BadRequestException("shard and shard_count go together")
    if (shard != null && (shardCount!! < 1 || shard !in 0 until shardCount)) throw BadRequestException("need 0 <= shard < shard_count")
    val shardSql = if (shard != null) "AND g.shard % $shardCount = $shard" else ""
    val n = limit.coerceIn(1, 1000)
    val (entries, last) = when (feed) {
      "taggings" -> tags.feed(after, n, shardSql).let { it to it.lastOrNull()?.feed_sync_id }
      "threads" -> comments.threadFeed(after, n, shardSql).let { it to it.lastOrNull()?.feed_sync_id }
      "comments" -> comments.commentFeed(after, n, shardSql).let { it to it.lastOrNull()?.feed_sync_id }
      "ownerships" -> ownership.feed(after, n, shardSql).let { it to it.lastOrNull()?.feed_sync_id }
      else -> approvals.feed(after, n, shardSql).let { it to it.lastOrNull()?.feed_sync_id }
    }
    return FeedPage(feed, entries, last ?: after)
  }

  fun status(): FeedsStatus = db.tx { c ->
    val seqs = c.select("SELECT name, value, publishing_enabled FROM sequences") { it.getString(1) to (it.getLong(2) to it.getBoolean(3)) }.toMap()
    FeedsStatus(FEEDS.map { (feed, table) ->
      c.select("SELECT COUNT(*), COALESCE(SUM(feed_sync_id IS NULL), 0) FROM $table") {
        FeedStatusDto(feed, seqs[feed]?.first ?: 0, it.getLong(1), it.getLong(2), seqs[feed]?.second ?: true)
      }.first()
    })
  }
}

// ========================================================================== notifications

/** How a queued notification leaves the building. Returns (status, detail). Throw to retry later. */
interface NotificationTransport {
  fun deliver(n: NotificationDto): Pair<String, String>
}

/**
 * SLACK goes to the configured incoming webhook when there is one. Everything else (no webhook,
 * EMAIL — there is no SMTP wiring here) is written to the service log and marked LOGGED, so the
 * outbox still shows exactly what would have been said to whom.
 */
@Singleton
class DefaultTransport @Inject constructor(private val config: TlcConfig, private val json: Json) : NotificationTransport {
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  override fun deliver(n: NotificationDto): Pair<String, String> {
    val hook = config.tlc.slack_webhook_url
    if (n.channel == "SLACK" && hook != null) {
      val body = json.write(mapOf("channel" to n.destination, "text" to n.body))
      val res = http.send(HttpRequest.newBuilder(URI(hook)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
      check(res.statusCode() in 200..299) { "slack webhook -> ${res.statusCode()} ${res.body().take(200)}" }
      return "SENT" to "slack webhook ${res.statusCode()}"
    }
    logger.info { "notify ${n.channel} ${n.destination}: ${n.body}" }
    return "LOGGED" to "no ${n.channel.lowercase()} transport configured; logged only"
  }

  companion object {
    private val logger = getLogger<DefaultTransport>()
  }
}

@Singleton
class NotificationDispatcher @Inject constructor(private val outbox: Outbox, private val transport: NotificationTransport) {
  /** At-least-once: a crash between deliver and mark re-sends. Gives up (FAILED) after 5 attempts. */
  @Synchronized
  fun drain(): Int {
    var n = 0
    for (p in outbox.pending(100)) {
      try {
        val (status, detail) = transport.deliver(p)
        outbox.mark(p.token, status, detail)
        n++
      } catch (e: Exception) {
        logger.warn(e) { "notification ${p.token} failed (attempt ${p.attempts + 1})" }
        outbox.mark(p.token, if (p.attempts + 1 >= 5) "FAILED" else "PENDING", e.message)
      }
    }
    return n
  }

  companion object {
    private val logger = getLogger<NotificationDispatcher>()
  }
}

/** The one background loop: publish feeds, revert unratified break-glass actions, deliver notifications. Single thread, self-rescheduling. */
@Singleton
class Background @Inject constructor(
  private val publisher: FeedPublisher,
  private val dispatcher: NotificationDispatcher,
  private val approvals: ApprovalStore,
  private val config: TlcConfig,
) : AbstractIdleService() {
  private lateinit var executor: ScheduledExecutorService

  override fun startUp() {
    val period = config.tlc.background_interval_ms
    executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tlc-background").apply { isDaemon = true } }
    executor.scheduleWithFixedDelay({
      try { publisher.publishAll() } catch (e: Exception) { logger.error(e) { "feed publish failed; will retry" } }
      try { approvals.expireRatifications() } catch (e: Exception) { logger.error(e) { "ratification expiry failed; will retry" } }
      try { dispatcher.drain() } catch (e: Exception) { logger.error(e) { "notification drain failed; will retry" } }
    }, period, period, TimeUnit.MILLISECONDS)
  }

  override fun shutDown() {
    executor.shutdownNow()
  }

  companion object {
    private val logger = getLogger<Background>()
  }
}

class FeedActions @Inject constructor(private val feeds: FeedService, private val publisher: FeedPublisher) : WebAction {
  /** `GET /_feeds/fetch/approvables?after=<cursor>&limit=100[&shard=0&shard_count=4]` */
  @Get("/_feeds/fetch/{feed}") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun fetch(@PathParam feed: String, @QueryParam after: Long?, @QueryParam limit: Int?, @QueryParam shard: Int?, @QueryParam shard_count: Int?) =
    feeds.fetch(feed, after ?: 0, limit ?: 100, shard, shard_count)

  @Get("/_feeds/status") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun status() = feeds.status()

  @Post("/_feeds/publish") @Authenticated(capabilities = [ADMIN]) @ResponseContentType(JSON)
  fun publish(@QueryParam feed: String?) = Published(publisher.publishAll(feed, force = true))

  @Post("/_feeds/{feed}/pause") @Authenticated(capabilities = [ADMIN]) @ResponseContentType(JSON)
  fun pause(@PathParam feed: String): Ok { publisher.setEnabled(feed, false); return Ok() }

  @Post("/_feeds/{feed}/resume") @Authenticated(capabilities = [ADMIN]) @ResponseContentType(JSON)
  fun resume(@PathParam feed: String): Ok { publisher.setEnabled(feed, true); return Ok() }
}
