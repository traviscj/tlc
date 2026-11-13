# API

JSON over HTTP. Identity: `X-TLC-User: <ldap>` and/or `X-TLC-Service: <name>` on every `/api` and
`/_feeds` call (401 without). POST/PUT/PATCH need `Content-Type: application/json` and a JSON body
(`{}` is fine). Timestamps: ISO-8601 UTC out; ISO or epoch-ms in. Nulls are omitted from responses.
`limit` defaults to 200, max 1000.

Errors are plain text with the status: **400** malformed · **401** no identity · **403** not allowed
(body says why) · **404** unknown ref · **409** wrong state / conflict.

`target` = `service/entity/id` | `E-…` | `T-…` | `C-…` | `AP-…` (see `docs/entities.md`). (A) = admin only.

## Entities

| | | |
|---|---|---|
| POST | `/api/entity-types` | `{source_service, source_entity, description?, url_template?}` — 409 if registered; revives a destroyed one |
| GET | `/api/entity-types[?include_deleted=true]` | `{entity_types:[…]}` |
| GET · PATCH · DELETE(A) | `/api/entity-types/get?type=` · `/api/entity-types?type=` | `type` = `ET-…` or `service/entity`; PATCH `{description?, url_template?}` |
| POST | `/api/entities` | `{target}` or `{source_service, source_entity, source_entity_id}`, `display_name?` — find-or-create |
| GET | `/api/entities?type=&q=&include_deleted=&limit=` | `{entities:[…]}` newest first |
| GET · PATCH · DELETE | `/api/entities/get?target=` · `/api/entities?target=` | PATCH `{display_name}`; DELETE is soft |
| GET | `/api/summary?target=&log_limit=` | `{target, known, entity?, tags[], threads[], approvables[], logs[], ownership[]}` |
| GET | `/api/tlcers` · `/api/me` | everyone who has acted · the caller (`ldap, service, admin, directory_groups`) |
| PATCH | `/api/me` | `{display_name}` |
| GET | `/api/info` | settings, row counts, requirement / state / level enums, feeds, token prefixes |

## Tags

| | | |
|---|---|---|
| POST | `/api/tags` | `{tag_name, description?, effective_at?}` — 409 if exists; un-retires a retired one |
| GET | `/api/tags[?include_ineffective=true]` · `/api/tags/{name}` | includes `tagged_now` |
| PATCH · DELETE | `/api/tags/{name}` | `{description}` · retire (`?at=`) |
| POST | `/api/taggings/tag` · `/api/taggings/untag` | `{target, tag, effective_at?, note?, create_tag?}` → `{changed, tagged, tagged_now, tagging?}` |
| GET | `/api/taggings/is-tagged?target&tag[&at][&known_at]` | `{tagged, since?, by?, tagging?}` |
| GET | `/api/taggings/was-tagged?target&tag[&from&to][&known_at]` | `{was_tagged, throughout, intervals:[{from,to?,added_by,removed_by?}]}` |
| GET | `/api/taggings/tags?target[&at][&known_at]` | tags on a target |
| GET | `/api/taggings/targets?tag[&at][&known_at][&limit]` | targets carrying a tag |
| GET | `/api/taggings?target=&tag=&limit=` | raw operations, newest first |

## Logs

| | | |
|---|---|---|
| POST | `/api/logs` | one `{message, level?, source?, target?, entity_type?, timestamp?}` or `{entries:[…]}` (≤1000) → `{appended, first_id, last_id}` |
| GET | `/api/logs?target=&entity_type=&level=&source=&q=&since=&until=&after_id=&before_id=&order=&limit=` | `{entries:[…], cursor}` |

## Comments

| | | |
|---|---|---|
| POST | `/api/threads` | `{title, body?, target?, revision?}`. `target` may be a record or an internal token (`AP-…`). The thread, and each later comment, is stamped with the target's revision at the time |
| GET | `/api/threads?target=&creator=&include_deleted=&limit=` | most recently active first |
| GET · PATCH · DELETE | `/api/threads/{T}` | GET → `{thread, comments:[…depth-first…]}` |
| POST | `/api/threads/{T}/comments` · `/api/comments` | `{content, parent_comment?}` · `{thread?, parent_comment?, content}` |
| GET | `/api/comments?thread=` or `?author=` | |
| GET · PATCH · DELETE | `/api/comments/{C}` | PATCH `{content}`; DELETE tombstones |
| POST | `/api/comments/{C}/like` · `/unlike` | → `{changed, liked, likes}` |
| GET | `/api/comments/{C}/likes[?include_unliked=true]` | |

## Approvals

| | | |
|---|---|---|
| GET | `/api/directory[?group=]` | directory stand-in |
| GET · PUT(A) · DELETE(A) | `/api/agents` · `/api/agents/{name}?operator=&description=` | which principals are agents, and who operates each |
| PUT(A) · DELETE(A) | `/api/directory/{group}/members/{ldap}[?admin=true]` | |
| PUT(A) · GET | `/api/directory/{group}/contact?slack_channel=&email=` · `/api/directory/contacts` | where a group is reached (for ownership-resolved parties) |
| POST(A) | `/api/approval-rules` | `{token?, description, requirement, min_groups?, groups:[{group, directory_group, admins_only?, slack_channel?, email?}], aspects:[{aspect, mode?: CONSENT\|NOTIFY, scope?: TARGET\|DOWNSTREAM, fallback_group?, threshold?, unit?}], applies_to?:["service/entity"], checks?:[{name, description?, reporter?, min?, max?, unit?}], on_revision?: DISMISS\|KEEP, agent_approvals?: DENY\|ALLOW}`. `requirement` must be on the deployment's menu (`tlc.requirements`) |
| GET | `/api/approval-rules` · `/api/approval-rules/{AR}` | includes `open_approvables` |
| PATCH(A) · DELETE(A) | `/api/approval-rules/{AR}[?force=true]` | see "Changing a rule" |
| POST | `/api/approvables` | `{target, rule, revision?, context?, client_id?, requested_by?, mode?: APPROVE\|RATIFY, ttl_seconds?, impacts?:[{aspect, magnitude: number\|null, unit?, note?}]}` → view (with `parties`, `impacts`). `impacts` only from a service caller (400 otherwise); 409 if a CONSENT aspect has no owner and no fallback; 403 on RATIFY by a non-owner |
| GET | `/api/approvables?target=&state=&rule=&requested_by=&awaiting=<ldap\|me>&limit=` | |
| GET | `/api/approvables/{AP}` | `{approvable, rule, approvals[], evaluation, you?, parties[], impacts[], revisions[], threads[], check_reports[]}`. `rule.groups` includes this approvable's CONSENT parties; `evaluation` = `{satisfied, approvals_satisfied, needed, have, groups[], checks[], summary}`; each approval has `revision`, `superseded`, `stale`, `agent`, `operator` |
| POST | `/api/approvables/{AP}/approve` | `{comment?, revision?}` → `{changed, approvable, evaluation}`. 409 if `revision` is not the current one. A person, or an agent under `agent_approvals: ALLOW` |
| POST | `/api/approvables/{AP}/revise` | `{revision, context?}`: the target changed. Requesting side or admin; 409 on a reused label or a closed request. Approvals are dismissed or kept per the rule's `on_revision` |
| POST | `/api/approvables/{AP}/checks` | `{name, status?: PENDING\|PASS\|FAIL, value?, summary?, url?, revision?}`, service callers only. With bounds on the rule's check the `value` decides; 403 if the rule names another `reporter`; 409 for a result about another revision |
| POST | `/api/approvables/{AP}/unapprove` | |
| POST | `/api/approvables/{AP}/decline` | `{reason?}` — on an `ACTED` one this is *revert* (requester / acting group / admin only) |
| POST | `/api/approvables/{AP}/execute` | `{revision?, expected_version?}`: apply the change only if `changed`. 409 if `revision` is not the approved one |
| GET | `/api/notifications?approvable=&status=&limit=` | the outbox |

States: `PENDING APPROVED EXECUTED DECLINED` (ask first) · `ACTED RATIFIED REVERTED` (`mode=RATIFY`).

## Ownership

`target` here is `service/entity/id`, `E-…`, or `service/entity/*` (type-wide default). See `docs/ownership.md`.

| | | |
|---|---|---|
| POST | `/api/ownership/claim` | `{target, aspect, owner, role?: OWNER\|STAKEHOLDER, effective_at?, note?}` → `{changed, operation: CLAIM\|AFFIRM, cell}` |
| POST | `/api/ownership/release` | `{target, aspect, owner, effective_at?, note?}` → `{changed, cell}` |
| POST | `/api/ownership/transfer` | `{target, aspect, from, to, effective_at?, note?}` — 409 if `from` doesn't hold it |
| POST | `/api/ownership/affirm` | `{owner, target?, aspect?}` → `{affirmed}` |
| GET | `/api/ownership?target[&at][&known_at]` | `{cells:[{aspect, status, source, owners[], stakeholders[]}], lapse_days}` |
| GET | `/api/ownership/history?target=&aspect=&owner=&limit=` | raw claim log, newest first |
| GET | `/api/ownership/coverage[?type=][&at][&known_at]` | `{total, rows:[per type×aspect], owners:[per group]}` |
| GET | `/api/ownership/gaps?type=&aspect=&status=&owner=&limit=` | cells not `OWNED` (or the listed statuses) |
| GET | `/api/ownership/owned-by?owner=` | every cell a group holds, inherited ones included |
| GET | `/api/ownership/affected?target[&direction=down\|up][&depth]` | `{nodes:[{target, depth, via, parent, cells}], parties:[…]}` |
| GET · POST · DELETE(A) | `/api/ownership/aspects[?type=]` | expected aspects per type and what each means; POST `{type, aspect, description?}` (again to edit the description); DELETE `?type=&aspect=` |
| POST | `/api/edges` | `{from, to, kind?, note?}` — `to` is built on `from`; idempotent |
| GET · DELETE | `/api/edges[?target=]` · `/api/edges?from=&to=[&kind=]` | |

## Feeds

| | | |
|---|---|---|
| GET | `/_feeds/fetch/{feed}?after=&limit=&shard=&shard_count=` | `{feed, entries, cursor}`; feeds: `taggings threads comments approvables ownerships` |
| GET | `/_feeds/status` | |
| POST(A) | `/_feeds/publish[?feed=]` · `/_feeds/{feed}/pause` · `/_feeds/{feed}/resume` | |

## Pages (no auth; they call the API as the viewer)

`/` console · `/iframe?target=…` widget · `/demo` embed demo · `/static/tlc.js` · `/static/tlc.css`
