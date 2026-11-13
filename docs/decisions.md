# How the note was read

The spec was a page of bullets: [2020_scribble.md](2020_scribble.md), notes taken in 2020 while
riskarbiter2020 was being implemented, on what tags, logs, comments and (above all) more flexible
approvals would need. It is kept as written. Where it was silent or ambiguous, this is what was chosen and why.
Each is cheap to revisit; the ones marked ⚑ are the ones most worth a second opinion.

## Vocabulary

| note | read as |
|---|---|
| `icuvt` / `icuv` | id, created_at, updated_at, version (+ token) |
| `effectivity` / `effectivicity` | effective-dating: when it is true, as distinct from when it was recorded |
| `feed`, `Fsi, shard` | feedz-style change feed: `feed_sync_id` + `shard` on the row |
| `External_entity_type_id`, `source_entity_id` | `(type) + the owning service's own primary key`, as a string |

## Entities

- **"Entities (not sure where I was going with this)" + CRUD** → the CRUD applies to the two
  `external_*` tables. There is no third, TLC-native "entity" concept.
- ⚑ **Types are registered, records are vivified on first write.** Requiring services to pre-register
  every row would make TLC useless; letting them mint *types* by typo would rot it.
- **Destroy is soft** everywhere. A tag service that forgets history on delete can't answer "was tagged".

## Tags

- **"taggable_id ..OR!.. External_entity_id"** → a tagging targets either a TLC-internal object
  (`taggable_id` holds its token: thread, comment, approvable) or an external record. Enforced by CHECK.
- ⚑ **Taggings are an operation log, not a mutable join table.** `Operation: ADD | REMOVE` plus
  effectivity only makes sense that way, and it is what makes "was tagged" exact rather than
  best-effort. Cost: state is folded at read time (see `docs/tags.md`, Cost model).
- **"effectivity" on taggings is a single `effective_at`**; an interval's end is the matching REMOVE.
  On *tags* it is a window (`effective_at`..`ineffective_at`): retiring a tag stops new use without
  erasing old use.
- **`known_at`** was not in the note. With backdating allowed, "what did we believe at the time" is
  a different question from "what was true at the time", and the append-only log answers both for
  one extra predicate.
- **`ldap` and `Service`** stay two columns: who, and through what.

## Logs

- Taken literally: six columns. Entries can be about a record, a type, or nothing.
- TLC also logs its own tag/comment/approval events here (`source` = `tlc.*`) — it gives approvals
  an audit trail for free and makes the L useful to the T, C and A.
- ⚑ Logs attach only to **external** records (the note's columns), not to threads/approvables.

## Comments

- **`Cached_comments`** → a maintained count (+ `last_comment_at`), not a cached blob of rendered comments.
- **`thread_id - easily allow`** (truncated in the note) → assumed "…easily allow fetching a whole
  thread": comments carry `thread_id` directly and `thread_path` orders the tree.
- **`thread_path`** → materialized path of zero-padded ids. Chosen over nested sets (cheap appends)
  and over recursive CTEs (one index range scan to render).
- **likes with `unliked_at`** → one row per like *episode*, so unlike→like keeps history.
- **"Standard: nouns thread | comment; verbs create/read/update/destroy; verbs mk/ls/edit/rm"** →
  one REST surface; both verb vocabularies are aliases in the CLI (`tlc comment mk` ≡ `tlc comment create`).
- **"iframe"** → `/iframe?target=…`, which grew to show tags, approvals and logs for the target too
  (`panels=comments` restores the narrow reading).
- rm of a comment tombstones (erases content, keeps the row) so the tree doesn't lose its middle.

## Approvals

- **States** `INITIAL / PENDING, APPROVED, EXECUTED, DECLINED` → `INITIAL` and `PENDING` are one state.
- ⚑ **APPROVED is not sticky** until EXECUTED: withdrawing an approval can reopen it. The alternative
  (approval is a one-way door) makes "oops, wrong PR" unfixable short of declining.
- **`requested_by`** was missing from the note's approvable; "any non-self approval" is undefined without it.
- ⚑ **"Approval from ≥2 groups" needs distinct people**, decided by maximum matching. The note
  doesn't say; the lenient reading lets one person in two groups approve alone.
- ⚑ **Self-approval never counts toward group requirements** and is refused outright (403) rather
  than silently ignored. Only `ANY_APPROVAL` admits it — otherwise it wouldn't differ from
  `ANY_NON_SELF_APPROVAL`.
- **"≥2 groups"** generalized to `MIN_GROUPS` with `min_groups` (default 2).
- ⚑ **Group membership is snapshotted on the approval** (`via_groups`), not re-read at evaluation.
- **"alternatives to approval: Slack notification, email notification"** (two bullets were blank)
  → requirement types that approve immediately after queueing the notification.
- **"Admins only? (True)"** → only *admins of* the directory group count for that rule group.
- **"slack notification channel"** → also notified for ordinary approval rules (requested / approved /
  declined / executed), not just notify-only ones. An `email` column sits beside it.
- **EXECUTED** is set by an explicit, exactly-once *claim* from the requesting side; TLC never
  executes anything itself.
- ⚑ **Rules are not versioned** (approvables are: see below). An approvable follows its rule by token; changing a rule's
  requirement under open approvables is refused unless forced. Snapshotting the rule onto each
  approvable is the stricter alternative.
- **"Directory group"** → looked up in a local stand-in table; the real directory is an integration point.

## Revisions, checks, agents (not in the note; added when "approve what, exactly?" had no answer)

Everything here is a default or a mechanism, not a policy: see the top of [approvals.md](approvals.md).

- ⚑ **One approvable, many revisions**, rather than a new approvable per version. Keeps the request,
  its discussion and its history under one token. Cost: parties and impacts stay as resolved at filing.
- ⚑ **Revision labels are opaque and ordered by arrival.** TLC does not parse or compare them, so a
  counter, a hash and a commit all work, and "is `b72c` after `a1f3`" is never TLC's question.
- **`revision` is a field, not part of the target string.** `…/1234@3` is how it is written and shown;
  parsing it out of `target` would break source ids that contain `@`.
- ⚑ **`on_revision` defaults to `DISMISS`.** The safe end. `KEEP` exists because re-collecting
  sign-offs for a typo has its own cost.
- ⚑ **Approve and execute take the revision the caller means** and 409 on a mismatch, instead of
  trusting that nothing moved between render and click.
- ⚑ **Checks are reported, never run.** TLC has no access to the source service's data. The rule can
  hold the bound, so the threshold is policy; the measurement is trusted the way declared impacts are.
- ⚑ **Only services report checks, only principals approve.** A person cannot mark a check passed;
  a service cannot approve.
- ⚑ **No waiver of a failed check.** Edit the rule (forced) or revise the change. A waiver would be a
  second approval path and wants its own rule.
- **A check that never reports stays `MISSING` forever**; there is no timeout.
- ⚑ **Agents are marked in TLC's directory stand-in, with an operator**, not inferred from a name or
  a token claim. `agent_approvals` defaults to `DENY`.
- ⚑ **An agent and its operator are one voice** on any one request. Stricter than treating the agent
  as an independent reviewer; looser than never counting it.
- ⚑ **Comments are stamped with a revision, never marked outdated.** TLC records; it does not judge
  whether an old comment still applies.
- **The requirement list is a menu** (`tlc.requirements` narrows it; the enum is where it grows),
  not a claim that these seven are what approval means.

## Ownership (not in the note; added when "owner" turned out to be plural)

- ⚑ **Ownership is per aspect, and the owner is a directory group**, never a person. People come
  and go through the directory; the claim stays put.
- ⚑ **A second OWNER claim is recorded, not refused** → `CONTESTED`. Under an aspect rule every
  claimant must consent. The alternative (first claim wins, 409 the rest) makes the table tidy and wrong.
- ⚑ **First claim on an UNOWNED cell wins**, by any member of the claiming group. No approval to
  *become* an owner. Cheap to say, visible in the log; tighten by putting claims behind an approval
  rule if that gets abused.
- ⚑ **A record-level OWNER overrides the type default** for that aspect rather than contesting it.
- ⚑ **Expected aspects are declared, not inferred from claims**, so coverage has a denominator
  someone chose.
- **LAPSED is a status, not an expiry.** A stale owner still owns and is still asked; it just shows.
- ⚑ **Parties are frozen at filing.** Ownership changing mid-request does not re-route it.
- ⚑ **Rules bind to the action, not the entity.** A rule is an action class mapping aspects to
  consent/notify; it is never attached to a record or type, and the source service chooses it when
  filing. `applies_to` limits which rules a type takes, and listing a type on any rule closes it to
  the rest; TLC still cannot tell which of two listed rules matches what the service then does.
- ⚑ **Declared impacts decide who is asked.** A rule aspect with a threshold only asks its owner
  when the source service declares an impact above it; below, the owner is told the number instead.
  Unknown and undeclared count as above. The declaration is trusted (service callers only) and is
  not checked against anything: the control is that the bypassed group sees it.
- ⚑ **Nothing above any threshold means approved at filing**, with nobody asked. If that is too
  loose, keep one aspect without a threshold, or add a fixed group to the rule.
- ⚑ **One scalar per aspect, sign ignored.** No distributions, no asymmetric thresholds
  (+50 flags fine, -50 not), no per-owner thresholds.
- ⚑ **An unowned CONSENT aspect blocks the request** (409) unless the rule names a `fallback_group`.
- ⚑ **Break-glass is open to any owner in the downstream closure**, including owners of things two
  hops away. Narrower (owners of the target only) is safer and slower.
- ⚑ **Only the pulling side can revert early**; everyone else withholds and the TTL reverts.
- **Ownership does not inherit along edges.** Edges answer "who is affected", not "who owns".
  Type defaults are the only inheritance.
- **Edges are not effective-dated**; the source service is the system of record for the graph.
- **"Which direction is safe" is not modelled** — see the end of `docs/ownership.md`.

## Things the note didn't ask for, flagged so they can be cut

`known_at` · internal-object tagging · `/api/summary` · approvables and threads feeds · `client_id`
idempotency · `awaiting=me` · the outbox · the console.
