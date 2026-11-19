package tcj.tlc

import com.google.common.util.concurrent.AbstractIdleService
import com.google.inject.multibindings.ProvidesIntoSet
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
import misk.inject.KAbstractModule
import misk.logging.getLogger
import tcj.feedz.FeedSource
import tcj.feedz.feedSource

/**
 * The feeds, served by misk-feedz (`/_feeds/...`, `/_admin/feedz/`): each is a table whose rows carry
 * (id, feed_sync_id, shard) and whose mutations NULL feed_sync_id. The library publishes, pages and
 * keeps consumer cursors; these say which tables, and how a page of each is read as its DTO.
 */
class TlcFeedsModule : KAbstractModule() {
  override fun configure() {}

  @ProvidesIntoSet fun taggings(tags: TagStore): FeedSource =
    feedSource<TaggingDto>("taggings", "taggings", syncId = { it.feed_sync_id }) { tags.feed(it.after, it.limit, it.shardSql("g")) }

  @ProvidesIntoSet fun threads(comments: CommentStore): FeedSource =
    feedSource<ThreadDto>("threads", "threads", syncId = { it.feed_sync_id }) { comments.threadFeed(it.after, it.limit, it.shardSql("g")) }

  @ProvidesIntoSet fun comments(comments: CommentStore): FeedSource =
    feedSource<CommentDto>("comments", "comments", syncId = { it.feed_sync_id }) { comments.commentFeed(it.after, it.limit, it.shardSql("g")) }

  @ProvidesIntoSet fun approvables(approvals: ApprovalStore): FeedSource =
    feedSource<ApprovableDto>("approvables", "approvables", syncId = { it.feed_sync_id }) { approvals.feed(it.after, it.limit, it.shardSql("g")) }

  @ProvidesIntoSet fun ownerships(ownership: OwnershipStore): FeedSource =
    feedSource<OwnershipOpDto>("ownerships", "ownerships", syncId = { it.feed_sync_id }) { ownership.feed(it.after, it.limit, it.shardSql("g")) }
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

/** The background loop: revert unratified break-glass actions, deliver notifications. Single thread, self-rescheduling. (Feeds publish on misk-feedz's own loop.) */
@Singleton
class Background @Inject constructor(
  private val dispatcher: NotificationDispatcher,
  private val approvals: ApprovalStore,
  private val config: TlcConfig,
) : AbstractIdleService() {
  private lateinit var executor: ScheduledExecutorService

  override fun startUp() {
    val period = config.tlc.background_interval_ms
    executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tlc-background").apply { isDaemon = true } }
    executor.scheduleWithFixedDelay({
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
