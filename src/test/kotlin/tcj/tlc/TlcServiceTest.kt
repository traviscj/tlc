package tcj.tlc

import com.google.inject.Module
import javax.inject.Inject
import misk.MiskTestingServiceModule
import misk.config.ConfigModule
import misk.config.MiskConfig
import misk.inject.KAbstractModule
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import misk.web.WebServerTestingModule
import misk.web.jetty.JettyService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import wisp.deployment.TESTING

class TlcTestModule : KAbstractModule() {
  override fun configure() {
    val config = MiskConfig.load<TlcConfig>("tlc", TESTING, listOf<String>())
    install(MiskTestingServiceModule())
    install(WebServerTestingModule())
    install(ConfigModule.create("tlc", config))
    install(TlcModule(config))
  }
}

/** Drives the service over real HTTP, the same path the UI, the CLI and source services use. */
@MiskTest(startService = true)
class TlcServiceTest {
  @MiskTestModule val module: Module = TlcTestModule()

  @Inject lateinit var jetty: JettyService
  @Inject lateinit var db: Db
  @Inject lateinit var json: Json
  @Inject lateinit var publisher: FeedPublisher
  @Inject lateinit var dispatcher: NotificationDispatcher
  @Inject lateinit var clock: misk.time.FakeClock
  @Inject lateinit var approvals: ApprovalStore

  private val http = OkHttpClient()
  private val jsonType = "application/json".toMediaType()
  private val pc = "sentinel/proposed_changes"

  @BeforeEach
  fun setUp() {
    // MiskTestingServiceModule pins the clock (to 2018); the scenarios below are written against late 2026.
    clock.setNow(java.time.Instant.parse("2026-11-10T12:00:00Z"))
    db.tx { c ->
      for (t in listOf("external_entity_types", "external_entities", "tlcers", "tags", "taggings", "log_entries", "threads", "comments", "likes", "approval_rules", "approval_rule_groups", "approvables", "approvals", "directory_memberships", "notifications", "sequences", "ownerships", "ownership_aspects", "entity_edges", "directory_groups", "approval_rule_aspects", "approvable_parties", "approval_rule_types", "approvable_impacts")) c.exec("DELETE FROM $t")
    }
    post("/api/entity-types", mapOf("source_service" to "sentinel", "source_entity" to "proposed_changes", "url_template" to "https://sentinel.example/pc/{id}"))
  }

  // ------------------------------------------------------------------------------ helpers

  class Res(val code: Int, val text: String, val body: Map<String, Any?>) {
    operator fun get(k: String): Any? = body[k]
    @Suppress("UNCHECKED_CAST") fun obj(k: String) = body[k] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") fun list(k: String) = body[k] as List<Map<String, Any?>>
  }

  @Suppress("UNCHECKED_CAST")
  private fun call(method: String, path: String, body: Any? = null, user: String? = "alice", service: String? = null): Res {
    val rb = when {
      body != null -> json.write(body).toRequestBody(jsonType)
      method in setOf("POST", "PUT", "PATCH") -> "{}".toRequestBody(jsonType)
      else -> null
    }
    val req = Request.Builder().url(jetty.httpServerUrl.resolve(path)!!).method(method, rb)
    user?.let { req.header("X-TLC-User", it) }
    service?.let { req.header("X-TLC-Service", it) }
    http.newCall(req.build()).execute().use { r ->
      val text = r.body!!.string()
      val parsed = if (text.startsWith("{")) json.parse(text) as Map<String, Any?> else mapOf()
      return Res(r.code, text, parsed)
    }
  }

  private fun get(path: String, user: String? = "alice") = call("GET", path, user = user)
  private fun post(path: String, body: Any? = null, user: String? = "alice", service: String? = null) = call("POST", path, body, user, service)
  private fun ok(r: Res): Res { assertThat(r.code).describedAs(r.text).isEqualTo(200); return r }

  private fun tag(target: String, tag: String, at: String? = null, user: String = "alice", op: String = "tag") =
    ok(post("/api/taggings/$op", mapOf("target" to target, "tag" to tag, "effective_at" to at, "create_tag" to true), user))

  private fun member(group: String, ldap: String, admin: Boolean = false) = ok(call("PUT", "/api/directory/$group/members/$ldap?admin=$admin", user = "root"))

  private fun rule(token: String, requirement: String, groups: List<Map<String, Any?>> = listOf(), minGroups: Int? = null) =
    ok(post("/api/approval-rules", mapOf("token" to token, "description" to "test rule", "requirement" to requirement, "min_groups" to minGroups, "groups" to groups), "root"))

  private fun grp(name: String, directory: String, adminsOnly: Boolean = false, slack: String? = null, email: String? = null) =
    mapOf("group" to name, "directory_group" to directory, "admins_only" to adminsOnly, "slack_channel" to slack, "email" to email)

  private fun request(target: String, rule: String, user: String? = "alice", service: String? = null, extra: Map<String, Any?> = mapOf()) =
    ok(post("/api/approvables", mapOf("target" to target, "rule" to rule, "context" to "because") + extra, user, service)).obj("approvable")["token"] as String

  private fun state(token: String) = ok(get("/api/approvables/$token")).obj("approvable")["approval_state"]

  // -------------------------------------------------------------------------------- auth

  @Test
  fun `auth is enforced`() {
    assertThat(get("/api/tags", user = null).code).isEqualTo(401)
    assertThat(get("/api/tags", user = "Not A Valid Ldap!").code).isEqualTo(401)
    // admin-only surface
    assertThat(post("/api/approval-rules", mapOf("description" to "x", "requirement" to "ANY_APPROVAL"), "alice").code).isEqualTo(403)
    assertThat(call("PUT", "/api/directory/g/members/bob", user = "alice").code).isEqualTo(403)
    val me = ok(get("/api/me", "root"))
    assertThat(me["admin"]).isEqualTo(true)
    assertThat(get("/").code).isEqualTo(200)
  }

  // ---------------------------------------------------------------------------- entities

  @Test
  fun `entity types are explicit, entities vivify on reference`() {
    // unregistered type: refs 404 and nothing is minted
    assertThat(post("/api/taggings/tag", mapOf("target" to "sentinel/typo_table/1", "tag" to "x", "create_tag" to true)).code).isEqualTo(404)
    assertThat(post("/api/entity-types", mapOf("source_service" to "sentinel", "source_entity" to "proposed_changes")).code).isEqualTo(409)

    assertThat(get("/api/entities/get?target=$pc/1234").code).isEqualTo(404)
    tag("$pc/1234", "urgent")
    val e = ok(get("/api/entities/get?target=$pc/1234"))
    assertThat(e["ref"]).isEqualTo("$pc/1234")
    assertThat(e["url"]).isEqualTo("https://sentinel.example/pc/1234")
    // same record by token
    assertThat(ok(get("/api/entities/get?target=${e["token"]}"))["source_entity_id"]).isEqualTo("1234")

    ok(call("PATCH", "/api/entities?target=$pc/1234", mapOf("display_name" to "raise TR limit")))
    assertThat(ok(get("/api/entities?q=raise")).list("entities")).hasSize(1)

    val gone = ok(call("DELETE", "/api/entities?target=$pc/1234"))
    assertThat(gone["deleted_at"]).isNotNull()
    assertThat(ok(get("/api/entities")).list("entities")).isEmpty()
    // reads still work against a destroyed record; a write revives it
    assertThat(ok(get("/api/taggings/is-tagged?target=$pc/1234&tag=urgent"))["tagged"]).isEqualTo(true)
    tag("$pc/1234", "second")
    assertThat(ok(get("/api/entities/get?target=$pc/1234")).body.containsKey("deleted_at")).isFalse()

    // type destroy is admin-only and soft
    assertThat(call("DELETE", "/api/entity-types?type=$pc").code).isEqualTo(403)
    ok(call("DELETE", "/api/entity-types?type=$pc", user = "root"))
    assertThat(post("/api/taggings/tag", mapOf("target" to "$pc/999", "tag" to "urgent")).code).isEqualTo(404)
    assertThat(ok(get("/api/taggings/is-tagged?target=$pc/1234&tag=urgent"))["tagged"]).isEqualTo(true)
    assertThat(ok(get("/api/tlcers")).list("tlcers").map { it["ldap"] }).contains("alice")
  }

  // -------------------------------------------------------------------------------- tags

  @Test
  fun `tag, untag, is tagged, was tagged`() {
    val t = "$pc/1"
    assertThat(post("/api/taggings/tag", mapOf("target" to t, "tag" to "nope")).code).isEqualTo(404)
    val first = tag(t, "urgent", "2026-01-10T00:00:00Z")
    assertThat(first["changed"]).isEqualTo(true)
    assertThat(tag(t, "urgent", "2026-01-11T00:00:00Z")["changed"]).isEqualTo(false) // already tagged then
    tag(t, "urgent", "2026-01-20T00:00:00Z", user = "bob", op = "untag")
    tag(t, "urgent", "2026-02-01T00:00:00Z")

    fun isAt(at: String) = ok(get("/api/taggings/is-tagged?target=$t&tag=urgent&at=$at"))["tagged"]
    assertThat(isAt("2026-01-09T00:00:00Z")).isEqualTo(false)
    assertThat(isAt("2026-01-15T00:00:00Z")).isEqualTo(true)
    assertThat(isAt("2026-01-20T00:00:00Z")).isEqualTo(false) // half-open: untagged AT the remove instant
    assertThat(isAt("2026-01-25T00:00:00Z")).isEqualTo(false)
    val now = ok(get("/api/taggings/is-tagged?target=$t&tag=urgent"))
    assertThat(now["tagged"]).isEqualTo(true)
    assertThat(now["since"]).isEqualTo("2026-02-01T00:00:00Z")

    val was = ok(get("/api/taggings/was-tagged?target=$t&tag=urgent"))
    assertThat(was["was_tagged"]).isEqualTo(true)
    val iv = was.list("intervals")
    assertThat(iv).hasSize(2)
    assertThat(iv[0]["from"]).isEqualTo("2026-01-10T00:00:00Z")
    assertThat(iv[0]["to"]).isEqualTo("2026-01-20T00:00:00Z")
    assertThat(iv[0]["removed_by"]).isEqualTo("bob")
    assertThat(iv[1].containsKey("to")).isFalse()

    val gap = ok(get("/api/taggings/was-tagged?target=$t&tag=urgent&from=2026-01-21T00:00:00Z&to=2026-01-30T00:00:00Z"))
    assertThat(gap["was_tagged"]).isEqualTo(false)
    val inside = ok(get("/api/taggings/was-tagged?target=$t&tag=urgent&from=2026-01-12T00:00:00Z&to=2026-01-14T00:00:00Z"))
    assertThat(inside["throughout"]).isEqualTo(true)
    val straddle = ok(get("/api/taggings/was-tagged?target=$t&tag=urgent&from=2026-01-15T00:00:00Z&to=2026-01-25T00:00:00Z"))
    assertThat(straddle["was_tagged"]).isEqualTo(true)
    assertThat(straddle["throughout"]).isEqualTo(false)

    // never-seen target: false, not 404, and no record is minted
    assertThat(ok(get("/api/taggings/is-tagged?target=$pc/never&tag=urgent"))["tagged"]).isEqualTo(false)
    assertThat(tag("$pc/never", "urgent", op = "untag")["changed"]).isEqualTo(false)
    assertThat(get("/api/entities/get?target=$pc/never").code).isEqualTo(404)

    assertThat(ok(get("/api/taggings?target=$t")).list("taggings")).hasSize(3)
    assertThat(ok(get("/api/taggings/tags?target=$t")).list("tags").map { it["tag"] }).containsExactly("urgent")
    assertThat(ok(get("/api/taggings/targets?tag=urgent")).list("targets").map { it["target"] }).containsExactly(t)
    assertThat(ok(get("/api/tags/urgent"))["tagged_now"]).isEqualTo(1.0)
  }

  @Test
  fun `backdating rewrites effect time but known_at recovers what TLC said then`() {
    val t = "$pc/2"
    tag(t, "seed") // make the record exist
    clock.add(java.time.Duration.ofHours(1))
    val before = clock.instant().toString()
    clock.add(java.time.Duration.ofHours(1))
    tag(t, "fraud", "2026-03-01T00:00:00Z") // learned now, effective long ago
    val q = "/api/taggings/is-tagged?target=$t&tag=fraud&at=2026-03-05T00:00:00Z"
    assertThat(ok(get(q))["tagged"]).isEqualTo(true)
    assertThat(ok(get("$q&known_at=$before"))["tagged"]).isEqualTo(false)
    // the op reports both views
    val future = tag(t, "later", "2099-01-01T00:00:00Z")
    assertThat(future["tagged"]).isEqualTo(true)
    assertThat(future["tagged_now"]).isEqualTo(false)
  }

  @Test
  fun `retired tags refuse new adds, internal objects are taggable`() {
    tag("$pc/3", "legacy")
    ok(call("DELETE", "/api/tags/legacy"))
    assertThat(post("/api/taggings/tag", mapOf("target" to "$pc/4", "tag" to "legacy")).code).isEqualTo(409)
    assertThat(ok(get("/api/taggings/is-tagged?target=$pc/3&tag=legacy"))["tagged"]).isEqualTo(true)
    tag("$pc/3", "legacy", op = "untag") // removal is always allowed
    assertThat(ok(get("/api/tags")).list("tags").map { it["tag_name"] }).doesNotContain("legacy")
    assertThat(ok(get("/api/tags?include_ineffective=true")).list("tags").map { it["tag_name"] }).contains("legacy")
    // re-creating un-retires
    ok(post("/api/tags", mapOf("tag_name" to "legacy")))
    tag("$pc/4", "legacy")

    val thread = ok(post("/api/threads", mapOf("title" to "free-standing")))["token"] as String
    tag(thread, "pinned")
    assertThat(ok(get("/api/taggings/is-tagged?target=$thread&tag=pinned"))["tagged"]).isEqualTo(true)
    assertThat(post("/api/taggings/tag", mapOf("target" to "T-nope", "tag" to "pinned")).code).isEqualTo(404)
    assertThat(post("/api/tags", mapOf("tag_name" to "Bad Name")).code).isEqualTo(400)
  }

  // -------------------------------------------------------------------------------- logs

  @Test
  fun `logs append, filter, tail, and carry TLC's own audit trail`() {
    val r = ok(post("/api/logs", mapOf("entries" to listOf(
      mapOf("target" to "$pc/7", "level" to "INFO", "message" to "created", "timestamp" to "2026-05-01T00:00:00Z"),
      mapOf("target" to "$pc/7", "level" to "ERROR", "message" to "backtest blew up", "source" to "sentinel.backtest"),
      mapOf("entity_type" to pc, "level" to "warn", "message" to "table-wide notice"),
      mapOf("message" to "unscoped"),
    )), user = null, service = "sentinel"))
    assertThat(r["appended"]).isEqualTo(4.0)

    val all = ok(get("/api/logs?target=$pc/7")).list("entries")
    assertThat(all.map { it["message"] }).containsExactly("backtest blew up", "created") // newest first
    assertThat(all[1]["source"]).isEqualTo("sentinel") // defaults to the calling service
    assertThat(all[1]["timestamp"]).isEqualTo("2026-05-01T00:00:00Z")
    assertThat(ok(get("/api/logs?target=$pc/7&level=WARN")).list("entries")).hasSize(1)
    assertThat(ok(get("/api/logs?entity_type=$pc")).list("entries")).hasSize(3)
    assertThat(ok(get("/api/logs?source=sentinel.")).list("entries")).hasSize(1)
    assertThat(ok(get("/api/logs?q=blew")).list("entries")).hasSize(1)
    assertThat(ok(get("/api/logs?target=$pc/unseen")).list("entries")).isEmpty()

    // tail
    val page = ok(get("/api/logs?after_id=0&limit=2"))
    assertThat(page.list("entries").map { it["message"] }).containsExactly("created", "backtest blew up")
    val next = ok(get("/api/logs?after_id=${(page["cursor"] as Double).toLong()}"))
    assertThat(next.list("entries")).hasSize(2)
    assertThat(ok(get("/api/logs?after_id=${(next["cursor"] as Double).toLong()}"))["cursor"]).isEqualTo(next["cursor"])

    tag("$pc/7", "urgent")
    assertThat(ok(get("/api/logs?target=$pc/7&source=tlc.")).list("entries").map { it["message"] }).containsExactly("tagged 'urgent' by alice")
    assertThat(post("/api/logs", mapOf("message" to "x", "level" to "LOUD")).code).isEqualTo(400)
  }

  // ---------------------------------------------------------------------------- comments

  @Test
  fun `threads, nested comments, edits, tombstones`() {
    val t = ok(post("/api/threads", mapOf("title" to "is this limit sane?", "body" to "see diff", "target" to "$pc/9")))["token"] as String
    fun say(content: String, parent: String? = null, user: String = "alice") =
      ok(post("/api/threads/$t/comments", mapOf("content" to content, "parent_comment" to parent), user))["comment_token"] as String
    val a = say("A")
    val b = say("B", user = "bob")
    val a1 = say("A1", a, "bob")
    val a11 = say("A1a", a1)
    val a2 = say("A2", a)
    // reply addressed by parent alone
    ok(post("/api/comments", mapOf("parent_comment" to b, "content" to "B1")))

    val view = ok(get("/api/threads/$t"))
    val cs = view.list("comments")
    assertThat(cs.map { it["content"] }).containsExactly("A", "A1", "A1a", "A2", "B", "B1") // depth-first, oldest first
    assertThat(cs.map { (it["depth"] as Double).toInt() }).containsExactly(0, 1, 2, 1, 0, 1)
    assertThat(cs[2]["parent_comment"]).isEqualTo(a1)
    assertThat(view.obj("thread")["cached_comments"]).isEqualTo(6.0)
    assertThat(view.obj("thread")["target"]).isEqualTo("$pc/9")
    assertThat(ok(get("/api/threads?target=$pc/9")).list("threads")).hasSize(1)

    // only the author (or an admin) edits
    assertThat(call("PATCH", "/api/comments/$a", mapOf("content" to "hax"), "bob").code).isEqualTo(403)
    val edited = ok(call("PATCH", "/api/comments/$a", mapOf("content" to "A!")))
    assertThat(edited["edited_at"]).isNotNull()
    ok(call("PATCH", "/api/comments/$a", mapOf("content" to "A (mod)"), "root"))

    // rm: content gone, row and children stay put
    val dead = ok(call("DELETE", "/api/comments/$a1", user = "bob"))
    assertThat(dead["content"]).isEqualTo("")
    val after = ok(get("/api/threads/$t"))
    assertThat(after.list("comments").map { it["comment_token"] }).contains(a1, a11)
    assertThat(after.obj("thread")["cached_comments"]).isEqualTo(5.0)
    assertThat(post("/api/comments", mapOf("parent_comment" to a1, "content" to "late")).code).isEqualTo(409)
    assertThat(ok(get("/api/comments?author=bob")).list("comments")).hasSize(2)

    assertThat(call("DELETE", "/api/threads/$t", user = "bob").code).isEqualTo(403)
    ok(call("DELETE", "/api/threads/$t"))
    assertThat(post("/api/threads/$t/comments", mapOf("content" to "x")).code).isEqualTo(409)
    assertThat(ok(get("/api/threads")).list("threads")).isEmpty()
    assertThat(a2).startsWith("C-")
  }

  @Test
  fun `replies past max depth flatten into siblings`() {
    val t = ok(post("/api/threads", mapOf("title" to "deep")))["token"] as String
    var parent: String? = null
    val depths = (0..19).map {
      val c = ok(post("/api/threads/$t/comments", mapOf("content" to "n$it", "parent_comment" to parent)))
      parent = c["comment_token"] as String
      (c["depth"] as Double).toInt()
    }
    assertThat(depths.max()).isEqualTo(16)
    assertThat(depths.takeLast(3)).containsExactly(16, 16, 16)
  }

  @Test
  fun `likes are episodes`() {
    val t = ok(post("/api/threads", mapOf("title" to "x")))["token"] as String
    val c = ok(post("/api/threads/$t/comments", mapOf("content" to "nice")))["comment_token"] as String
    assertThat(ok(post("/api/comments/$c/like", user = "bob"))["likes"]).isEqualTo(1.0)
    assertThat(ok(post("/api/comments/$c/like", user = "bob"))["changed"]).isEqualTo(false)
    assertThat(ok(post("/api/comments/$c/like", user = "carol"))["likes"]).isEqualTo(2.0)
    assertThat(ok(get("/api/comments/$c", "bob"))["liked_by_me"]).isEqualTo(true)
    assertThat(ok(get("/api/comments/$c", "alice"))["liked_by_me"]).isEqualTo(false)
    assertThat(ok(post("/api/comments/$c/unlike", user = "bob"))["likes"]).isEqualTo(1.0)
    assertThat(ok(post("/api/comments/$c/unlike", user = "bob"))["changed"]).isEqualTo(false)
    ok(post("/api/comments/$c/like", user = "bob"))
    assertThat(ok(get("/api/comments/$c/likes")).list("likes")).hasSize(2)
    val all = ok(get("/api/comments/$c/likes?include_unliked=true")).list("likes")
    assertThat(all).hasSize(3) // bob's first episode is kept, closed
    assertThat(all.count { it["unliked_at"] != null }).isEqualTo(1)
    assertThat(ok(get("/api/comments/$c"))["likes"]).isEqualTo(2.0)
  }

  // --------------------------------------------------------------------------- approvals

  @Test
  fun `any non-self approval, then execute exactly once`() {
    rule("AR-1", "ANY_NON_SELF_APPROVAL")
    val ap = request("$pc/20", "AR-1")
    assertThat(state(ap)).isEqualTo("PENDING")
    assertThat(post("/api/approvables/$ap/execute").code).isEqualTo(409)
    assertThat(post("/api/approvables/$ap/approve").code).isEqualTo(403) // self
    assertThat(post("/api/approvables/$ap/approve", user = null, service = "sentinel").code).isEqualTo(400) // needs a human
    val r = ok(post("/api/approvables/$ap/approve", mapOf("comment" to "lgtm"), "bob"))
    assertThat(r["changed"]).isEqualTo(true)
    assertThat(r.obj("approvable")["approval_state"]).isEqualTo("APPROVED")
    assertThat(ok(post("/api/approvables/$ap/approve", user = "bob"))["changed"]).isEqualTo(false)

    assertThat(post("/api/approvables/$ap/execute", user = "bob").code).isEqualTo(403) // not the requesting side
    assertThat(post("/api/approvables/$ap/execute", mapOf("expected_version" to 1)).code).isEqualTo(409)
    assertThat(ok(post("/api/approvables/$ap/execute"))["changed"]).isEqualTo(true)
    assertThat(ok(post("/api/approvables/$ap/execute"))["changed"]).isEqualTo(false) // the claim was already taken
    assertThat(state(ap)).isEqualTo("EXECUTED")
    assertThat(post("/api/approvables/$ap/unapprove", user = "bob").code).isEqualTo(409)
    assertThat(post("/api/approvables/$ap/decline", user = "bob").code).isEqualTo(409)

    val audit = ok(get("/api/logs?target=$pc/20&source=tlc.approvals&order=asc")).list("entries").map { it["message"] as String }
    assertThat(audit.map { it.substringAfter(": ") }).containsExactly(
      "approval requested by alice under AR-1 (ANY_NON_SELF_APPROVAL)", "approved by bob",
      "PENDING -> APPROVED (approval by bob; approved by bob)", "APPROVED -> EXECUTED by alice",
    )
  }

  @Test
  fun `any approval allows self`() {
    rule("AR-2", "ANY_APPROVAL")
    val ap = request("$pc/21", "AR-2")
    assertThat(ok(post("/api/approvables/$ap/approve")).obj("approvable")["approval_state"]).isEqualTo("APPROVED")
  }

  @Test
  fun `group requirements need distinct people and withdrawals reopen`() {
    member("trust-infra", "alice"); member("trust-infra", "bob"); member("trust-risk", "bob"); member("trust-legal", "carol"); member("trust-infra", "dave")
    rule("AR-3", "MIN_GROUPS", listOf(grp("ENG", "trust-infra", slack = "#trust-infra-oncall"), grp("RISK", "trust-risk"), grp("LEGAL", "trust-legal", email = "legal@example.com")), 2)
    val ap = request("$pc/30", "AR-3", user = "erin")

    assertThat(post("/api/approvables/$ap/approve", user = "mallory").code).isEqualTo(403) // in no group
    // bob is in ENG and RISK but is one person: 1 group, not 2
    val one = ok(post("/api/approvables/$ap/approve", user = "bob"))
    assertThat(one.obj("approvable")["approval_state"]).isEqualTo("PENDING")
    assertThat(one.obj("evaluation")["have"]).isEqualTo(1.0)
    // dave is ENG-only: the matching must move bob to RISK so both count
    val two = ok(post("/api/approvables/$ap/approve", user = "dave"))
    assertThat(two.obj("approvable")["approval_state"]).isEqualTo("APPROVED")
    @Suppress("UNCHECKED_CAST")
    val groups = (two.obj("evaluation")["groups"] as List<Map<String, Any?>>).associate { it["group"] to it["satisfied_by"] }
    assertThat(groups).isEqualTo(mapOf("ENG" to "dave", "RISK" to "bob", "LEGAL" to null))

    // membership is snapshotted at approval: removing dave from the directory doesn't unapprove
    ok(call("DELETE", "/api/directory/trust-infra/members/dave", user = "root"))
    assertThat(state(ap)).isEqualTo("APPROVED")
    // ...but withdrawing does
    val back = ok(post("/api/approvables/$ap/unapprove", user = "dave"))
    assertThat(back.obj("approvable")["approval_state"]).isEqualTo("PENDING")
    assertThat(back.obj("approvable").containsKey("approved_at")).isFalse()
    assertThat(post("/api/approvables/$ap/execute", user = "erin").code).isEqualTo(409)

    // "my queue"
    assertThat(ok(get("/api/approvables?awaiting=me", "carol")).list("approvables").map { it["token"] }).containsExactly(ap)
    assertThat(ok(get("/api/approvables?awaiting=me", "bob")).list("approvables")).isEmpty() // already approved
    assertThat(ok(get("/api/approvables?awaiting=me", "erin")).list("approvables")).isEmpty() // requester
    assertThat(ok(get("/api/approvables/$ap", "carol")).obj("you")["allowed"]).isEqualTo(true)

    ok(post("/api/approvables/$ap/approve", user = "carol"))
    assertThat(state(ap)).isEqualTo("APPROVED")

    dispatcher.drain()
    val notes = ok(get("/api/notifications?approvable=$ap")).list("notifications")
    assertThat(notes.filter { it["event"] == "REQUESTED" }.map { it["destination"] }).containsExactlyInAnyOrder("#trust-infra-oncall", "legal@example.com")
    assertThat(notes.count { it["event"] == "APPROVED" }).isEqualTo(4) // approved twice (dave, then carol) x 2 destinations
    assertThat(notes.map { it["status"] }.toSet()).containsExactly("LOGGED")
  }

  @Test
  fun `majority, all groups, admins only`() {
    member("g1", "u1"); member("g2", "u2"); member("g3", "u3", admin = true); member("g3", "pleb")
    val gs = listOf(grp("G1", "g1"), grp("G2", "g2"), grp("G3", "g3", adminsOnly = true))
    rule("AR-MAJ", "MAJORITY_OF_GROUPS", gs)
    rule("AR-ALL", "ALL_GROUPS", gs)
    val maj = request("$pc/40", "AR-MAJ")
    val all = request("$pc/41", "AR-ALL")
    assertThat(post("/api/approvables/$maj/approve", user = "pleb").code).isEqualTo(403) // member, not admin
    for (ap in listOf(maj, all)) { ok(post("/api/approvables/$ap/approve", user = "u1")); ok(post("/api/approvables/$ap/approve", user = "u3")) }
    assertThat(state(maj)).isEqualTo("APPROVED") // 2 of 3
    assertThat(state(all)).isEqualTo("PENDING")
    ok(post("/api/approvables/$all/approve", user = "u2"))
    assertThat(state(all)).isEqualTo("APPROVED")

    // rule validation
    assertThat(post("/api/approval-rules", mapOf("description" to "x", "requirement" to "ALL_GROUPS"), "root").code).isEqualTo(400)
    assertThat(post("/api/approval-rules", mapOf("description" to "x", "requirement" to "MIN_GROUPS", "min_groups" to 4, "groups" to gs), "root").code).isEqualTo(400)
    assertThat(post("/api/approval-rules", mapOf("description" to "x", "requirement" to "SLACK_NOTIFICATION", "groups" to gs), "root").code).isEqualTo(400)
    val auto = ok(post("/api/approval-rules", mapOf("description" to "auto token", "requirement" to "ANY_APPROVAL"), "root"))
    assertThat(auto["token"] as String).matches("AR-\\d{4}")
  }

  @Test
  fun `notification-only rules approve themselves`() {
    rule("AR-FYI", "SLACK_NOTIFICATION", listOf(grp("ENG", "trust-infra", slack = "#trust-infra-oncall", email = "ignored@example.com")))
    val ap = request("$pc/50", "AR-FYI")
    assertThat(state(ap)).isEqualTo("APPROVED")
    assertThat(post("/api/approvables/$ap/approve", user = "bob").code).isEqualTo(409)
    val notes = ok(get("/api/notifications?approvable=$ap")).list("notifications")
    assertThat(notes.map { "${it["channel"]} ${it["event"]} ${it["status"]}" }).containsExactly("SLACK NOTIFY PENDING")
    assertThat(dispatcher.drain()).isEqualTo(1)
    assertThat(ok(post("/api/approvables/$ap/execute"))["changed"]).isEqualTo(true)
  }

  @Test
  fun `decline is terminal, services request on behalf of users, client_id dedupes`() {
    member("trust-infra", "bob")
    rule("AR-5", "ANY_NON_SELF_APPROVAL", listOf(grp("ENG", "trust-infra")))
    // sentinel files it for alice
    val body = mapOf("requested_by" to "alice", "client_id" to "sentinel-pc-60-v1")
    val ap = request("$pc/60", "AR-5", user = null, service = "sentinel", extra = body)
    assertThat(request("$pc/60", "AR-5", user = null, service = "sentinel", extra = body)).isEqualTo(ap)
    assertThat(post("/api/approvables", mapOf("target" to "$pc/61", "rule" to "AR-5", "client_id" to "sentinel-pc-60-v1"), null, "sentinel").code).isEqualTo(409)
    // a human can't claim someone else requested it
    assertThat(post("/api/approvables", mapOf("target" to "$pc/62", "rule" to "AR-5", "requested_by" to "bob")).code).isEqualTo(400)

    ok(post("/api/approvables/$ap/approve", user = "bob"))
    assertThat(post("/api/approvables/$ap/execute", user = "alice").code).isEqualTo(403) // filed via sentinel: sentinel executes
    assertThat(ok(post("/api/approvables/$ap/execute", user = null, service = "sentinel"))["changed"]).isEqualTo(true)

    val ap2 = request("$pc/63", "AR-5")
    assertThat(post("/api/approvables/$ap2/decline", mapOf("reason" to "no"), "mallory").code).isEqualTo(403)
    val d = ok(post("/api/approvables/$ap2/decline", mapOf("reason" to "limit too high"), "bob"))
    assertThat(d.obj("approvable")["decline_reason"]).isEqualTo("limit too high")
    assertThat(post("/api/approvables/$ap2/approve", user = "bob").code).isEqualTo(409)
    assertThat(post("/api/approvables/$ap2/execute").code).isEqualTo(409)
    assertThat(ok(get("/api/approvables?state=declined")).list("approvables")).hasSize(1)
    assertThat(ok(get("/api/approvables?target=$pc/60")).list("approvables")).hasSize(1)
  }

  @Test
  fun `a rule's requirement can't change under open approvables without force`() {
    member("trust-infra", "bob"); member("trust-risk", "carol")
    rule("AR-6", "ALL_GROUPS", listOf(grp("ENG", "trust-infra"), grp("RISK", "trust-risk")))
    val ap = request("$pc/70", "AR-6")
    ok(post("/api/approvables/$ap/approve", user = "bob"))
    assertThat(state(ap)).isEqualTo("PENDING")
    ok(call("PATCH", "/api/approval-rules/AR-6", mapOf("description" to "reworded"), "root")) // description: always fine
    assertThat(call("PATCH", "/api/approval-rules/AR-6", mapOf("requirement" to "MIN_GROUPS", "min_groups" to 1), "root").code).isEqualTo(409)
    assertThat(call("DELETE", "/api/approval-rules/AR-6", user = "root").code).isEqualTo(409)
    ok(call("PATCH", "/api/approval-rules/AR-6?force=true", mapOf("requirement" to "MIN_GROUPS", "min_groups" to 1), "root"))
    assertThat(state(ap)).isEqualTo("APPROVED") // re-evaluated in the same transaction
    ok(call("PATCH", "/api/approval-rules/AR-6?force=true", mapOf("requirement" to "ALL_GROUPS"), "root"))
    assertThat(state(ap)).isEqualTo("PENDING")
  }

  // ------------------------------------------------------------------------------- feeds

  @Test
  fun `feeds publish in order and republish on change`() {
    rule("AR-7", "ANY_NON_SELF_APPROVAL")
    val ap = request("$pc/80", "AR-7")
    val ap2 = request("$pc/81", "AR-7")
    assertThat(ok(get("/_feeds/fetch/approvables")).list("entries")).isEmpty() // nothing published yet
    assertThat(publisher.publishAll()).isGreaterThanOrEqualTo(2)
    val p1 = ok(get("/_feeds/fetch/approvables?after=0"))
    assertThat(p1.list("entries").map { it["token"] }).containsExactly(ap, ap2)
    val cursor = (p1["cursor"] as Double).toLong()
    assertThat(ok(get("/_feeds/fetch/approvables?after=$cursor"))["cursor"]).isEqualTo(p1["cursor"])

    ok(post("/api/approvables/$ap/approve", user = "bob"))
    publisher.publishAll()
    val p2 = ok(get("/_feeds/fetch/approvables?after=$cursor")).list("entries")
    assertThat(p2.map { "${it["token"]} ${it["approval_state"]}" }).containsExactly("$ap APPROVED")
    assertThat((p2[0]["feed_sync_id"] as Double).toLong()).isGreaterThan(cursor)

    // shards partition the feed
    repeat(12) { tag("$pc/s$it", "shardy") }
    val t = ok(post("/api/threads", mapOf("title" to "f")))["token"] as String
    ok(post("/api/threads/$t/comments", mapOf("content" to "c")))
    publisher.publishAll()
    val whole = ok(get("/_feeds/fetch/taggings")).list("entries")
    val parts = (0 until 3).flatMap { ok(get("/_feeds/fetch/taggings?shard=$it&shard_count=3")).list("entries") }
    assertThat(parts.map { it["token"] }).containsExactlyInAnyOrderElementsOf(whole.map { it["token"] })
    assertThat(whole).hasSize(12)
    assertThat(ok(get("/_feeds/fetch/comments")).list("entries")).hasSize(1)
    assertThat(ok(get("/_feeds/fetch/threads")).list("entries")).hasSize(1)
    assertThat(get("/_feeds/fetch/nope").code).isEqualTo(404)
    assertThat(get("/_feeds/fetch/taggings?shard=1").code).isEqualTo(400)

    // pause holds rows back, resume releases them
    ok(post("/_feeds/taggings/pause", user = "root"))
    tag("$pc/s0", "paused")
    assertThat(publisher.publishAll("taggings")).isEqualTo(0)
    val st = ok(get("/_feeds/status")).list("feeds").first { it["feed"] == "taggings" }
    assertThat(st["pending"]).isEqualTo(1.0)
    assertThat(st["publishing_enabled"]).isEqualTo(false)
    ok(post("/_feeds/taggings/resume", user = "root"))
    assertThat(publisher.publishAll("taggings")).isEqualTo(1)
  }

  @Test
  fun `summary rolls a target up`() {
    rule("AR-8", "ANY_APPROVAL")
    tag("$pc/90", "urgent")
    ok(post("/api/threads", mapOf("title" to "t", "target" to "$pc/90")))
    request("$pc/90", "AR-8")
    val s = ok(get("/api/summary?target=$pc/90"))
    assertThat(s["known"]).isEqualTo(true)
    assertThat(s.list("tags")).hasSize(1)
    assertThat(s.list("threads")).hasSize(1)
    assertThat(s.list("approvables")).hasSize(1)
    assertThat(s.list("logs").size).isGreaterThanOrEqualTo(3)
    assertThat(ok(get("/api/summary?target=$pc/none"))["known"]).isEqualTo(false)
    assertThat(ok(get("/api/info")).list("requirements")).hasSize(7)
  }

  // --------------------------------------------------------------------------- ownership

  private val rl = "sentinel/rules"
  private val sig = "sentinel/signals"
  private val hook = "sentinel/hooks"

  private fun ownershipFixture() {
    for (e in listOf("signals", "rules", "hooks")) ok(post("/api/entity-types", mapOf("source_service" to "sentinel", "source_entity" to e)))
    member("trust-infra", "bob"); member("trust-infra", "dave"); member("trust-ops", "olga"); member("trust-ds", "dana")
  }

  private fun claim(target: String, aspect: String, owner: String, user: String, role: String? = null, at: String? = null) =
    ok(post("/api/ownership/claim", mapOf("target" to target, "aspect" to aspect, "owner" to owner, "role" to role, "effective_at" to at), user))

  private fun cell(target: String, aspect: String, q: String = "") = ok(get("/api/ownership?target=$target$q")).list("cells").first { it["aspect"] == aspect }

  @Suppress("UNCHECKED_CAST")
  private fun owners(cell: Map<String, Any?>) = (cell["owners"] as List<Map<String, Any?>>).map { it["owner"] }

  private fun edge(from: String, to: String, kind: String = "FEEDS") = ok(post("/api/edges", mapOf("from" to from, "to" to to, "kind" to kind)))

  @Test
  fun `ownership is per aspect, effective-dated, and contested rather than refused`() {
    ownershipFixture()
    for (a in listOf("availability", "efficacy")) ok(post("/api/ownership/aspects", mapOf("type" to rl, "aspect" to a)))
    // you can only speak for a group you are in
    assertThat(post("/api/ownership/claim", mapOf("target" to "$rl/R1", "aspect" to "availability", "owner" to "trust-infra"), "alice").code).isEqualTo(403)
    val first = claim("$rl/R1", "availability", "trust-infra", "bob", at = "2026-11-01T00:00:00Z")
    assertThat(first["changed"]).isEqualTo(true)
    assertThat(cell("$rl/R1", "availability")["status"]).isEqualTo("OWNED")
    // before it was effective: the aspect is expected of the type, and nobody has it
    assertThat(cell("$rl/R1", "availability", "&at=2026-10-31T00:00:00Z")["status"]).isEqualTo("UNOWNED")
    // claiming what you already hold is an affirmation, not a second claim
    val again = claim("$rl/R1", "availability", "trust-infra", "bob")
    assertThat(again["changed"]).isEqualTo(false)
    assertThat(again["operation"]).isEqualTo("AFFIRM")

    // a second OWNER is recorded, and the cell says so
    val before = java.time.Instant.ofEpochMilli(clock.millis()).toString()
    clock.add(java.time.Duration.ofMinutes(5))
    assertThat(claim("$rl/R1", "availability", "trust-ops", "olga").obj("cell")["status"]).isEqualTo("CONTESTED")
    claim("$rl/R1", "availability", "trust-ds", "dana", role = "STAKEHOLDER")
    val contested = cell("$rl/R1", "availability")
    assertThat(owners(contested)).containsExactly("trust-infra", "trust-ops")
    assertThat(contested.let { @Suppress("UNCHECKED_CAST") (it["stakeholders"] as List<Map<String, Any?>>).map { s -> s["owner"] } }).containsExactly("trust-ds")
    assertThat(cell("$rl/R1", "availability", "&known_at=$before")["status"]).isEqualTo("OWNED")
    assertThat(ok(get("/api/logs?target=$rl/R1&source=tlc.ownership")).list("entries").map { it["message"] as String }).anyMatch { it.contains("CONTESTED") }

    ok(post("/api/ownership/release", mapOf("target" to "$rl/R1", "aspect" to "availability", "owner" to "trust-ops"), "olga"))
    assertThat(cell("$rl/R1", "availability")["status"]).isEqualTo("OWNED")
    // transfer is the giver's to make, and leaves no gap
    val xfer = mapOf("target" to "$rl/R1", "aspect" to "availability", "from" to "trust-infra", "to" to "trust-ops")
    assertThat(post("/api/ownership/transfer", xfer, "olga").code).isEqualTo(403)
    ok(post("/api/ownership/transfer", xfer, "bob"))
    assertThat(owners(cell("$rl/R1", "availability"))).containsExactly("trust-ops")

    // type-wide default, overridden per record
    claim("$rl/*", "efficacy", "trust-ds", "dana")
    ok(post("/api/entities", mapOf("target" to "$rl/R2")))
    assertThat(cell("$rl/R2", "efficacy")).containsEntry("status", "OWNED").containsEntry("source", "TYPE")
    claim("$rl/R2", "efficacy", "trust-ops", "olga")
    val over = cell("$rl/R2", "efficacy")
    assertThat(over).containsEntry("status", "OWNED").containsEntry("source", "DIRECT")
    assertThat(owners(over)).containsExactly("trust-ops")

    // nobody re-affirmed for 91 days
    clock.add(java.time.Duration.ofDays(91))
    assertThat(cell("$rl/R1", "availability")["status"]).isEqualTo("LAPSED")
    assertThat(ok(post("/api/ownership/affirm", mapOf("owner" to "trust-ops"), "olga"))["affirmed"]).isEqualTo(2.0)
    assertThat(cell("$rl/R1", "availability")["status"]).isEqualTo("OWNED")

    // R1.availability OWNED direct, R1.efficacy LAPSED via type, R2.availability UNOWNED, R2.efficacy OWNED direct
    val cov = ok(get("/api/ownership/coverage?type=$rl"))
    assertThat(cov.obj("total")).containsEntry("cells", 4.0).containsEntry("owned", 2.0).containsEntry("lapsed", 1.0).containsEntry("unowned", 1.0).containsEntry("contested", 0.0)
      .containsEntry("direct", 2.0).containsEntry("inherited", 1.0).containsEntry("pct_owned", 50.0)
    assertThat(cov.list("rows").map { "${it["aspect"]} ${it["owned"]}/${it["cells"]}" }).containsExactly("availability 1.0/2.0", "efficacy 1.0/2.0")
    assertThat(cov.list("owners").first()).containsEntry("owner", "trust-ops").containsEntry("owned", 2.0)
    assertThat(ok(get("/api/ownership/gaps?type=$rl")).list("cells").map { "${it["target"]} ${it["aspect"]} ${it["status"]}" })
      .containsExactlyInAnyOrder("$rl/R1 efficacy LAPSED", "$rl/R2 availability UNOWNED")
    assertThat(ok(get("/api/ownership/owned-by?owner=trust-ds")).list("cells").map { it["target"] }).contains("$rl/R1")
    assertThat(ok(get("/api/ownership/history?target=$rl/R1&aspect=availability")).list("ownerships").map { it["operation"] })
      .containsExactly("AFFIRM", "CLAIM", "RELEASE", "RELEASE", "CLAIM", "CLAIM", "AFFIRM", "CLAIM")
    assertThat(ok(get("/api/summary?target=$rl/R1")).list("ownership")).hasSize(2)
    // a claim on an aspect nobody declared shows on the record but stays out of the denominator
    claim("$rl/R1", "oncall-rotation", "trust-infra", "bob")
    assertThat(ok(get("/api/ownership?target=$rl/R1")).list("cells")).hasSize(3)
    assertThat(ok(get("/api/ownership/coverage?type=$rl")).obj("total")["cells"]).isEqualTo(4.0)
    assertThat(call("DELETE", "/api/ownership/aspects?type=$rl&aspect=efficacy", user = "root").code).isEqualTo(200)
    assertThat(ok(get("/api/ownership/coverage?type=$rl")).obj("total")["cells"]).isEqualTo(2.0)
    publisher.publishAll()
    assertThat(ok(get("/_feeds/fetch/ownerships")).list("entries").size).isGreaterThanOrEqualTo(10)
  }

  @Test
  fun `approval rules resolve consenting groups from ownership over the dependency graph`() {
    ownershipFixture()
    val s = "$sig/velocity"; val r1 = "$rl/R1"; val r2 = "$rl/R2"; val h = "$hook/freeze"
    val e = edge(s, r1)
    assertThat(edge(s, r1)["token"]).isEqualTo(e["token"]) // idempotent
    edge(s, r2); edge(r1, h, "HOOKS")
    claim(s, "availability", "trust-ds", "dana")
    claim(r1, "availability", "trust-infra", "bob")
    claim(r1, "disposition", "trust-ops", "olga"); claim(r2, "disposition", "trust-ops", "olga"); claim(h, "disposition", "trust-ops", "olga")
    claim(r1, "efficacy", "trust-ds", "dana", role = "STAKEHOLDER")

    val down = ok(get("/api/ownership/affected?target=$s"))
    assertThat(down.list("nodes").map { "${it["target"]} ${it["depth"]}" }).containsExactly("$s 0.0", "$r1 1.0", "$r2 1.0", "$h 2.0")
    assertThat(down.list("parties").map { "${it["owner"]} ${it["nearest"]}" }).containsExactly("trust-ds 0.0", "trust-infra 1.0", "trust-ops 1.0")
    assertThat(ok(get("/api/ownership/affected?target=$h&direction=up")).list("nodes").map { it["target"] }).containsExactly(h, r1, s)

    ok(call("PUT", "/api/directory/trust-ops/contact?slack_channel=%23ops-review", user = "root"))
    ok(call("PUT", "/api/directory/trust-ds/contact?email=ds@example.com", user = "root"))
    val aspects = listOf(
      mapOf("aspect" to "availability", "mode" to "CONSENT", "scope" to "TARGET"),
      mapOf("aspect" to "disposition", "mode" to "CONSENT", "scope" to "DOWNSTREAM"),
      mapOf("aspect" to "efficacy", "mode" to "NOTIFY"),
    )
    ok(post("/api/approval-rules", mapOf("token" to "AR-OWN", "description" to "disable a rule", "requirement" to "ALL_GROUPS", "aspects" to aspects), "root"))

    val view = ok(post("/api/approvables", mapOf("target" to r1, "rule" to "AR-OWN", "context" to "disable R1"), "alice"))
    val ap = view.obj("approvable")["token"] as String
    assertThat(view.list("parties").map { "${it["directory_group"]} ${it["mode"]} ${it["status"]}" }).containsExactly("trust-infra CONSENT OWNED", "trust-ops CONSENT OWNED", "trust-ds NOTIFY STAKEHOLDER")
    assertThat(view.list("parties").first { it["directory_group"] == "trust-ops" }["via"] as List<*>).containsExactly(r1, h)
    assertThat(post("/api/approvables/$ap/approve", user = "dana").code).isEqualTo(403) // told, not asked
    ok(post("/api/approvables/$ap/approve", user = "bob"))
    assertThat(state(ap)).isEqualTo("PENDING")
    ok(post("/api/approvables/$ap/approve", user = "olga"))
    assertThat(state(ap)).isEqualTo("APPROVED")
    val told = ok(get("/api/notifications?approvable=$ap")).list("notifications").map { "${it["destination"]} ${it["event"]}" }
    assertThat(told).contains("#ops-review REQUESTED", "ds@example.com REQUESTED", "#ops-review APPROVED")

    // the parties were frozen at filing: ownership moving afterwards doesn't reach back
    ok(post("/api/ownership/transfer", mapOf("target" to r1, "aspect" to "availability", "from" to "trust-infra", "to" to "trust-ds"), "bob"))
    assertThat(ok(get("/api/approvables/$ap")).list("parties").map { it["directory_group"] }).contains("trust-infra")
    assertThat(state(ap)).isEqualTo("APPROVED")
    val later = ok(post("/api/approvables", mapOf("target" to r1, "rule" to "AR-OWN"), "alice"))
    assertThat(later.list("parties").filter { it["mode"] == "CONSENT" }.map { it["directory_group"] }).containsExactly("trust-ds", "trust-ops")

    // an unowned CONSENT aspect stops the filing, unless the rule names a fallback
    val unowned = post("/api/approvables", mapOf("target" to r2, "rule" to "AR-OWN"), "alice")
    assertThat(unowned.code).isEqualTo(409)
    assertThat(unowned.text).contains("nobody owns 'availability'")
    ok(call("PATCH", "/api/approval-rules/AR-OWN?force=true", mapOf("aspects" to listOf(aspects[0] + ("fallback_group" to "trust-infra"), aspects[1], aspects[2])), "root"))
    val fb = ok(post("/api/approvables", mapOf("target" to r2, "rule" to "AR-OWN"), "alice"))
    assertThat(fb.list("parties").map { "${it["directory_group"]} ${it["status"]} ${it["source"]}" }).containsExactly("trust-infra UNOWNED FALLBACK", "trust-ops OWNED DIRECT")

    // the action allowlist: AR-OWN is "disable a rule", so it is for rules only, and rules only take listed rules
    ok(call("PATCH", "/api/approval-rules/AR-OWN", mapOf("applies_to" to listOf(rl)), "root")) // no force needed with approvables open
    assertThat(ok(get("/api/approval-rules/AR-OWN"))["applies_to"] as List<*>).containsExactly(rl)
    val wrongType = post("/api/approvables", mapOf("target" to s, "rule" to "AR-OWN"), "alice")
    assertThat(wrongType.code).isEqualTo(409)
    assertThat(wrongType.text).contains("applies to $rl, not $sig")
    ok(post("/api/approval-rules", mapOf("token" to "AR-ANY", "description" to "generic", "requirement" to "ALL_GROUPS", "groups" to listOf(mapOf("group" to "ENG", "directory_group" to "trust-infra"))), "root"))
    val unlisted = post("/api/approvables", mapOf("target" to r1, "rule" to "AR-ANY"), "alice")
    assertThat(unlisted.code).isEqualTo(409)
    assertThat(unlisted.text).contains("$rl only takes AR-OWN")
    ok(post("/api/approvables", mapOf("target" to s, "rule" to "AR-ANY"), "alice")) // signals are still open to any unrestricted rule
    ok(post("/api/approvables", mapOf("target" to r1, "rule" to "AR-OWN"), "alice"))
    assertThat(post("/api/approval-rules", mapOf("token" to "AR-BAD", "description" to "x", "requirement" to "ALL_GROUPS", "aspects" to aspects, "applies_to" to listOf("nope/nothing")), "root").code).isEqualTo(404)

    ok(call("DELETE", "/api/edges?from=$r1&to=$h", user = "alice"))
    assertThat(ok(get("/api/edges?target=$r1")).list("edges")).hasSize(1)
  }

  @Test
  fun `declared impacts gate who is asked, told or skipped`() {
    ownershipFixture()
    val r1 = "$rl/R1"
    ok(post("/api/ownership/aspects", mapOf("type" to rl, "aspect" to "efficacy", "description" to "catching enough of the fraud that exists at a low enough false-positive cost"), "root"))
    claim(r1, "availability", "trust-infra", "bob"); claim(r1, "disposition", "trust-ops", "olga"); claim(r1, "efficacy", "trust-ds", "dana")
    assertThat(cell(r1, "efficacy")["description"] as String).startsWith("catching enough")
    ok(call("PUT", "/api/directory/trust-ops/contact?slack_channel=%23ops-review", user = "root"))
    val aspects = listOf(
      mapOf("aspect" to "availability", "threshold" to 0),
      mapOf("aspect" to "disposition", "threshold" to 50, "unit" to "flags/day"),
      mapOf("aspect" to "efficacy"), // no threshold: always asked
    )
    ok(post("/api/approval-rules", mapOf("token" to "AR-THR", "description" to "change a threshold", "requirement" to "ALL_GROUPS", "aspects" to aspects), "root"))
    assertThat(post("/api/approval-rules", mapOf("token" to "AR-NEG", "description" to "x", "requirement" to "ALL_GROUPS", "aspects" to listOf(mapOf("aspect" to "a", "threshold" to -1))), "root").code).isEqualTo(400)
    fun file(impacts: List<Map<String, Any?>>?, service: String? = "sentinel", rule: String = "AR-THR") =
      call("POST", "/api/approvables", mapOf("target" to r1, "rule" to rule, "impacts" to impacts), "alice", service)
    fun asked(v: Res) = v.list("parties").filter { it["mode"] == "CONSENT" }.map { it["directory_group"] }
    fun told(v: Res) = v.list("parties").filter { it["mode"] == "NOTIFY" }.map { it["directory_group"] }

    // nothing declared = unknown = everyone asked
    assertThat(asked(ok(file(null)))).containsExactly("trust-infra", "trust-ops", "trust-ds")
    // a human can't declare impacts: that would be choosing your own approvers
    assertThat(file(listOf(mapOf("aspect" to "availability", "magnitude" to 0)), service = null).code).isEqualTo(400)

    val small = ok(file(listOf(mapOf("aspect" to "availability", "magnitude" to 0), mapOf("aspect" to "disposition", "magnitude" to -12, "unit" to "flags/day", "note" to "30d backtest"), mapOf("aspect" to "latency", "magnitude" to 3))))
    assertThat(asked(small)).containsExactly("trust-ds")
    assertThat(told(small)).containsExactly("trust-infra", "trust-ops")
    assertThat(small.list("impacts").map { "${it["aspect"]} ${it["rule_mode"]} ${it["effect"]}" }).containsExactly("availability CONSENT NOTIFY", "disposition CONSENT NOTIFY", "latency null NONE")
    val ap = small.obj("approvable")["token"] as String
    assertThat(post("/api/approvables/$ap/approve", user = "olga").code).isEqualTo(403)
    val fyi = ok(get("/api/notifications?approvable=$ap")).list("notifications").first { it["destination"] == "#ops-review" }["body"] as String
    assertThat(fyi).contains("declared disposition=-12 flags/day")
    ok(post("/api/approvables/$ap/approve", user = "dana"))
    assertThat(state(ap)).isEqualTo("APPROVED")

    // the sign doesn't matter, the size does; unknown is asked; the unit has to match
    val big = ok(file(listOf(mapOf("aspect" to "availability", "magnitude" to 0), mapOf("aspect" to "disposition", "magnitude" to -340, "unit" to "flags/day"))))
    assertThat(asked(big)).containsExactly("trust-ops", "trust-ds")
    assertThat(asked(ok(file(listOf(mapOf("aspect" to "availability", "magnitude" to 0), mapOf("aspect" to "disposition", "magnitude" to null)))))).containsExactly("trust-ops", "trust-ds")
    val unit = file(listOf(mapOf("aspect" to "disposition", "magnitude" to 1, "unit" to "flags/hour")))
    assertThat(unit.code).isEqualTo(400)
    assertThat(unit.text).contains("must be in 'flags/day'")

    // a NOTIFY aspect below its threshold is skipped; with nobody left to ask it is approved at filing
    ok(post("/api/approval-rules", mapOf("token" to "AR-TINY", "description" to "retune", "requirement" to "ALL_GROUPS", "aspects" to listOf(mapOf("aspect" to "disposition", "threshold" to 50), mapOf("aspect" to "efficacy", "mode" to "NOTIFY", "threshold" to 1))), "root"))
    val none = ok(file(listOf(mapOf("aspect" to "disposition", "magnitude" to 2), mapOf("aspect" to "efficacy", "magnitude" to 0)), rule = "AR-TINY"))
    assertThat(none.obj("approvable")["approval_state"]).isEqualTo("APPROVED")
    assertThat(none.list("parties").map { "${it["directory_group"]} ${it["mode"]}" }).containsExactly("trust-ops NOTIFY")
    assertThat(none.list("impacts").map { "${it["aspect"]} ${it["effect"]}" }).containsExactly("disposition NOTIFY", "efficacy SKIPPED")
  }

  @Test
  fun `break-glass acts first and is ratified, or reverts`() {
    ownershipFixture()
    val s = "$sig/velocity"; val r1 = "$rl/R1"
    edge(s, r1)
    claim(s, "availability", "trust-ds", "dana")
    claim(r1, "availability", "trust-infra", "bob")
    claim(r1, "disposition", "trust-ops", "olga")
    ok(post("/api/approval-rules", mapOf("token" to "AR-BG", "description" to "detach a signal", "requirement" to "ALL_GROUPS", "aspects" to listOf(mapOf("aspect" to "disposition", "mode" to "CONSENT", "scope" to "DOWNSTREAM"))), "root"))
    fun pull(user: String, ttl: Int? = null) = post("/api/approvables", mapOf("target" to s, "rule" to "AR-BG", "mode" to "RATIFY", "context" to "signal is stale; detached", "ttl_seconds" to ttl), user)

    // owning nothing here, alice can't pull the cord; bob owns availability of a rule built on the signal
    val no = pull("alice")
    assertThat(no.code).isEqualTo(403)
    assertThat(no.text).contains("trust-ds, trust-infra, trust-ops")
    val a = ok(pull("bob")).obj("approvable")
    val ap = a["token"] as String
    assertThat(a).containsEntry("approval_state", "ACTED").containsEntry("mode", "RATIFY").containsEntry("acting_group", "trust-infra")
    assertThat(a["expires_at"]).isEqualTo("2026-11-10T16:00:00Z")
    assertThat(post("/api/approvables/$ap/approve", user = "bob").code).isEqualTo(403) // can't ratify your own
    assertThat(post("/api/approvables/$ap/execute", user = "bob").code).isEqualTo(409) // it already happened
    assertThat(post("/api/approvables/$ap/decline", user = "olga").code).isEqualTo(403) // others withhold; they don't flip it back
    assertThat(ok(get("/api/approvables?awaiting=olga")).list("approvables").map { it["token"] }).containsExactly(ap)
    ok(post("/api/approvables/$ap/approve", user = "olga"))
    assertThat(state(ap)).isEqualTo("RATIFIED")
    assertThat(approvals.expireRatifications()).isEqualTo(0)

    // not ratified inside the TTL: REVERTED, and the feed tells the source service to undo it
    val ap2 = ok(pull("bob", 600)).obj("approvable")["token"] as String
    clock.add(java.time.Duration.ofMinutes(9))
    assertThat(approvals.expireRatifications()).isEqualTo(0)
    clock.add(java.time.Duration.ofMinutes(2))
    assertThat(approvals.expireRatifications()).isEqualTo(1)
    val gone = ok(get("/api/approvables/$ap2")).obj("approvable")
    assertThat(gone).containsEntry("approval_state", "REVERTED").containsEntry("declined_by", "tlc:ttl")
    assertThat(post("/api/approvables/$ap2/approve", user = "olga").code).isEqualTo(409)
    publisher.publishAll()
    assertThat(ok(get("/_feeds/fetch/approvables")).list("entries").map { "${it["token"]} ${it["approval_state"]}" }).contains("$ap RATIFIED", "$ap2 REVERTED")

    // the side that pulled it may put it back early
    val ap3 = ok(pull("bob")).obj("approvable")["token"] as String
    ok(post("/api/approvables/$ap3/decline", mapOf("reason" to "false alarm"), "dave"))
    assertThat(state(ap3)).isEqualTo("REVERTED")
    assertThat(ok(get("/api/logs?target=$s&source=tlc.approvals")).list("entries").map { it["message"] as String }).anyMatch { it.contains("BREAK-GLASS by bob (trust-infra)") }
  }
}
