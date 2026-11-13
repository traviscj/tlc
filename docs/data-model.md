# Data model

> Names like `sentinel`, `ATO_VELOCITY`, `trust-infra` and `alice` are a made-up example company: [who and what they are](example-world.md).

23 migrations (one statement each), 22 tables, in `src/main/resources/migrations/`. MySQL 8+/9
(CHECK constraints and stored generated columns are relied on).

## Conventions

| shorthand in [the note](2020_scribble.md) | columns |
|---|---|
| **i c u v** | `id` BIGINT auto-inc · `created_at_ms` · `updated_at_ms` · `version` (starts 1, +1 per update) |
| **t** | `token` — public id, `<PREFIX>-<12 random [a-z2-9]>`; ids never leave the service |
| **effectivity** | `effective_at_ms` (+ `ineffective_at_ms` on tags) — when it is true in the world, vs. `created_at` = when TLC learned it |
| **feed / fsi, shard** | `feed_sync_id` BIGINT NULL UNIQUE (NULL = changed, unpublished) · `shard` INT |
| soft delete | `deleted_at_ms` NULL |

All times are epoch milliseconds in the database and ISO-8601 UTC on the wire (inputs accept either).

Token prefixes: `ET-` type · `E-` entity · `U-` tlcer · `TG-` tag · `TGG-` tagging · `T-` thread ·
`C-` comment · `L-` like · `AR-` rule ([`AR-6392`](example-world.md#AR-6392): 4 digits when generated, or caller-chosen) ·
`AP-` approvable · `A-` approval · `N-` notification · `OW-` ownership claim · `EG-` edge.

## Tables

```
external_entity_types ─1:N─ external_entities ─┬─ taggings ─N:1─ tags
  (source_service,            (type_id,        ├─ log_entries
   source_entity) UNIQUE       source_entity_id├─ threads ─1:N─ comments ─1:N─ likes
                               ) UNIQUE        └─ approvables ─1:N─ approvals
                                                     │N:1
                              directory_memberships   approval_rules ─1:N─ approval_rule_groups
                              tlcers   notifications   sequences                └1:N─ approval_rule_aspects

external_entity_types ─1:N─ ownership_aspects        external_entities ─N:M─ external_entities  (entity_edges)
external_entities | types ─1:N─ ownerships           approvables ─1:N─ approvable_parties      directory_groups
```

| table | conventions | columns beyond them | keys / invariants |
|---|---|---|---|
| `external_entity_types` | icuvt, soft | `source_service`, `source_entity`, `description`, `url_template` | UNIQUE (service, entity) |
| `external_entities` | icuvt, soft | `external_entity_type_id`, `source_entity_id` (≤191), `display_name` | UNIQUE (type, source id) |
| `tlcers` | icuvt | `ldap`, `display_name`, `last_seen_at_ms` | UNIQUE ldap |
| `tags` | icuvt + effectivity | `tag_name`, `description`, `created_by` | UNIQUE tag_name |
| `taggings` | icuvt + feed + effective_at | `taggable_id` (internal token) **xor** `external_entity_id`, `target_key`, `tag_id`, `operation`, `ldap`, `service`, `note` | CHECK exactly one target; CHECK op ∈ ADD/REMOVE; KEY (target_key, tag_id, effective_at, id); **append-only** |
| `log_entries` | id, created_at | `timestamp_ms`, `level` (0–5), `message`, `source`, `external_entity_type_id`, `external_entity_id` | KEYs on (entity, ts), (type, ts), (ts); **append-only** |
| `threads` | icuv t + feed, soft | `title`, `body`, `creator`, `subject_token` xor `external_entity_id` (or neither), `target_key`, `cached_comments`, `last_comment_at_ms` | KEY (target_key, id) |
| `comments` | icuv + feed, soft | `comment_token`, `thread_id`, `parent_comment_id`, `thread_path`, `depth`, `content`, `author`, `commented_at_ms`, `edited_at_ms`, `cached_likes` | KEY (thread_id, thread_path) |
| `likes` | icuvt | `comment_id`, `liked_by`, `liked_at_ms`, `unliked_at_ms`, `active` (generated: 1 while open, else NULL) | UNIQUE (comment, liker, active) ⇒ one open episode |
| `approval_rules` | icuvt, soft | `description`, `requirement`, `min_groups`, `created_by` | |
| `approval_rule_groups` | icuv | `approval_rule_token`, `group_name`, `directory_group`, `admins_only`, `slack_channel`, `email` | UNIQUE (rule, group) |
| `approvables` | icuvt + feed | `external_entity_id`, `approval_context`, `approval_rule_token`, `approval_state`, `requested_by`, `requested_via_service`, `client_id`, `approved_at_ms`, `executed_at_ms`, `executed_by`, `declined_at_ms`, `declined_by`, `decline_reason` | CHECK state; UNIQUE client_id |
| `approvals` | icuvt | `approvable_id`, `approvable_token`, `approved_by`, `approved_at_ms`, `unapproved_at_ms`, `via_groups`, `comment`, `active` (generated) | UNIQUE (approvable, approver, active) |
| `directory_memberships` | icuv | `directory_group`, `ldap`, `is_admin` | UNIQUE (group, ldap) — directory stand-in |
| `notifications` | icuvt | `channel`, `destination`, `event`, `approvable_token`, `body`, `status`, `detail`, `attempts`, `sent_at_ms` | outbox; KEY (status, id) |
| `sequences` | | `name`, `value`, `publishing_enabled` | one row per feed |
| `ownerships` | icuvt + feed + effective_at | `external_entity_type_id`, `external_entity_id` (NULL = type-wide), `scope_key` (`E:<id>` \| `ET:<type id>`), `aspect`, `owner`, `role`, `operation`, `ldap`, `service`, `note` | CHECK role ∈ OWNER/STAKEHOLDER, op ∈ CLAIM/RELEASE/AFFIRM; KEY (scope_key, aspect, effective_at, id); **append-only** |
| `ownership_aspects` | icuv, soft | `external_entity_type_id`, `aspect`, `description`, `created_by` | UNIQUE (type, aspect) — the coverage denominator |
| `entity_edges` | icuvt | `from_entity_id`, `to_entity_id`, `kind`, `created_by`, `note` | UNIQUE (from, to, kind); CHECK from ≠ to; hard delete |
| `directory_groups` | icuv | `directory_group`, `slack_channel`, `email` | UNIQUE group — directory stand-in, contact half |
| `approval_rule_aspects` | icuv | `approval_rule_token`, `aspect`, `mode`, `scope`, `fallback_group`, `threshold`, `unit` | UNIQUE (rule, aspect) |
| `approval_rule_types` | id, created_at | `approval_rule_token`, `external_entity_type_id` | UNIQUE (rule, type) — `applies_to` |
| `approvable_impacts` | id, created_at | `approvable_id`, `aspect`, `declared`, `magnitude` (NULL = unknown), `unit`, `note`, `rule_mode`, `threshold`, `effect`, `reason` | UNIQUE (approvable, aspect) — frozen at filing |
| `approvable_parties` | id, created_at | `approvable_id`, `directory_group`, `mode`, `aspects`, `status`, `source`, `via`, `slack_channel`, `email` | UNIQUE (approvable, group) — frozen at filing |

`approvables` gained `mode` (`APPROVE` \| `RATIFY`), `expires_at_ms`, `acting_group` in `v023`, and
three states (`ACTED`, `RATIFIED`, `REVERTED`). A revert stamps `declined_at_ms / declined_by /
decline_reason`.

Columns the note lists that are here under the same name: everything in `taggings`, `threads`
(`token, title, body, creator, cached_comments`), `comments` (`comment_token, thread_id,
parent_comment, thread_path, content, author, commented_at`), `likes`, `approvables`, `approvals`,
rules and rule groups. Additions are the ones the behaviour needs: `requested_by` (non-self needs a
self), `via_groups` (membership snapshot), `client_id` (idempotent create), `target_key` (one
indexable column for "entity or internal token"), `depth`, the two `cached_*` counters and the
generated `active` columns.

## `target_key`

`taggings` and `threads` can point at either an external record or an internal object. Both nullable
columns are kept (as the note has them), plus `target_key` = `E:<entity id>` or the internal token,
so every lookup is a single-column prefix of one index instead of an `OR`.

## Locking

Writes that must not interleave take one row lock first, always in the same order, so there is no
lock-order inversion: tag ops lock the **tag** row; comment writes lock the **thread** row; likes
lock the **comment** row; approval actions lock the **approvable** row (rule edits lock the rule,
then its open approvables in id order); the feed publisher locks the feed's **sequences** row;
ownership writes lock the record's **entity type** row (coarse, but claims are rare).
