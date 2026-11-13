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
import misk.web.Put
import misk.web.QueryParam
import misk.web.RequestBody
import misk.web.RequestContentType
import misk.web.ResponseContentType
import misk.web.actions.WebAction

// =============================================================================== directory

data class GroupContactDto(val directory_group: String, val slack_channel: String?, val email: String?)
data class MembershipDto(val directory_group: String, val ldap: String, val is_admin: Boolean, val updated_at: String)

/**
 * Stand-in for the real group directory (Okta, Google Groups, LDAP): who is in which group, and who administers
 * it. Everything else only goes through [memberOf], so swapping in a real directory client is a
 * one-class change. See docs/approvals.md.
 */
@Singleton
class Directory @Inject constructor(private val db: Db, private val clock: Clock) {
  fun put(group: String, ldap: String, admin: Boolean): MembershipDto = db.tx { c ->
    val now = clock.millis()
    c.exec(
      """
      INSERT INTO directory_memberships (created_at_ms, updated_at_ms, directory_group, ldap, is_admin) VALUES (?,?,?,?,?)
      ON DUPLICATE KEY UPDATE is_admin = VALUES(is_admin), updated_at_ms = VALUES(updated_at_ms), version = version + 1
      """.trimIndent(),
      listOf(now, now, group, ldap.lowercase(), admin),
    )
    MembershipDto(group, ldap.lowercase(), admin, iso(now))
  }

  fun remove(group: String, ldap: String): Boolean = db.tx { c -> c.exec("DELETE FROM directory_memberships WHERE directory_group = ? AND ldap = ?", listOf(group, ldap.lowercase())) > 0 }

  fun list(group: String?): List<MembershipDto> = db.tx { c ->
    c.select("SELECT * FROM directory_memberships ${if (group != null) "WHERE directory_group = ?" else ""} ORDER BY directory_group, ldap", listOfNotNull(group)) {
      MembershipDto(it.getString("directory_group"), it.getString("ldap"), it.getBoolean("is_admin"), iso(it.getLong("updated_at_ms")))
    }
  }

  fun groupsOf(ldap: String): List<String> = db.tx { c -> c.select("SELECT directory_group FROM directory_memberships WHERE ldap = ? ORDER BY directory_group", listOf(ldap)) { it.getString(1) } }

  /** (slack_channel, email) for a group; how ownership-resolved parties get told. */
  fun contact(c: Connection, group: String): Pair<String?, String?> =
    c.select("SELECT slack_channel, email FROM directory_groups WHERE directory_group = ?", listOf(group)) { it.getString(1) to it.getString(2) }.firstOrNull() ?: (null to null)

  fun setContact(group: String, slack: String?, email: String?): GroupContactDto = db.tx { c ->
    val now = clock.millis()
    c.exec(
      """
      INSERT INTO directory_groups (created_at_ms, updated_at_ms, directory_group, slack_channel, email) VALUES (?,?,?,?,?)
      ON DUPLICATE KEY UPDATE slack_channel = VALUES(slack_channel), email = VALUES(email), updated_at_ms = VALUES(updated_at_ms), version = version + 1
      """.trimIndent(),
      listOf(now, now, group, slack, email),
    )
    GroupContactDto(group, slack, email)
  }

  fun contacts(): List<GroupContactDto> = db.tx { c -> c.select("SELECT * FROM directory_groups ORDER BY directory_group") { GroupContactDto(it.getString("directory_group"), it.getString("slack_channel"), it.getString("email")) } }

  /** directory_group -> is_admin, for one person. */
  fun memberOf(c: Connection, ldap: String): Map<String, Boolean> =
    c.select("SELECT directory_group, is_admin FROM directory_memberships WHERE ldap = ?", listOf(ldap)) { it.getString(1) to it.getBoolean(2) }.toMap()
}

// ================================================================================== rules

enum class Requirement(val needsGroups: Boolean, val notifyOnly: Boolean, val doc: String) {
  ANY_APPROVAL(false, false, "one approval from anyone eligible, the requester included"),
  ANY_NON_SELF_APPROVAL(false, false, "one approval from anyone eligible other than the requester"),
  MIN_GROUPS(true, false, "approvals from at least min_groups distinct groups (default 2), one distinct person per group"),
  MAJORITY_OF_GROUPS(true, false, "approvals from more than half of the rule's groups, one distinct person per group"),
  ALL_GROUPS(true, false, "an approval from every group, one distinct person per group"),
  SLACK_NOTIFICATION(false, true, "no approval: the groups' Slack channels are told, then it is APPROVED"),
  EMAIL_NOTIFICATION(false, true, "no approval: the groups' email addresses are told, then it is APPROVED");

  companion object {
    fun parse(s: String?): Requirement = entries.firstOrNull { it.name == s?.trim()?.uppercase() }
      ?: throw BadRequestException("bad requirement '$s' (have: ${entries.joinToString()})")
  }
}

data class RuleGroupDto(
  /** Label within the rule, e.g. "ENG". */
  val group: String,
  /** Directory group whose members may approve for it, e.g. "trust-infra". */
  val directory_group: String,
  val admins_only: Boolean = false,
  val slack_channel: String? = null,
  val email: String? = null,
)

data class RuleDto(
  val token: String,
  val description: String,
  val requirement: String,
  val min_groups: Int?,
  val groups: List<RuleGroupDto>,
  val created_by: String,
  val open_approvables: Long,
  val created_at: String,
  val updated_at: String,
  val deleted_at: String?,
  val version: Int,
  /** Ownership-resolved parties: see docs/ownership.md. Resolved per approvable, at filing. */
  val aspects: List<RuleAspectDto> = listOf(),
  /**
   * `service/entity` types this rule may be filed against; empty = any type that no rule has listed.
   * Listing a type on any live rule closes it: only rules that list it may then be filed against it.
   */
  val applies_to: List<String> = listOf(),
  /**
   * What a new revision of an approvable does to the approvals already on it. DISMISS: they are
   * withdrawn and must be given again. KEEP: they stand, each still showing the revision it was given at.
   */
  val on_revision: String = "DISMISS",
)

private val RULE_TOKEN_RE = Regex("AR-[A-Za-z0-9][A-Za-z0-9_-]{0,27}")
private val GROUP_RE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
/** A revision label is the source service's own: a counter, a content hash, a commit. TLC orders them by arrival. */
private val REVISION_RE = Regex("[A-Za-z0-9][A-Za-z0-9_.+:-]{0,63}")
private fun revisionLabel(s: String?): String? = s?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }?.also { if (!REVISION_RE.matches(it)) throw BadRequestException("revision must match ${REVISION_RE.pattern}") }
private fun onRevision(s: String?): String? = s?.trim()?.uppercase()?.also { if (it !in setOf("DISMISS", "KEEP")) throw BadRequestException("on_revision is DISMISS or KEEP") }

// ================================================================================= engine

data class GroupEval(val group: String, val directory_group: String, val admins_only: Boolean, /** The approver this group is matched to. */ val satisfied_by: String?, /** Everyone whose live approval could count for it. */ val candidates: List<String>)

data class Evaluation(
  val requirement: String,
  val satisfied: Boolean,
  /** Group requirements: distinct groups needed / matched. Otherwise approvals needed (0|1) / counted. */
  val needed: Int,
  val have: Int,
  val groups: List<GroupEval>,
  val summary: String,
)

object ApprovalEngine {
  /**
   * [approvals]: live approver -> rule groups they were entitled to approve for when they approved.
   * Self-approvals are refused at write time except under ANY_APPROVAL, and filtered again here so
   * a rule tightened later doesn't count an old one.
   *
   * Group requirements need *distinct people*: one person in both ENG and RISK satisfies one group,
   * not two. Which one is a maximum bipartite matching (groups x approvers); greedy assignment
   * under-counts, e.g. A∈{ENG,RISK}, B∈{ENG} is 2 groups only if A takes RISK.
   */
  fun evaluate(req: Requirement, minGroups: Int?, groups: List<RuleGroupDto>, approvals: Map<String, Set<String>>, requestedBy: String): Evaluation {
    val counted = if (req == Requirement.ANY_APPROVAL) approvals else approvals.filterKeys { it != requestedBy }
    val names = groups.map { it.group }
    val candidates = names.associateWith { g -> counted.filterValues { g in it }.keys.sorted() }
    if (req.notifyOnly) return Evaluation(req.name, true, 0, 0, eval(groups, mapOf(), candidates), "notification only; no approval needed")
    if (!req.needsGroups) {
      val ok = counted.isNotEmpty()
      return Evaluation(req.name, ok, 1, minOf(counted.size, 1), eval(groups, mapOf(), candidates), if (ok) "approved by ${counted.keys.sorted().joinToString()}" else "needs 1 approval" + (if (req == Requirement.ANY_NON_SELF_APPROVAL) " from someone other than $requestedBy" else "") + (if (groups.isNotEmpty()) " (a member of: ${names.joinToString()})" else ""))
    }
    val needed = when (req) {
      Requirement.MIN_GROUPS -> minGroups ?: 2
      Requirement.MAJORITY_OF_GROUPS -> groups.size / 2 + 1
      else -> groups.size
    }
    val match = matching(names, candidates)
    val ok = match.size >= needed
    val missing = names.filter { it !in match }
    return Evaluation(req.name, ok, needed, match.size, eval(groups, match, candidates), if (ok) "${match.size}/${groups.size} groups approved (needed $needed)" else "${match.size} of $needed needed groups approved; still open: ${missing.joinToString()}")
  }

  private fun eval(groups: List<RuleGroupDto>, match: Map<String, String>, candidates: Map<String, List<String>>) =
    groups.map { GroupEval(it.group, it.directory_group, it.admins_only, match[it.group], candidates[it.group].orEmpty()) }

  /** Kuhn's augmenting-path maximum matching. group -> approver. */
  fun matching(groups: List<String>, candidates: Map<String, List<String>>): Map<String, String> {
    val owner = HashMap<String, String>() // approver -> group
    fun assign(g: String, seen: MutableSet<String>): Boolean {
      for (a in candidates[g].orEmpty()) {
        if (!seen.add(a)) continue
        val cur = owner[a]
        if (cur == null || assign(cur, seen)) { owner[a] = g; return true }
      }
      return false
    }
    for (g in groups) assign(g, HashSet())
    return owner.entries.associate { (a, g) -> g to a }.toSortedMap(compareBy { groups.indexOf(it) })
  }
}

// ============================================================================= approvables

data class ApprovableDto(
  val token: String,
  val target: String,
  val approval_context: String?,
  val approval_rule_token: String,
  val approval_state: String,
  val requested_by: String,
  val requested_via_service: String?,
  val client_id: String?,
  val approved_at: String?,
  val executed_at: String?,
  val executed_by: String?,
  val declined_at: String?,
  val declined_by: String?,
  val decline_reason: String?,
  val created_at: String,
  val updated_at: String,
  val version: Int,
  val feed_sync_id: Long?,
  val shard: Int,
  /** APPROVE: ask first. RATIFY: break-glass, already done; reverts at expires_at unless ratified. */
  val mode: String = "APPROVE",
  val expires_at: String? = null,
  /** RATIFY: the owning group the requester acted for. */
  val acting_group: String? = null,
  /** Which revision of the target is up for approval, in the source service's own labels (`…/1234@3`). null: the service never said. */
  val revision: String? = null,
  /** 1 at filing, +1 per revise. Approvals, and what they count for, are per revision. */
  val revision_seq: Int = 1,
)

data class ApprovalDto(
  val token: String, val approvable_token: String, val approved_by: String, val approved_at: String, val unapproved_at: String?,
  /** Rule groups the approver could approve for at that moment (snapshot). */ val via_groups: List<String>, val comment: String?,
  /** The revision that was approved. */
  val revision: String? = null,
  val revision_seq: Int = 1,
  /** Ended by a later revision (the rule's on_revision is DISMISS), not by the approver. */
  val superseded: Boolean = false,
  /** Still counted, but given at an earlier revision than the current one (on_revision is KEEP). */
  val stale: Boolean = false,
)
/** One revision of an approvable: what was put up for approval, by whom, and how many approvals it displaced. */
data class RevisionDto(val seq: Int, val revision: String?, val context: String?, val revised_by: String, val via_service: String?, val created_at: String, val dismissed: Int)
data class CanApprove(val allowed: Boolean, val reason: String, val via_groups: List<String>)
/** What the source service says an action does to one aspect. `magnitude` null = it does not know. */
data class ImpactReq(val aspect: String?, val magnitude: Double? = null, val unit: String? = null, val note: String? = null)

/** One aspect of one approvable: what was declared, what the rule's threshold was, and what that did. Frozen at filing. */
data class ImpactDto(
  val aspect: String,
  /** false = the rule names this aspect but the filer said nothing about it (treated as unknown). */
  val declared: Boolean,
  val magnitude: Double?,
  val unit: String?,
  val note: String?,
  /** What the rule asks for this aspect at full impact; null = the rule does not name it. */
  val rule_mode: String?,
  val threshold: Double?,
  /** CONSENT | NOTIFY | SKIPPED, or NONE when the rule does not name the aspect. */
  val effect: String,
  val reason: String,
)

data class ApprovableView(val approvable: ApprovableDto, /** `groups` here = the rule's own groups + this approvable's CONSENT parties. */ val rule: RuleDto, val approvals: List<ApprovalDto>, val evaluation: Evaluation, /** For the calling human. */ val you: CanApprove?, /** Groups pulled in by ownership, frozen at filing. */ val parties: List<PartyDto> = listOf(), /** Declared impacts and what they did to each rule aspect, frozen at filing. */ val impacts: List<ImpactDto> = listOf(), /** Every revision this approvable has been through, oldest first; the last is the one being decided. */ val revisions: List<RevisionDto> = listOf(), /** Discussion of this request: threads whose target is the approvable. Each thread and comment carries the revision it was written at. */ val threads: List<ThreadDto> = listOf())
data class ActionResult(val changed: Boolean, val approvable: ApprovableDto, val evaluation: Evaluation)

private val OPEN = "('PENDING','APPROVED','ACTED')"
private val LIVE = setOf("PENDING", "APPROVED", "ACTED")

@Singleton
class ApprovalStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: TlcConfig,
  private val entities: EntityStore,
  private val tlcers: Tlcers,
  private val logs: LogStore,
  private val directory: Directory,
  private val outbox: Outbox,
  private val ownership: OwnershipStore,
) {
  // ------------------------------------------------------------------------------- rules

  private fun aspectsOf(c: Connection, token: String): List<RuleAspectDto> =
    c.select("SELECT * FROM approval_rule_aspects WHERE approval_rule_token = ? ORDER BY id", listOf(token)) {
      RuleAspectDto(it.getString("aspect"), it.getString("mode"), it.getString("scope"), it.getString("fallback_group"), it.getDouble("threshold").takeUnless { _ -> it.wasNull() }, it.getString("unit"))
    }

  private fun normAspects(aspects: List<RuleAspectDto>): List<RuleAspectDto> {
    val out = aspects.map { a ->
      val n = RuleAspectDto(a.aspect.trim().lowercase(), a.mode.trim().uppercase(), a.scope.trim().uppercase(), a.fallback_group?.trim()?.takeIf { it.isNotEmpty() }, a.threshold, a.unit?.trim()?.takeIf { it.isNotEmpty() })
      if (n.threshold != null && (n.threshold.isNaN() || n.threshold < 0)) throw BadRequestException("threshold for '${n.aspect}' must be >= 0 (it is compared with |magnitude|)")
      if (n.unit != null && (n.threshold == null || n.unit.length > 48)) throw BadRequestException("unit for '${n.aspect}' needs a threshold and is at most 48 chars")
      if (n.aspect.isEmpty() || n.mode !in setOf("CONSENT", "NOTIFY") || n.scope !in setOf("TARGET", "DOWNSTREAM")) throw BadRequestException("rule aspect needs aspect, mode CONSENT|NOTIFY, scope TARGET|DOWNSTREAM")
      if (n.fallback_group != null && !GROUP_RE.matches(n.fallback_group)) throw BadRequestException("fallback_group must match ${GROUP_RE.pattern}")
      n
    }
    if (out.map { it.aspect }.distinct().size != out.size) throw BadRequestException("duplicate aspects in rule")
    return out
  }

  private fun writeAspects(c: Connection, token: String, aspects: List<RuleAspectDto>) {
    val now = clock.millis()
    c.exec("DELETE FROM approval_rule_aspects WHERE approval_rule_token = ?", listOf(token))
    for (a in aspects) c.insert("INSERT INTO approval_rule_aspects (created_at_ms, updated_at_ms, approval_rule_token, aspect, mode, scope, fallback_group, threshold, unit) VALUES (?,?,?,?,?,?,?,?,?)", listOf(now, now, token, a.aspect, a.mode, a.scope, a.fallback_group, a.threshold, a.unit))
  }

  private fun appliesTo(c: Connection, token: String): List<String> =
    c.select("SELECT t.source_service, t.source_entity FROM approval_rule_types x JOIN external_entity_types t ON t.id = x.external_entity_type_id WHERE x.approval_rule_token = ? ORDER BY t.source_service, t.source_entity", listOf(token)) { "${it.getString(1)}/${it.getString(2)}" }

  private fun writeAppliesTo(c: Connection, token: String, types: List<String>) {
    val ids = types.map { it.trim().removeSuffix("/*") }.filter { it.isNotEmpty() }.distinct().map { entities.typeId(c, it) }.distinct()
    c.exec("DELETE FROM approval_rule_types WHERE approval_rule_token = ?", listOf(token))
    for (id in ids) c.insert("INSERT INTO approval_rule_types (created_at_ms, approval_rule_token, external_entity_type_id) VALUES (?,?,?)", listOf(clock.millis(), token, id))
  }

  /** The action allowlist: a rule is filed only against types it lists, and a listed type only takes rules that list it. */
  private fun checkAppliesTo(c: Connection, rule: RuleDto, target: Target) {
    val allowed = c.select(
      "SELECT x.approval_rule_token FROM approval_rule_types x JOIN approval_rules r ON r.token = x.approval_rule_token WHERE x.external_entity_type_id = ? AND r.deleted_at_ms IS NULL ORDER BY 1",
      listOf(target.entityTypeId),
    ) { it.getString(1) }
    if (rule.token in allowed) return
    val type = target.ref.substringBeforeLast('/')
    if (rule.applies_to.isNotEmpty()) throw ConflictException("${rule.token} (${rule.description}) applies to ${rule.applies_to.joinToString()}, not $type")
    if (allowed.isNotEmpty()) throw ConflictException("$type only takes ${allowed.joinToString()}; ${rule.token} does not list it in applies_to")
  }

  private fun groupsOf(c: Connection, token: String): List<RuleGroupDto> =
    c.select("SELECT * FROM approval_rule_groups WHERE approval_rule_token = ? ORDER BY id", listOf(token)) {
      RuleGroupDto(it.getString("group_name"), it.getString("directory_group"), it.getBoolean("admins_only"), it.getString("slack_channel"), it.getString("email"))
    }

  private fun rules(c: Connection, where: String, args: List<Any?> = listOf()): List<RuleDto> =
    c.select("SELECT r.*, (SELECT COUNT(*) FROM approvables a WHERE a.approval_rule_token = r.token AND a.approval_state IN $OPEN) AS open_n FROM approval_rules r $where", args) { rs ->
      RuleDto(
        rs.getString("token"), rs.getString("description"), rs.getString("requirement"), rs.getInt("min_groups").takeUnless { rs.wasNull() }, listOf(),
        rs.getString("created_by"), rs.getLong("open_n"), iso(rs.getLong("created_at_ms")), iso(rs.getLong("updated_at_ms")), rs.isoOrNull("deleted_at_ms"), rs.getInt("version"), on_revision = rs.getString("on_revision"),
      )
    }.map { it.copy(groups = groupsOf(c, it.token), aspects = aspectsOf(c, it.token), applies_to = appliesTo(c, it.token)) }

  private fun rule(c: Connection, token: String): RuleDto = rules(c, "WHERE r.token = ?", listOf(token)).firstOrNull() ?: throw NotFoundException("no approval rule '$token'")

  private fun validate(req: Requirement, minGroups: Int?, groups: List<RuleGroupDto>, aspects: List<RuleAspectDto> = listOf()): Int? {
    // With CONSENT aspects the approving groups are only known per approvable, at filing.
    val dynamic = aspects.any { it.mode == "CONSENT" }
    if (aspects.isNotEmpty() && req.notifyOnly && !dynamic) return null
    groups.forEach { g ->
      if (!GROUP_RE.matches(g.group) || !GROUP_RE.matches(g.directory_group)) throw BadRequestException("group / directory_group must match ${GROUP_RE.pattern}")
    }
    if (groups.map { it.group }.distinct().size != groups.size) throw BadRequestException("duplicate group names in rule")
    if (req.needsGroups && groups.isEmpty() && !dynamic) throw BadRequestException("$req needs at least one group")
    if (req == Requirement.SLACK_NOTIFICATION && groups.none { it.slack_channel != null }) throw BadRequestException("SLACK_NOTIFICATION needs a group with a slack_channel")
    if (req == Requirement.EMAIL_NOTIFICATION && groups.none { it.email != null }) throw BadRequestException("EMAIL_NOTIFICATION needs a group with an email")
    if (req != Requirement.MIN_GROUPS) {
      if (minGroups != null) throw BadRequestException("min_groups only applies to MIN_GROUPS")
      return null
    }
    val n = minGroups ?: 2
    if (n < 1 || (!dynamic && n > groups.size)) throw BadRequestException("min_groups=$n but the rule has ${groups.size} group(s)")
    return n
  }

  private fun writeGroups(c: Connection, token: String, groups: List<RuleGroupDto>) {
    val now = clock.millis()
    c.exec("DELETE FROM approval_rule_groups WHERE approval_rule_token = ?", listOf(token))
    for (g in groups) {
      c.insert(
        "INSERT INTO approval_rule_groups (created_at_ms, updated_at_ms, approval_rule_token, group_name, directory_group, admins_only, slack_channel, email) VALUES (?,?,?,?,?,?,?,?)",
        listOf(now, now, token, g.group, g.directory_group, g.admins_only, g.slack_channel, g.email),
      )
    }
  }

  fun createRule(actor: Actor, token: String?, description: String, requirement: String?, minGroups: Int?, groups: List<RuleGroupDto>, aspects: List<RuleAspectDto> = listOf(), appliesTo: List<String> = listOf(), onRevision: String? = null): RuleDto = db.tx { c ->
    tlcers.touch(c, actor)
    val req = Requirement.parse(requirement)
    val onRev = onRevision(onRevision) ?: "DISMISS"
    val asp = normAspects(aspects)
    val n = validate(req, minGroups, groups, asp)
    if (token != null && !RULE_TOKEN_RE.matches(token)) throw BadRequestException("rule token must match ${RULE_TOKEN_RE.pattern}")
    var tok = token
    if (tok == null) {
      // "AR-6392"-style: short enough to say out loud. Retry on the (rare) collision.
      do { tok = "AR-" + randomDigits(4) } while (c.select("SELECT 1 FROM approval_rules WHERE token = ?", listOf(tok)) { 1 }.isNotEmpty())
    } else if (c.select("SELECT 1 FROM approval_rules WHERE token = ?", listOf(tok)) { 1 }.isNotEmpty()) {
      throw ConflictException("approval rule $tok already exists")
    }
    val now = clock.millis()
    c.insert("INSERT INTO approval_rules (created_at_ms, updated_at_ms, token, description, requirement, min_groups, created_by, on_revision) VALUES (?,?,?,?,?,?,?,?)", listOf(now, now, tok, description, req.name, n, actor.name, onRev))
    writeGroups(c, tok!!, groups)
    writeAspects(c, tok, asp)
    writeAppliesTo(c, tok, appliesTo)
    rule(c, tok)
  }

  fun listRules(includeDeleted: Boolean): List<RuleDto> = db.tx { c -> rules(c, "${if (includeDeleted) "" else "WHERE r.deleted_at_ms IS NULL"} ORDER BY r.token") }

  fun getRule(token: String): RuleDto = db.tx { c -> rule(c, token) }

  /**
   * Description is always editable. Changing what the rule *requires* while approvables are open
   * under it would move the goalposts mid-flight, so that is a 409 unless [force]; with force,
   * every open approvable is re-evaluated in this transaction and may flip PENDING<->APPROVED.
   */
  fun updateRule(actor: Actor, token: String, description: String?, requirement: String?, minGroups: Int?, groups: List<RuleGroupDto>?, force: Boolean, aspects: List<RuleAspectDto>? = null, appliesTo: List<String>? = null, onRevision: String? = null): RuleDto = db.tx { c ->
    c.select("SELECT id FROM approval_rules WHERE token = ? FOR UPDATE", listOf(token)) { it.getLong(1) }.firstOrNull() ?: throw NotFoundException("no approval rule '$token'")
    val old = rule(c, token)
    if (old.deleted_at != null) throw ConflictException("approval rule $token was deleted")
    val req = requirement?.let(Requirement::parse) ?: Requirement.valueOf(old.requirement)
    val newGroups = groups ?: old.groups
    val wantMin = if (req == Requirement.MIN_GROUPS) (minGroups ?: old.min_groups) else minGroups
    val newAspects = aspects?.let(::normAspects) ?: old.aspects
    val n = validate(req, wantMin, newGroups, newAspects)
    val onRev = onRevision(onRevision) ?: old.on_revision
    val policyChanged = req.name != old.requirement || n != old.min_groups || newGroups != old.groups || newAspects != old.aspects || onRev != old.on_revision
    val open = c.select("SELECT id FROM approvables WHERE approval_rule_token = ? AND approval_state IN $OPEN ORDER BY id FOR UPDATE", listOf(token)) { it.getLong(1) }
    if (policyChanged && open.isNotEmpty() && !force) throw ConflictException("${open.size} approvable(s) are open under $token; pass force=true to change its requirement and re-evaluate them")
    c.exec("UPDATE approval_rules SET description = COALESCE(?, description), requirement = ?, min_groups = ?, on_revision = ?, updated_at_ms = ?, version = version + 1 WHERE token = ?", listOf(description, req.name, n, onRev, clock.millis(), token))
    if (policyChanged) {
      writeGroups(c, token, newGroups)
      // Aspects apply to approvables filed from now on: open ones keep the parties they were filed with.
      writeAspects(c, token, newAspects)
      val r = rule(c, token)
      for (id in open) reevaluate(c, id, r, "rule $token changed by ${actor.name}")
    }
    // Checked at filing only, so this never needs force: open approvables are unaffected.
    if (appliesTo != null) writeAppliesTo(c, token, appliesTo)
    rule(c, token)
  }

  fun destroyRule(token: String): RuleDto = db.tx { c ->
    val r = rule(c, token)
    if (r.open_approvables > 0) throw ConflictException("${r.open_approvables} approvable(s) are open under $token")
    if (r.deleted_at == null) c.exec("UPDATE approval_rules SET deleted_at_ms = ?, updated_at_ms = ?, version = version + 1 WHERE token = ?", listOf(clock.millis(), clock.millis(), token))
    rule(c, token)
  }

  // ------------------------------------------------------------------------- approvables

  private class Row(val id: Long, val entityId: Long, val dto: ApprovableDto)

  private fun rows(c: Connection, tail: String, args: List<Any?> = listOf()): List<Row> =
    c.select("SELECT g.* FROM approvables g $tail", args) { r ->
      Row(r.getLong("id"), r.getLong("external_entity_id"), ApprovableDto(
        r.getString("token"), "", r.getString("approval_context"), r.getString("approval_rule_token"), r.getString("approval_state"), r.getString("requested_by"),
        r.getString("requested_via_service"), r.getString("client_id"), r.isoOrNull("approved_at_ms"), r.isoOrNull("executed_at_ms"), r.getString("executed_by"),
        r.isoOrNull("declined_at_ms"), r.getString("declined_by"), r.getString("decline_reason"), iso(r.getLong("created_at_ms")), iso(r.getLong("updated_at_ms")),
        r.getInt("version"), r.longOrNull("feed_sync_id"), r.getInt("shard"), r.getString("mode"), r.isoOrNull("expires_at_ms"), r.getString("acting_group"),
        r.getString("revision"), r.getInt("revision_seq"),
      ))
    }.map { Row(it.id, it.entityId, it.dto.copy(target = entities.refOf(c, it.entityId))) }

  private fun row(c: Connection, token: String, lock: Boolean = false): Row =
    rows(c, "WHERE g.token = ?${if (lock) " FOR UPDATE" else ""}", listOf(token)).firstOrNull() ?: throw NotFoundException("no approvable '$token'")

  /** [currentSeq]: the approvable's revision_seq, to mark live approvals given at an earlier revision as stale. */
  private fun approvals(c: Connection, id: Long, liveOnly: Boolean, currentSeq: Int? = null): List<ApprovalDto> =
    c.select("SELECT * FROM approvals WHERE approvable_id = ? ${if (liveOnly) "AND active = 1" else ""} ORDER BY id", listOf(id)) {
      val seq = it.getInt("revision_seq")
      val ended = it.isoOrNull("unapproved_at_ms")
      ApprovalDto(
        it.getString("token"), it.getString("approvable_token"), it.getString("approved_by"), iso(it.getLong("approved_at_ms")), ended, it.getString("via_groups").split(',').filter(String::isNotEmpty), it.getString("comment"),
        it.getString("revision"), seq, it.longOrNull("superseded_by_seq") != null, ended == null && currentSeq != null && seq < currentSeq,
      )
    }

  private fun revisions(c: Connection, id: Long): List<RevisionDto> =
    c.select("SELECT * FROM approvable_revisions WHERE approvable_id = ? ORDER BY seq", listOf(id)) {
      RevisionDto(it.getInt("seq"), it.getString("revision"), it.getString("context"), it.getString("revised_by"), it.getString("via_service"), iso(it.getLong("created_at_ms")), it.getInt("dismissed"))
    }

  /** `@3`, or `#2` (the second revision) when the source service gave no label. */
  private fun at(revision: String?, seq: Int) = revision?.let { "@$it" } ?: "#$seq"

  /** The record's latest revision as far as TLC has been told; threads and comments on it are stamped with this. */
  private fun noteRevision(c: Connection, entityId: Long, revision: String?) {
    if (revision != null) c.exec("UPDATE external_entities SET revision = ?, updated_at_ms = ?, version = version + 1 WHERE id = ? AND NOT (revision <=> ?)", listOf(revision, clock.millis(), entityId, revision))
  }

  private fun parties(c: Connection, id: Long): List<PartyDto> =
    c.select("SELECT * FROM approvable_parties WHERE approvable_id = ? ORDER BY id", listOf(id)) {
      PartyDto(it.getString("directory_group"), it.getString("mode"), it.getString("aspects").split(',').filter(String::isNotEmpty), it.getString("status"), it.getString("source"), it.getString("via").split(',').filter(String::isNotEmpty), it.getString("slack_channel"), it.getString("email"))
    }

  /**
   * The rule as it applies to one approvable: its own groups plus the CONSENT parties ownership
   * resolved when this approvable was filed. A group already named by the rule isn't added twice.
   */
  private fun effective(rule: RuleDto, parties: List<PartyDto>): RuleDto {
    val have = rule.groups.map { it.directory_group }.toSet()
    return rule.copy(groups = rule.groups + parties.filter { it.mode == "CONSENT" && it.directory_group !in have }.map { RuleGroupDto(it.directory_group, it.directory_group, false, it.slack_channel, it.email) })
  }

  private fun impacts(c: Connection, id: Long): List<ImpactDto> =
    c.select("SELECT * FROM approvable_impacts WHERE approvable_id = ? ORDER BY id", listOf(id)) {
      ImpactDto(it.getString("aspect"), it.getBoolean("declared"), it.getDouble("magnitude").takeUnless { _ -> it.wasNull() }, it.getString("unit"), it.getString("note"), it.getString("rule_mode"), it.getDouble("threshold").takeUnless { _ -> it.wasNull() }, it.getString("effect"), it.getString("reason"))
    }

  /**
   * Gates the rule's aspects by what the action is declared to do. Returns the aspects as they apply
   * to this one approvable (CONSENT demoted to NOTIFY, NOTIFY dropped, where the impact is at or
   * below the threshold) and the record of why. Anything not positively declared small keeps its
   * full mode: "we didn't measure" must never be the cheap path.
   */
  private fun gate(ruleAspects: List<RuleAspectDto>, declared: List<ImpactReq>): Pair<List<RuleAspectDto>, List<ImpactDto>> {
    val byAspect = LinkedHashMap<String, ImpactReq>()
    for (i in declared) {
      val a = i.aspect?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("each impact needs an aspect")
      if (i.magnitude != null && i.magnitude.isNaN()) throw BadRequestException("impact on '$a': magnitude must be a number, or null for unknown")
      if ((i.unit?.length ?: 0) > 48 || (i.note?.length ?: 0) > 512) throw BadRequestException("impact on '$a': unit is at most 48 chars, note at most 512")
      if (byAspect.put(a, i.copy(aspect = a, unit = i.unit?.trim()?.takeIf { it.isNotEmpty() })) != null) throw BadRequestException("impact on '$a' declared twice")
    }
    fun n(x: Double) = if (x == Math.floor(x) && Math.abs(x) < 1e15) x.toLong().toString() else x.toString()
    val out = ArrayList<ImpactDto>()
    val eff = ArrayList<RuleAspectDto>()
    for (ra in ruleAspects) {
      val i = byAspect.remove(ra.aspect)
      val u = ra.unit?.let { " $it" } ?: ""
      val (effect, reason) = when {
        ra.threshold == null -> ra.mode to "the rule has no threshold for this aspect"
        i == null -> ra.mode to "not declared: treated as unknown"
        i.magnitude == null -> ra.mode to "declared unknown"
        ra.unit != null && i.unit != ra.unit -> throw BadRequestException("impact on '${ra.aspect}' must be in '${ra.unit}' (the rule's threshold is ${n(ra.threshold)}$u), got ${i.unit?.let { "'$it'" } ?: "no unit"}")
        Math.abs(i.magnitude) > ra.threshold -> ra.mode to "|${n(i.magnitude)}| > ${n(ra.threshold)}$u"
        ra.mode == "CONSENT" -> "NOTIFY" to "|${n(i.magnitude)}| <= ${n(ra.threshold)}$u: told, not asked"
        else -> "SKIPPED" to "|${n(i.magnitude)}| <= ${n(ra.threshold)}$u: not told"
      }
      if (effect != "SKIPPED") eff += ra.copy(mode = effect)
      if (i != null || ra.threshold != null) out += ImpactDto(ra.aspect, i != null, i?.magnitude, i?.unit ?: ra.unit.takeIf { i != null }, i?.note, ra.mode, ra.threshold, effect, reason)
    }
    for ((a, i) in byAspect) out += ImpactDto(a, true, i.magnitude, i.unit, i.note, null, null, "NONE", "the rule does not name this aspect")
    return eff to out
  }

  private fun ruleFor(c: Connection, r: Row): RuleDto = effective(rule(c, r.dto.approval_rule_token), parties(c, r.id))

  private fun evaluate(c: Connection, r: Row, rule: RuleDto): Evaluation {
    val ruleGroups = rule.groups.map { it.group }.toSet()
    val live = approvals(c, r.id, liveOnly = true).associate { it.approved_by to it.via_groups.filter(ruleGroups::contains).toSet() }
    return ApprovalEngine.evaluate(Requirement.valueOf(rule.requirement), rule.min_groups, rule.groups, live, r.dto.requested_by)
  }

  /** Which of the rule's groups [ldap] may approve for right now. */
  private fun entitled(c: Connection, rule: RuleDto, ldap: String): List<String> {
    val mine = directory.memberOf(c, ldap)
    return rule.groups.filter { g -> mine[g.directory_group]?.let { isAdmin -> !g.admins_only || isAdmin } ?: false }.map { it.group }
  }

  private fun canApprove(c: Connection, r: Row, rule: RuleDto, ldap: String): CanApprove {
    val req = Requirement.valueOf(rule.requirement)
    val via = entitled(c, rule, ldap)
    fun no(why: String) = CanApprove(false, why, via)
    return when {
      r.dto.approval_state !in LIVE -> no("it is ${r.dto.approval_state}")
      req.notifyOnly -> no("${rule.token} is notification-only")
      r.dto.mode == "RATIFY" && ldap == r.dto.requested_by -> no("you pulled it; someone else has to ratify")
      req != Requirement.ANY_APPROVAL && ldap == r.dto.requested_by -> no("you requested it and ${rule.token} doesn't allow self-approval")
      rule.groups.isNotEmpty() && via.isEmpty() -> no("you are not in an approving group of ${rule.token} (${rule.groups.joinToString { it.directory_group + if (it.admins_only) " admins" else "" }})")
      else -> CanApprove(true, if (via.isEmpty()) "anyone may approve" else "as ${via.joinToString()}", via)
    }
  }

  private fun setState(c: Connection, id: Long, state: String, extra: String = "", args: List<Any?> = listOf()) {
    c.exec("UPDATE approvables SET approval_state = ?, updated_at_ms = ?, version = version + 1, feed_sync_id = NULL $extra WHERE id = ?", listOf(state, clock.millis()) + args + listOf(id))
  }

  private fun audit(c: Connection, r: Row, level: LogLevel, msg: String) {
    val typeId = c.select("SELECT external_entity_type_id FROM external_entities WHERE id = ?", listOf(r.entityId)) { it.getLong(1) }.first()
    logs.internal(c, Target(r.entityId, typeId, null, r.dto.target), level, "tlc.approvals", "${r.dto.token}: $msg")
  }

  private fun notify(c: Connection, r: Row, rule: RuleDto, event: String, text: String) {
    val req = Requirement.valueOf(rule.requirement)
    val url = "${config.tlc.public_url.trimEnd('/')}/#approvable=${r.dto.token}"
    val body = "[TLC] ${r.dto.target} — $text (${rule.token}: ${rule.description}) $url"
    for (g in rule.groups) {
      if (g.slack_channel != null && req != Requirement.EMAIL_NOTIFICATION) outbox.enqueue(c, "SLACK", g.slack_channel, event, r.dto.token, body)
      if (g.email != null && req != Requirement.SLACK_NOTIFICATION) outbox.enqueue(c, "EMAIL", g.email, event, r.dto.token, body)
    }
    // Told, not asked: NOTIFY aspects' owners and every stakeholder. (CONSENT parties are in rule.groups above.)
    val declared = impacts(c, r.id).filter { it.rule_mode != null && it.effect != it.rule_mode }.associateBy { it.aspect }
    for (p in parties(c, r.id).filter { it.mode == "NOTIFY" }) {
      // A group that would have been asked but for the declaration gets the number, so a wrong one is visible to whom it bypassed.
      val why = p.aspects.mapNotNull { declared[it] }.joinToString("") { "; declared " + impactText(it) }
      val fyi = "$body [fyi: ${p.directory_group} — ${p.aspects.joinToString()}$why]"
      if (p.slack_channel != null) outbox.enqueue(c, "SLACK", p.slack_channel, event, r.dto.token, fyi)
      if (p.email != null) outbox.enqueue(c, "EMAIL", p.email, event, r.dto.token, fyi)
    }
  }

  private fun impactText(i: ImpactDto): String {
    val m = when { !i.declared -> "undeclared"; i.magnitude == null -> "?"; else -> (if (i.magnitude == Math.floor(i.magnitude)) i.magnitude.toLong().toString() else i.magnitude.toString()) + (i.unit?.let { " $it" } ?: "") }
    return "${i.aspect}=$m (${i.reason})"
  }

  /** Moves PENDING<->APPROVED to match the evaluation. Returns the evaluation. */
  private fun reevaluate(c: Connection, id: Long, base: RuleDto, why: String): Evaluation {
    val r = rows(c, "WHERE g.id = ?", listOf(id)).first()
    val rule = effective(base, parties(c, id)) // idempotent: callers may pass either the bare rule or the effective one
    val ev = evaluate(c, r, rule)
    if (r.dto.approval_state == "ACTED" && ev.satisfied) {
      setState(c, id, "RATIFIED", ", approved_at_ms = ?", listOf(clock.millis()))
      audit(c, r, LogLevel.INFO, "ACTED -> RATIFIED ($why; ${ev.summary})")
      notify(c, r, rule, "RATIFIED", "ratified: ${ev.summary}")
    } else if (r.dto.approval_state == "PENDING" && ev.satisfied) {
      setState(c, id, "APPROVED", ", approved_at_ms = ?", listOf(clock.millis()))
      audit(c, r, LogLevel.INFO, "PENDING -> APPROVED ($why; ${ev.summary})")
      notify(c, r, rule, "APPROVED", "approved: ${ev.summary}")
    } else if (r.dto.approval_state == "APPROVED" && !ev.satisfied) {
      setState(c, id, "PENDING", ", approved_at_ms = NULL")
      audit(c, r, LogLevel.WARN, "APPROVED -> PENDING ($why; ${ev.summary})")
    }
    return ev
  }

  private fun result(c: Connection, token: String, changed: Boolean, rule: RuleDto): ActionResult {
    val r = row(c, token)
    return ActionResult(changed, r.dto, evaluate(c, r, rule))
  }

  /**
   * [mode] RATIFY is break-glass: the requester has *already* taken a reversible action and is asking
   * for it to be blessed after the fact. It starts ACTED, becomes RATIFIED when the rule is met, and
   * REVERTED when its TTL runs out first — at which point the source service undoes the action.
   * Only someone who owns an aspect of the target or of anything built on it (or an admin) may pull it.
   */
  fun create(actor: Actor, targetRef: String, ruleToken: String, context: String?, clientId: String?, requestedBy: String?, mode: String? = null, ttlSeconds: Long? = null, declared: List<ImpactReq> = listOf(), revision: String? = null): ApprovableView = db.tx { c ->
    tlcers.touch(c, actor)
    // An impact declaration decides who is asked, so it has to come from the system that computed
    // it (and is vouching for it), the same way only a service may set requested_by.
    if (declared.isNotEmpty() && actor.service == null) throw BadRequestException("impacts can only be declared by a service caller")
    val ratify = when ((mode ?: "APPROVE").trim().uppercase()) { "APPROVE" -> false; "RATIFY" -> true; else -> throw BadRequestException("mode is APPROVE or RATIFY") }
    val rev = revisionLabel(revision)
    val base = rule(c, ruleToken)
    var rule = base
    if (rule.deleted_at != null) throw ConflictException("approval rule $ruleToken was deleted")
    // A human caller is the requester, full stop. Only a service may name someone else (it is
    // vouching for its own user), otherwise "non-self" would be trivially dodged.
    val requester = when {
      actor.ldap != null -> { if (requestedBy != null && requestedBy != actor.ldap) throw BadRequestException("requested_by can only be set by a service caller"); actor.ldap }
      else -> requestedBy?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: actor.name
    }
    val target = entities.resolveEntity(c, targetRef, create = true)
    checkAppliesTo(c, base, target)
    if (clientId != null) {
      if (clientId.length > 96) throw BadRequestException("client_id is at most 96 chars")
      val prior = rows(c, "WHERE g.client_id = ?", listOf(clientId)).firstOrNull()
      if (prior != null) {
        if (prior.entityId != target.entityId || prior.dto.approval_rule_token != ruleToken) throw ConflictException("client_id '$clientId' was already used for ${prior.dto.target} under ${prior.dto.approval_rule_token}")
        return@tx view(c, prior.dto.token, actor)
      }
    }
    val now = clock.millis()
    val token = newToken("AP")
    val req = Requirement.valueOf(rule.requirement)
    // Resolve who this has to go through *now* and freeze it: ownership moving next week must not
    // silently change who was asked about this one.
    val (gated, impactRows) = gate(base.aspects, declared)
    val found = ownership.resolveParties(c, target.entityId!!, gated)
    rule = effective(base, found)
    if (req == Requirement.MIN_GROUPS && (rule.min_groups ?: 2) > rule.groups.size) throw ConflictException("$ruleToken needs ${rule.min_groups ?: 2} groups but only ${rule.groups.size} resolve for ${target.ref} (${rule.groups.joinToString { it.directory_group }.ifEmpty { "none" }})")
    var actingGroup: String? = null
    var expires: Long? = null
    if (ratify) {
      if (req.notifyOnly) throw BadRequestException("$ruleToken is notification-only: there is nothing to ratify")
      val owners = ownership.ownersDownstream(c, target.entityId)
      val mine = if (actor.ldap != null || requestedBy != null) directory.memberOf(c, requester).keys else setOf()
      actingGroup = owners.firstOrNull { it in mine } ?: rule.groups.map { it.directory_group }.firstOrNull { it in mine }
      if (actingGroup == null && !actor.admin) forbidden("break-glass on ${target.ref} needs an owner of it or of something built on it (${owners.joinToString().ifEmpty { "nobody owns anything here" }}); $requester is in none of those groups")
      expires = now + (ttlSeconds ?: config.tlc.ratify_ttl_seconds).coerceIn(1, 7 * 86_400) * 1000
    } else if (ttlSeconds != null) throw BadRequestException("ttl_seconds only applies to mode=RATIFY")
    c.insert(
      "INSERT INTO approvables (created_at_ms, updated_at_ms, token, external_entity_id, approval_context, approval_rule_token, approval_state, requested_by, requested_via_service, client_id, shard, mode, expires_at_ms, acting_group, revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
      listOf(now, now, token, target.entityId, context, ruleToken, if (ratify) "ACTED" else "PENDING", requester, actor.service, clientId, shardOf(target.key, config.tlc.feed_shards), if (ratify) "RATIFY" else "APPROVE", expires, actingGroup, rev),
    )
    val r = row(c, token)
    c.insert("INSERT INTO approvable_revisions (created_at_ms, approvable_id, seq, revision, context, revised_by, via_service) VALUES (?,?,?,?,?,?,?)", listOf(now, r.id, 1, rev, context, requester, actor.service))
    noteRevision(c, target.entityId, rev)
    for (p in found) c.insert(
      "INSERT INTO approvable_parties (created_at_ms, approvable_id, directory_group, mode, aspects, status, source, via, slack_channel, email) VALUES (?,?,?,?,?,?,?,?,?,?)",
      listOf(now, r.id, p.directory_group, p.mode, p.aspects.joinToString(","), p.status, p.source, p.via.joinToString(",").take(1024), p.slack_channel, p.email),
    )
    for (i in impactRows) c.insert(
      "INSERT INTO approvable_impacts (created_at_ms, approvable_id, aspect, declared, magnitude, unit, note, rule_mode, threshold, effect, reason) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
      listOf(now, r.id, i.aspect, i.declared, i.magnitude, i.unit, i.note, i.rule_mode, i.threshold, i.effect, i.reason),
    )
    if (impactRows.isNotEmpty()) audit(c, r, if (impactRows.any { it.rule_mode != null && it.effect != it.rule_mode }) LogLevel.WARN else LogLevel.INFO, "impacts declared by ${actor.name}: " + impactRows.joinToString("; ") { impactText(it) })
    if (found.isNotEmpty()) audit(c, r, LogLevel.INFO, "parties by ownership: " + found.joinToString("; ") { "${it.directory_group} ${it.mode} (${it.aspects.joinToString()}${if (it.status !in setOf("OWNED", "STAKEHOLDER")) ", ${it.status}" else ""})" })
    if (ratify) {
      audit(c, r, LogLevel.WARN, "BREAK-GLASS by $requester${actingGroup?.let { " ($it)" } ?: ""}${context?.let { ": $it" } ?: ""}; reverts ${iso(expires!!)} unless ratified under ${rule.token} (${rule.requirement})")
      notify(c, r, rule, "ACTED", "BREAK-GLASS by $requester${actingGroup?.let { " ($it)" } ?: ""}${context?.let { ": $it" } ?: ""} — already done; reverts ${iso(expires)} unless ratified")
      return@tx view(c, token, actor)
    }
    audit(c, r, LogLevel.INFO, "approval requested by $requester${rev?.let { " for @$it" } ?: ""} under ${rule.token} (${rule.requirement})")
    if (req.notifyOnly) {
      notify(c, r, rule, "NOTIFY", "change by $requester${context?.let { ": $it" } ?: ""}")
      setState(c, r.id, "APPROVED", ", approved_at_ms = ?", listOf(now))
      audit(c, r, LogLevel.INFO, "PENDING -> APPROVED (${rule.token} is notification-only)")
    } else {
      notify(c, r, rule, "REQUESTED", "approval requested by $requester${context?.let { ": $it" } ?: ""}")
    }
    // Every consenting aspect was declared below its threshold: there is nobody to ask.
    if (req.needsGroups && rule.groups.isEmpty()) reevaluate(c, r.id, base, "no aspect above its threshold: nobody needs to consent")
    view(c, token, actor)
  }

  private fun view(c: Connection, token: String, viewer: Actor?): ApprovableView {
    val r = row(c, token)
    val rule = ruleFor(c, r)
    return ApprovableView(r.dto, rule, approvals(c, r.id, liveOnly = false, currentSeq = r.dto.revision_seq), evaluate(c, r, rule), viewer?.ldap?.let { canApprove(c, r, rule, it) }, parties(c, r.id), impacts(c, r.id), revisions(c, r.id))
  }

  fun get(token: String, viewer: Actor?): ApprovableView = db.tx { c -> view(c, token, viewer) }

  /** [awaiting]: only PENDING approvables that ldap could approve and hasn't — "my queue". */
  fun list(targetRef: String?, state: String?, ruleToken: String?, requestedBy: String?, awaiting: String?, limit: Int): List<ApprovableDto> = db.tx { c ->
    val where = ArrayList<String>()
    val args = ArrayList<Any?>()
    if (targetRef != null) { where += "g.external_entity_id = ?"; args += (entities.resolveOrNull(c, targetRef)?.entityId ?: return@tx listOf()) }
    if (awaiting != null) where += "g.approval_state IN ('PENDING','ACTED')"
    else if (state != null) { where += "g.approval_state = ?"; args += state.uppercase() }
    if (ruleToken != null) { where += "g.approval_rule_token = ?"; args += ruleToken }
    if (requestedBy != null) { where += "g.requested_by = ?"; args += requestedBy }
    val found = rows(c, "${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY g.id DESC LIMIT ${if (awaiting != null) 1000 else limit}", args)
    if (awaiting == null) return@tx found.map { it.dto }
    val ruleCache = HashMap<String, RuleDto>()
    found.filter { r ->
      val rule = effective(ruleCache.getOrPut(r.dto.approval_rule_token) { rule(c, r.dto.approval_rule_token) }, parties(c, r.id))
      canApprove(c, r, rule, awaiting).allowed && approvals(c, r.id, liveOnly = true).none { it.approved_by == awaiting }
    }.take(limit).map { it.dto }
  }

  /** [revision]: the revision the approver looked at. If the approvable has moved on since, it is a 409 rather than an approval of something unread. */
  fun approve(actor: Actor, token: String, comment: String?, revision: String? = null): ActionResult = db.tx { c ->
    val ldap = actor.requireHuman("approving")
    tlcers.touch(c, actor)
    val r = row(c, token, lock = true)
    val rule = ruleFor(c, r)
    revisionLabel(revision)?.let { if (it != r.dto.revision) throw ConflictException("$token is now at ${at(r.dto.revision, r.dto.revision_seq)}, not @$it: look at the current revision before approving") }
    val can = canApprove(c, r, rule, ldap)
    if (!can.allowed) {
      if (r.dto.approval_state !in LIVE || Requirement.valueOf(rule.requirement).notifyOnly) throw ConflictException("can't approve $token: ${can.reason}")
      forbidden("can't approve $token: ${can.reason}")
    }
    if (approvals(c, r.id, liveOnly = true).any { it.approved_by == ldap }) return@tx result(c, token, false, rule)
    val now = clock.millis()
    c.insert(
      "INSERT INTO approvals (created_at_ms, updated_at_ms, token, approvable_id, approvable_token, approved_by, approved_at_ms, via_groups, comment, revision_seq, revision) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
      listOf(now, now, newToken("A"), r.id, token, ldap, now, can.via_groups.joinToString(","), comment, r.dto.revision_seq, r.dto.revision),
    )
    c.exec("UPDATE approvables SET updated_at_ms = ?, version = version + 1, feed_sync_id = NULL WHERE id = ?", listOf(now, r.id))
    audit(c, r, LogLevel.INFO, "approved by $ldap${if (can.via_groups.isNotEmpty()) " as ${can.via_groups.joinToString()}" else ""}${r.dto.revision?.let { " at @$it" } ?: ""}")
    reevaluate(c, r.id, rule, "approval by $ldap")
    result(c, token, true, rule)
  }

  /** Withdraw your approval. Possible until EXECUTED/DECLINED; may drop APPROVED back to PENDING. */
  fun unapprove(actor: Actor, token: String): ActionResult = db.tx { c ->
    val ldap = actor.requireHuman("unapproving")
    val r = row(c, token, lock = true)
    val rule = ruleFor(c, r)
    if (r.dto.approval_state !in LIVE) throw ConflictException("can't unapprove $token: it is ${r.dto.approval_state}")
    val now = clock.millis()
    val n = c.exec("UPDATE approvals SET unapproved_at_ms = ?, updated_at_ms = ?, version = version + 1 WHERE approvable_id = ? AND approved_by = ? AND active = 1", listOf(now, now, r.id, ldap))
    if (n == 0) return@tx result(c, token, false, rule)
    c.exec("UPDATE approvables SET updated_at_ms = ?, version = version + 1, feed_sync_id = NULL WHERE id = ?", listOf(now, r.id))
    audit(c, r, LogLevel.INFO, "approval withdrawn by $ldap")
    reevaluate(c, r.id, rule, "approval withdrawn by $ldap")
    result(c, token, true, rule)
  }

  /**
   * The thing up for approval changed: the approvable moves to a new revision of its target. Every
   * revision is kept. What happens to the approvals already given is the rule's `on_revision`:
   * DISMISS withdraws them (they were for something else), KEEP lets them stand, marked stale.
   * Only the requesting side (or an admin) may revise. Parties and impacts stay as frozen at filing.
   */
  fun revise(actor: Actor, token: String, revision: String?, context: String?): ActionResult = db.tx { c ->
    tlcers.touch(c, actor)
    val r = row(c, token, lock = true)
    val rule = ruleFor(c, r)
    val rev = revisionLabel(revision) ?: throw BadRequestException("revision is required: the source service's label for the new version")
    if (r.dto.approval_state !in LIVE) throw ConflictException("can't revise $token: it is ${r.dto.approval_state}")
    val mine = if (r.dto.requested_via_service != null) actor.service == r.dto.requested_via_service else actor.name == r.dto.requested_by
    if (!mine && !actor.admin) forbidden("only ${r.dto.requested_via_service?.let { "service $it" } ?: r.dto.requested_by} (or an admin) may revise $token")
    if (rev == r.dto.revision) return@tx result(c, token, false, rule)
    if (revisions(c, r.id).any { it.revision == rev }) throw ConflictException("$token was already at @$rev; a revision is never reused, mint a new one")
    val now = clock.millis()
    val seq = r.dto.revision_seq + 1
    val dismissed = if (rule.on_revision == "DISMISS") c.exec("UPDATE approvals SET unapproved_at_ms = ?, superseded_by_seq = ?, updated_at_ms = ?, version = version + 1 WHERE approvable_id = ? AND active = 1", listOf(now, seq, now, r.id)) else 0
    c.insert("INSERT INTO approvable_revisions (created_at_ms, approvable_id, seq, revision, context, revised_by, via_service, dismissed) VALUES (?,?,?,?,?,?,?,?)", listOf(now, r.id, seq, rev, context, actor.name, actor.service, dismissed))
    c.exec("UPDATE approvables SET revision = ?, revision_seq = ?, approval_context = COALESCE(?, approval_context), updated_at_ms = ?, version = version + 1, feed_sync_id = NULL WHERE id = ?", listOf(rev, seq, context, now, r.id))
    noteRevision(c, r.entityId, rev)
    val kept = approvals(c, r.id, liveOnly = true).size
    val effect = when { dismissed > 0 -> "$dismissed approval(s) dismissed"; kept > 0 -> "$kept approval(s) kept from earlier revisions"; else -> "no approvals yet" }
    val text = "${at(r.dto.revision, r.dto.revision_seq)} -> @$rev by ${actor.name}${context?.let { ": $it" } ?: ""}; $effect (${rule.token} on_revision=${rule.on_revision})"
    audit(c, r, if (dismissed > 0) LogLevel.WARN else LogLevel.INFO, "revised $text")
    notify(c, row(c, token), rule, "REVISED", "revised $text")
    reevaluate(c, r.id, rule, "revised to @$rev")
    result(c, token, true, rule)
  }

  /** Terminal. The requester (withdrawing), anyone who could approve, or an admin. */
  fun decline(actor: Actor, token: String, reason: String?): ActionResult = db.tx { c ->
    tlcers.touch(c, actor)
    val r = row(c, token, lock = true)
    val rule = ruleFor(c, r)
    if (r.dto.approval_state == "DECLINED" || r.dto.approval_state == "REVERTED") return@tx result(c, token, false, rule)
    if (r.dto.approval_state !in LIVE) throw ConflictException("can't decline $token: it is ${r.dto.approval_state}")
    val isRequester = actor.name == r.dto.requested_by || (actor.service != null && actor.service == r.dto.requested_via_service)
    if (r.dto.approval_state == "ACTED") {
      // Reverting early belongs to the side that pulled it. Everyone else's lever is to not ratify
      // and let the TTL do it; otherwise two owners could flip the thing back and forth all night.
      val sameGroup = actor.ldap != null && r.dto.acting_group != null && r.dto.acting_group in directory.memberOf(c, actor.ldap)
      if (!isRequester && !sameGroup && !actor.admin) forbidden("only ${r.dto.requested_by}${r.dto.acting_group?.let { ", $it" } ?: ""} or an admin can revert $token early; withhold ratification and it reverts ${r.dto.expires_at}")
      revert(c, r, rule, actor.name, reason)
      return@tx result(c, token, true, rule)
    }
    val isApprover = actor.ldap != null && (rule.groups.isEmpty() || entitled(c, rule, actor.ldap).isNotEmpty())
    if (!isRequester && !isApprover && !actor.admin) forbidden("only the requester, an approver or an admin may decline $token")
    setState(c, r.id, "DECLINED", ", declined_at_ms = ?, declined_by = ?, decline_reason = ?", listOf(clock.millis(), actor.name, reason))
    audit(c, r, LogLevel.WARN, "${r.dto.approval_state} -> DECLINED by ${actor.name}${reason?.let { ": $it" } ?: ""}")
    notify(c, r, rule, "DECLINED", "declined by ${actor.name}${reason?.let { ": $it" } ?: ""}")
    result(c, token, true, rule)
  }

  /**
   * APPROVED -> EXECUTED, exactly once. This is a *claim*: call it before performing the change and
   * perform it only if `changed` is true. Two racers serialize on the row lock; the loser sees
   * changed=false. Executor must be the requesting side (requester / its service) or an admin.
   */
  fun execute(actor: Actor, token: String, expectedVersion: Int?, revision: String? = null): ActionResult = db.tx { c ->
    tlcers.touch(c, actor)
    val r = row(c, token, lock = true)
    val rule = ruleFor(c, r)
    if (r.dto.approval_state == "EXECUTED") return@tx result(c, token, false, rule)
    if (r.dto.approval_state != "APPROVED") throw ConflictException("can't execute $token: it is ${r.dto.approval_state}, not APPROVED")
    // What is about to be applied has to be what was approved.
    revisionLabel(revision)?.let { if (it != r.dto.revision) throw ConflictException("$token is approved at ${at(r.dto.revision, r.dto.revision_seq)}, not @$it") }
    if (expectedVersion != null && expectedVersion != r.dto.version) throw ConflictException("$token is at version ${r.dto.version}, not $expectedVersion")
    val mine = if (r.dto.requested_via_service != null) actor.service == r.dto.requested_via_service else actor.name == r.dto.requested_by
    if (!mine && !actor.admin) forbidden("only ${r.dto.requested_via_service?.let { "service $it" } ?: r.dto.requested_by} (or an admin) may execute $token")
    // Belt and braces: the state should already reflect the approvals, but never execute on a stale one.
    val ev = evaluate(c, r, rule)
    if (!ev.satisfied) throw ConflictException("can't execute $token: ${ev.summary}")
    setState(c, r.id, "EXECUTED", ", executed_at_ms = ?, executed_by = ?", listOf(clock.millis(), actor.name))
    audit(c, r, LogLevel.INFO, "APPROVED -> EXECUTED by ${actor.name}${r.dto.revision?.let { " at @$it" } ?: ""}")
    notify(c, r, rule, "EXECUTED", "executed by ${actor.name}")
    result(c, token, true, rule)
  }

  private fun revert(c: Connection, r: Row, rule: RuleDto, by: String, reason: String?) {
    setState(c, r.id, "REVERTED", ", declined_at_ms = ?, declined_by = ?, decline_reason = ?", listOf(clock.millis(), by, reason))
    audit(c, r, LogLevel.WARN, "ACTED -> REVERTED by $by${reason?.let { ": $it" } ?: ""}")
    notify(c, r, rule, "REVERTED", "REVERT IT: ${reason ?: "reverted by $by"}")
  }

  /** Break-glass actions nobody ratified in time. Called from the background loop; returns how many reverted. */
  fun expireRatifications(): Int = db.tx { c ->
    val due = rows(c, "WHERE g.approval_state = 'ACTED' AND g.expires_at_ms <= ? ORDER BY g.id LIMIT 200 FOR UPDATE", listOf(clock.millis()))
    for (r in due) {
      val rule = ruleFor(c, r)
      revert(c, r, rule, "tlc:ttl", "not ratified by ${r.dto.expires_at} (${evaluate(c, r, rule).summary})")
    }
    due.size
  }

  fun feed(after: Long, limit: Int, shardSql: String): List<ApprovableDto> = db.tx { c ->
    rows(c, "WHERE g.feed_sync_id > ? $shardSql ORDER BY g.feed_sync_id LIMIT $limit", listOf(after)).map { it.dto }
  }
}

// ========================================================================== notifications

data class NotificationDto(val token: String, val channel: String, val destination: String, val event: String, val approvable_token: String?, val body: String, val status: String, val detail: String?, val attempts: Int, val created_at: String, val sent_at: String?)

/** Transactional outbox: rows are written with the state change and delivered after commit. */
@Singleton
class Outbox @Inject constructor(private val db: Db, private val clock: Clock) {
  fun enqueue(c: Connection, channel: String, destination: String, event: String, approvable: String?, body: String) {
    val now = clock.millis()
    c.insert("INSERT INTO notifications (created_at_ms, updated_at_ms, token, channel, destination, event, approvable_token, body) VALUES (?,?,?,?,?,?,?,?)", listOf(now, now, newToken("N"), channel, destination, event, approvable, body))
  }

  private fun dto(rs: ResultSet) = NotificationDto(rs.getString("token"), rs.getString("channel"), rs.getString("destination"), rs.getString("event"), rs.getString("approvable_token"), rs.getString("body"), rs.getString("status"), rs.getString("detail"), rs.getInt("attempts"), iso(rs.getLong("created_at_ms")), rs.isoOrNull("sent_at_ms"))

  fun list(approvable: String?, status: String?, limit: Int): List<NotificationDto> = db.tx { c ->
    val where = ArrayList<String>()
    val args = ArrayList<Any?>()
    if (approvable != null) { where += "approvable_token = ?"; args += approvable }
    if (status != null) { where += "status = ?"; args += status.uppercase() }
    c.select("SELECT * FROM notifications ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY id DESC LIMIT $limit", args, ::dto)
  }

  fun pending(limit: Int): List<NotificationDto> = db.tx { c -> c.select("SELECT * FROM notifications WHERE status = 'PENDING' ORDER BY id LIMIT $limit", map = ::dto) }

  fun mark(token: String, status: String, detail: String?) = db.tx { c ->
    val now = clock.millis()
    c.exec("UPDATE notifications SET status = ?, detail = ?, attempts = attempts + 1, sent_at_ms = ?, updated_at_ms = ?, version = version + 1 WHERE token = ?", listOf(status, detail?.take(512), if (status == "PENDING") null else now, now, token))
  }
}

// ================================================================================ actions

data class MembershipList(val memberships: List<MembershipDto>)
data class RuleReq(val token: String?, val description: String?, val requirement: String?, val min_groups: Int?, val groups: List<RuleGroupDto>?, val aspects: List<RuleAspectDto>? = null, val applies_to: List<String>? = null, val on_revision: String? = null)
data class RuleList(val rules: List<RuleDto>)
data class ApprovableReq(val target: String?, val rule: String?, val context: String?, val client_id: String?, val requested_by: String?, val mode: String? = null, val ttl_seconds: Long? = null, val impacts: List<ImpactReq>? = null, val revision: String? = null)
data class GroupContacts(val groups: List<GroupContactDto>)
data class ApprovableList(val approvables: List<ApprovableDto>)
data class ApproveReq(val comment: String?, val reason: String?, val expected_version: Int?, val revision: String? = null)
data class ReviseReq(val revision: String?, val context: String?)
data class NotificationList(val notifications: List<NotificationDto>)

class DirectoryActions @Inject constructor(private val directory: Directory) : WebAction {
  @Get("/api/directory") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun list(@QueryParam group: String?) = MembershipList(directory.list(group))

  @Put("/api/directory/{group}/members/{ldap}") @Authenticated(capabilities = [ADMIN]) @ResponseContentType(JSON)
  fun put(@PathParam group: String, @PathParam ldap: String, @QueryParam admin: Boolean?) = directory.put(group, ldap, admin == true)

  /** Where a group is reached. Used for parties that ownership pulls into an approvable. */
  @Put("/api/directory/{group}/contact") @Authenticated(capabilities = [ADMIN]) @ResponseContentType(JSON)
  fun contact(@PathParam group: String, @QueryParam slack_channel: String?, @QueryParam email: String?) = directory.setContact(group, slack_channel, email)

  @Get("/api/directory/contacts") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun contacts() = GroupContacts(directory.contacts())

  @Delete("/api/directory/{group}/members/{ldap}") @Authenticated(capabilities = [ADMIN]) @ResponseContentType(JSON)
  fun remove(@PathParam group: String, @PathParam ldap: String) = Ok(directory.remove(group, ldap))
}

class RuleActions @Inject constructor(private val store: ApprovalStore, private val actors: Actors) : WebAction {
  @Post("/api/approval-rules") @Authenticated(capabilities = [ADMIN]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun create(@RequestBody req: RuleReq) = store.createRule(actors.current(), req.token, req.description.required("description"), req.requirement.required("requirement"), req.min_groups, req.groups.orEmpty(), req.aspects.orEmpty(), req.applies_to.orEmpty(), req.on_revision)

  @Get("/api/approval-rules") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun list(@QueryParam include_deleted: Boolean?) = RuleList(store.listRules(include_deleted == true))

  @Get("/api/approval-rules/{token}") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun get(@PathParam token: String) = store.getRule(token)

  @Patch("/api/approval-rules/{token}") @Authenticated(capabilities = [ADMIN]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun update(@PathParam token: String, @QueryParam force: Boolean?, @RequestBody req: RuleReq) =
    store.updateRule(actors.current(), token, req.description, req.requirement, req.min_groups, req.groups, force == true, req.aspects, req.applies_to, req.on_revision)

  @Delete("/api/approval-rules/{token}") @Authenticated(capabilities = [ADMIN]) @ResponseContentType(JSON)
  fun destroy(@PathParam token: String) = store.destroyRule(token)
}

class ApprovableActions @Inject constructor(private val store: ApprovalStore, private val actors: Actors, private val outbox: Outbox, private val comments: CommentStore) : WebAction {
  @Post("/api/approvables") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun create(@RequestBody req: ApprovableReq) = store.create(actors.current(), req.target.required("target"), req.rule.required("rule"), req.context, req.client_id, req.requested_by, req.mode, req.ttl_seconds, req.impacts.orEmpty(), req.revision)

  @Get("/api/approvables") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun list(@QueryParam target: String?, @QueryParam state: String?, @QueryParam rule: String?, @QueryParam requested_by: String?, @QueryParam awaiting: String?, @QueryParam limit: Int?) =
    ApprovableList(store.list(target, state, rule, requested_by, awaiting?.let { if (it == "me") actors.current().requireHuman("awaiting=me") else it }, limitOf(limit)))

  @Get("/api/approvables/{token}") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun get(@PathParam token: String) = store.get(token, actors.current()).copy(threads = comments.listThreads(token, null, false, 50))

  @Post("/api/approvables/{token}/approve") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun approve(@PathParam token: String, @RequestBody req: ApproveReq) = store.approve(actors.current(), token, req.comment, req.revision)

  /** The target changed: move the approvable to a new revision of it. */
  @Post("/api/approvables/{token}/revise") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun revise(@PathParam token: String, @RequestBody req: ReviseReq) = store.revise(actors.current(), token, req.revision, req.context)

  @Post("/api/approvables/{token}/unapprove") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun unapprove(@PathParam token: String) = store.unapprove(actors.current(), token)

  @Post("/api/approvables/{token}/decline") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun decline(@PathParam token: String, @RequestBody req: ApproveReq) = store.decline(actors.current(), token, req.reason ?: req.comment)

  @Post("/api/approvables/{token}/execute") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun execute(@PathParam token: String, @RequestBody req: ApproveReq) = store.execute(actors.current(), token, req.expected_version, req.revision)

  @Get("/api/notifications") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun notifications(@QueryParam approvable: String?, @QueryParam status: String?, @QueryParam limit: Int?) = NotificationList(outbox.list(approvable, status, limitOf(limit)))
}
