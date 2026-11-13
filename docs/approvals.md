# Approvals

> Names like `sentinel`, `ATO_VELOCITY`, `trust-infra` and `alice` are a made-up example company: [who and what they are](example-world.md).

```
                  approve (requirement met)            execute (claim)
   ┌─────────┐ ─────────────────────────────▶ ┌──────────┐ ───────────────▶ ┌──────────┐
   │ PENDING │                                │ APPROVED │                  │ EXECUTED │
   └─────────┘ ◀───────────────────────────── └──────────┘                  └──────────┘
        │        unapprove (no longer met)          │
        └──────────────── decline ──────────────────┴──────────────────────▶  DECLINED
```

The shape comes from the [`# approvals` section of the 2020 note](2020_scribble.md#approvals): approvable,
approvals, approval rule, rule-to-group. [decisions.md](decisions.md#approvals) says how each bullet was read.

| piece | is |
|---|---|
| **approval rule** ([`AR-6392`](example-world.md#AR-6392)) | what is required: a `requirement` + the groups that count |
| **rule group** | a label in the rule ([`ENG`](example-world.md#trust-infra)) → a directory group ([`trust-infra`](example-world.md#trust-infra)), `admins_only?`, `slack_channel?`, `email?` |
| **approvable** (`AP-…`) | one thing awaiting approval: an external record + rule + `approval_context` + who asked |
| **approval** | one person's approval of one approvable, with `unapproved_at` when withdrawn |

## Requirements

| `requirement` | satisfied when | self-approval |
|---|---|---|
| `ANY_APPROVAL` | ≥1 live approval | counts |
| `ANY_NON_SELF_APPROVAL` | ≥1 live approval not by the requester | refused |
| `MIN_GROUPS` | ≥ `min_groups` (default 2) groups matched | refused |
| `MAJORITY_OF_GROUPS` | > half of the rule's groups matched (`⌊G/2⌋+1`) | refused |
| `ALL_GROUPS` | every group matched | refused |
| `SLACK_NOTIFICATION` | immediately — groups' Slack channels are told | n/a |
| `EMAIL_NOTIFICATION` | immediately — groups' email addresses are told | n/a |

**Who may approve.** If the rule has groups: members of at least one group's `directory_group`
(admins of it, where `admins_only`). If it has none: anyone. Services can't approve; it takes a human.
Everything else is a 403 that says why. `GET /api/approvables/{AP}` includes a `you` block
(allowed / reason / via which groups) for the caller, and `GET /api/approvables?awaiting=me` is the
caller's queue: pending, approvable by them, not yet approved by them.

### Groups need distinct people

One person in both ENG and RISK satisfies **one** group, not two — otherwise "≥2 groups" collapses to
"one well-connected person". Which group they stand for is not fixed in advance: with bob ∈ {ENG, RISK}
and dave ∈ {ENG}, the answer is 2 groups *only if* bob is assigned to RISK. That is a maximum bipartite
matching between groups and approvers (`ApprovalEngine.matching`, Kuhn's augmenting paths); a greedy
first-fit under-counts exactly this case. The evaluation returned by every call shows the matching:

```
2/2  2/3 groups approved (needed 2)
  ENG      ✓ dave       candidates=bob,dave  <- trust-infra
  RISK     ✓ bob        candidates=bob       <- trust-risk
  LEGAL    ·            candidates=-         <- trust-legal
```

### Membership is snapshotted at approval time

An approval stores `via_groups`: the rule groups the approver was entitled to at that moment.
Leaving `trust-infra` afterwards does not un-approve — they *were* an authorized approver when they
signed, and silent retroactive flips are worse than a stale membership. To revoke, the approver
withdraws (`unapprove`) or anyone entitled declines.

## Lifecycle

- **Request**: `POST /api/approvables {target, rule, context?, client_id?, requested_by?}`.
  A human caller *is* the requester. Only a service caller may set `requested_by` (it vouches for its
  own user) — otherwise "non-self" is dodged by lying about who asked. `client_id` makes retries
  idempotent; reusing one for a different target/rule is a 409.
- **Approve / unapprove**: idempotent (`changed`). Extra approvals after APPROVED are accepted.
  Withdrawing before execution re-evaluates and can drop APPROVED → PENDING (`approved_at` cleared).
- **Decline** (terminal): the requester (withdrawing), anyone who could approve, or an admin.
- **Execute** (terminal): `POST …/execute {expected_version?}`. This is a **claim**, not a report:
  call it *before* applying the change and apply only if `changed: true`. Racers serialize on the
  row lock; exactly one gets `true`. Only the requesting side may claim — the service that filed it
  (`requested_via_service`), else the requester — or an admin. It re-checks the evaluation under the
  lock, so a withdrawal that raced the claim wins.
- Every mutation bumps `version`, republishes the row on the `approvables` feed, and writes a
  `tlc.approvals` log entry on the record.

## Approvers from ownership, and break-glass

Two extensions, both documented in [ownership.md](ownership.md):

- A rule may list **aspects** (`aspects: [{aspect, mode, scope, fallback_group}]`). At filing, the
  owners of those aspects are resolved, frozen onto the approvable (`parties`), and the CONSENT ones
  are appended to the rule's groups for that approvable. Everything above applies unchanged.
- `mode: "RATIFY"` on the request is break-glass: `ACTED → RATIFIED | REVERTED` with a TTL, pullable
  only by an owner of the target or of something built on it. `execute` does not apply (409).

## Changing a rule

Description: always. What it *requires* (requirement, `min_groups`, groups): 409 while approvables
are open under it — moving the goalposts mid-flight should be deliberate. (A change to `aspects`
counts, but only affects approvables filed afterwards: open ones keep their frozen parties.) `?force=true` applies the
change and re-evaluates every open approvable in the same transaction (they may flip either way;
each flip is logged with the reason). Rules with open approvables can't be deleted. Rule and
directory writes need the admin capability.

## Notifications

Rows go into `notifications` (a transactional outbox) with the state change and are delivered
after commit by the background loop — at-least-once, 5 attempts, then `FAILED`.

| event | when | to |
|---|---|---|
| `REQUESTED` | approvable created | every group's `slack_channel` and `email` |
| `APPROVED` / `DECLINED` / `EXECUTED` | on the transition | same |
| `NOTIFY` | created under a notification-only rule | Slack *or* email, per the requirement |
| `ACTED` / `RATIFIED` / `REVERTED` | break-glass filed / blessed / expired or pulled back | same, plus NOTIFY parties |

Ownership-resolved parties are reached at their group's contact (`/api/directory/{group}/contact`);
NOTIFY-mode owners and stakeholders get every event for the approvable, marked `[fyi: …]`.

Transport (`NotificationTransport`): SLACK posts to `tlc.slack_webhook_url` if configured; anything
else is written to the service log and marked `LOGGED`. `GET /api/notifications?approvable=AP-…`
shows what was, or would have been, said to whom.

## The directory stand-in

`directory_memberships (directory_group, ldap, is_admin)` plus `PUT/DELETE /api/directory/{group}/members/{ldap}`
stands in for the real group directory. All approval logic reaches it through `Directory.memberOf`;
pointing that at the real thing is the integration.
