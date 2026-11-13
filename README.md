# TLC — tags, logs, comments (& approvals & ownership & entities)

> Names like `sentinel`, `ATO_VELOCITY`, `trust-infra` and `alice` are a made-up example company: [who and what they are](docs/example-world.md).

One small service that gives *other* services the cross-cutting stuff they keep re-building badly:
tag a row, hang a log on it, discuss it, say who owns which aspect of it, and gate a change to it on
an approval rule. The source
service keeps its own tables; TLC only ever learns the triple `service/entity/id`.

Kotlin + [misk](https://github.com/cashapp/misk), raw SQL over misk-jdbc, MySQL. JSON over HTTP.
Port **8790**. Built from a one-page note, [`docs/2020_scribble.md`](docs/2020_scribble.md), taken in 2020 while
riskarbiter2020 was being implemented and its approvals needed to be more flexible than they were;
[`docs/decisions.md`](docs/decisions.md) records how each line of it was read.

```mermaid
flowchart LR
  sentinel["sentinel<br/>(a source service)"]
  subgraph TLC
    direction TB
    t1["entities · tags · logs"]
    t2["threads · comments · likes"]
    t3["rules · groups · approvables"]
    t4["ownership · aspects · edges"]
  end
  sentinel -- "tag / log / comment / request" --> TLC
  TLC -- "iframe src=/iframe?target=…" --> sentinel
  TLC -- "/_feeds/fetch/approvables<br/>APPROVED → claim + apply<br/>REVERTED → undo" --> sentinel
  TLC --> outbox --> slack["Slack / log"]
  humans["humans: console, iframe, tlc CLI"] --> TLC
```

## Run it

```sh
. bin/activate-hermit                      # JDK 21 (every new shell)
mysql -uroot -h127.0.0.1 -e 'create database if not exists tlc_dev; create database if not exists tlc_test'
./gradlew run                              # foreground; migrations apply at startup
scripts/dev-restart.sh                     # …or: rebuild + restart in the background (log: build/tlc-run.log)
scripts/seed.sh                            # the sentinel/proposed_changes/1234 scenario
open http://localhost:8790/                # console        (type an ldap into "as")
open http://localhost:8790/demo            # a fake source-service page embedding the iframe
scripts/demo.sh                            # all nine narrated demos (≈25 s; re-runnable, each run uses fresh ids)
./gradlew test                             # 25 tests: 21 over real HTTP + MySQL, 4 on the approval engine
```

Start over: stop the server, `mysql -uroot -h127.0.0.1 -e 'drop database tlc_dev; create database tlc_dev'`, start it, re-seed.

`tlc.admins` (default `[traviscj]`) may write approval rules and the group directory. Identity is the
`X-TLC-User` / `X-TLC-Service` header — **trusted, not verified**; see `docs/operations.md` before
putting this anywhere a stranger can reach.

## Thirty seconds of API

```sh
H='-H X-TLC-User:alice -H Content-Type:application/json'; U=localhost:8790; E=sentinel/proposed_changes/1234
curl $H $U/api/taggings/tag  -d "{\"target\":\"$E\",\"tag\":\"urgent\",\"create_tag\":true}"
curl $H "$U/api/taggings/is-tagged?target=$E&tag=urgent"                       # is tagged
curl $H "$U/api/taggings/is-tagged?target=$E&tag=urgent&at=2026-01-15T00:00:00Z" # was tagged (then)
curl $H $U/api/logs          -d "{\"target\":\"$E\",\"level\":\"WARN\",\"message\":\"backtest sample small\"}"
curl $H $U/api/threads       -d "{\"target\":\"$E\",\"title\":\"is 20 right?\"}"
curl $H $U/api/approvables   -d "{\"target\":\"$E\",\"rule\":\"AR-6392\",\"context\":\"12 -> 20\"}"
curl $H "$U/api/summary?target=$E"                                             # everything about it
```

Same thing through the CLI (`scripts/tlc --help`); both verb sets from the note work on every noun:

```sh
tlc thread mk "is 20 right?" --target $E      # == tlc thread create …
tlc comment ls T-…  |  tlc comment edit C-… "…"  |  tlc comment rm C-…  |  tlc comment like C-…
tlc tag is $E urgent && echo tagged           # exit status is the answer
tlc ap approve AP-… ; tlc ap execute AP-…     # execute exits 3 if someone else already claimed it
```

## Ownership (added after the note)

"Owner of a rule" turned out to be three different teams depending on what broke, so ownership is
per **aspect** of a record ([docs/ownership.md](docs/ownership.md)):

```sh
tlc own expect sentinel/rules disposition --desc "…"  # every rule should have an owner for this, and what that means
tlc --as bob  own claim 'sentinel/rules/*' availability trust-infra    # type-wide default
tlc --as olga own claim sentinel/rules/ATO_VELOCITY disposition trust-ops   # per record
tlc own of sentinel/rules/ATO_VELOCITY [--at 2026-10-01T00:00:00Z]       # who owns what (then)
tlc own coverage ; tlc own gaps                     # % of cells with exactly one live owner; the rest
tlc edge mk sentinel/signals/velocity_24h sentinel/rules/ATO_VELOCITY     # signal -> rule -> trigger -> hook
tlc own affected sentinel/signals/velocity_24h       # who is on the hook downstream if it breaks
tlc rule mk --requirement ALL_GROUPS --desc "disable a rule" --aspect availability --aspect disposition --aspect efficacy:notify
tlc --service sentinel --as alice ap mk sentinel/rules/VEL_2 AR-3005 --impact availability=0 --impact disposition=-18:flags/day
                                                    # declared impact under the rule's threshold: that owner is told, not asked
tlc --as bob ap mk sentinel/signals/velocity_24h AR-3003 --ratify   # break-glass: done already; reverts unless ratified
```

## The note → the code

The note is [`docs/2020_scribble.md`](docs/2020_scribble.md), kept as written.

| note | where | doc |
|---|---|---|
| Entities: create / read / update / destroy | `Entities.kt` — types explicit, records vivified on first reference | [entities](docs/entities.md) |
| external entity types, external entities, tlcers | `v001`–`v003` | [data model](docs/data-model.md) |
| tags (icuvt + effectivity), taggings (ADD\|REMOVE, ldap, service, feed) | `Tags.kt`, `v004`–`v005` | [tags](docs/tags.md) |
| create tag · tag/untag · is tagged · was tagged | `/api/tags`, `/api/taggings/*` | [tags](docs/tags.md) |
| log entries (timestamp, level, message, source, type, entity) | `Logs.kt`, `v006` | [logs](docs/logs.md) |
| threads (cached_comments), comments (fsi, shard, thread_path), likes | `Comments.kt`, `v007`–`v009` | [comments](docs/comments.md) |
| iframe · thread\|comment × create/read/update/destroy × mk/ls/edit/rm · like | `/iframe`, `/api/threads`, `/api/comments`, `scripts/tlc` | [embedding](docs/embedding.md) |
| approvable, approvals, approval rule, rules→groups | `Approvals.kt`, `v010`–`v015` | [approvals](docs/approvals.md) |
| "alternatives to approval": Slack / email notification | `SLACK_NOTIFICATION` / `EMAIL_NOTIFICATION` requirements + outbox | [approvals](docs/approvals.md#notifications) |
| "+ feed", "fsi, shard" | `Feeds.kt`, `/_feeds/fetch/{taggings,threads,comments,approvables,ownerships}` | [feeds](docs/feeds.md) |
| *(not in the note)* ownership per aspect, coverage, dependency edges, aspect-resolved rules, break-glass | `Ownership.kt`, `Approvals.kt`, `v017`–`v023` | [ownership](docs/ownership.md) |

Every endpoint: [docs/api.md](docs/api.md). Config, auth, deploy notes: [docs/operations.md](docs/operations.md).

## Demos

| script | shows |
|---|---|
| `scripts/demos/01-entities.sh` | unregistered types refused; records vivify; soft destroy + revive |
| `scripts/demos/02-tags.sh` | idempotent tag/untag; backdating; was-tagged windows; `known_at`; retiring a tag; tagging a thread |
| `scripts/demos/03-logs.sh` | append/batch; level, source-prefix, substring filters; TLC's own audit entries; tail |
| `scripts/demos/04-comments.sh` | nested replies in path order; like episodes; author-only edit; tombstones |
| `scripts/demos/05-approvals.sh` | every requirement type; the distinct-people matching; withdraw; admins-only; notify-only; decline; service-on-behalf-of; forced rule change; outbox; audit trail |
| `scripts/demos/06-feeds.sh` | a source service tailing `approvables` and claiming APPROVED changes exactly once |
| `scripts/demos/07-curl.sh` | the whole flow in bare curl against a second service (`billing/invoices`) |
| `scripts/demos/08-ownership.sh` | aspects; type defaults; CONTESTED; time travel; coverage + gaps; blast radius over the graph; rules that resolve approvers from owners; `applies_to`; declared impacts vs thresholds; break-glass ratified and reverted |
| `scripts/demos/09-revisions-checks-agents.sh` | a request pinned to `…@3`; checks with the bound on the rule; discussion on the request; revise to `@4` dismissing approvals; execute only the approved revision; a second rule that keeps approvals and admits agents |
| `/demo` in a browser | a "sentinel" page embedding the iframe; switch the viewer between alice/bob/carol |
| `scripts/ui_smoke.py` | Playwright click-through of the iframe and console (optional; needs Playwright) |

![the iframe widget](docs/img/iframe.png)

## Layout

```
src/main/kotlin/tcj/tlc/   TlcApp TlcModule TlcConfig · Db (tx + SQL helpers) · Auth (actors, tlcers)
                           Entities · Tags · Logs · Comments · Approvals (directory, rules, engine, approvables, outbox)
                           Ownership (claims, cells, coverage, edges, party resolution)
                           Feeds (publisher, transport, background loop) · Web (pages, /api/info, /api/summary)
src/main/resources/        migrations/v001…v023 (one statement per file) · web/{console,iframe,demo}.html tlc.{js,css}
src/test/kotlin/tcj/tlc/   TlcServiceTest (HTTP + MySQL) · ApprovalEngineTest
scripts/                   tlc (CLI) · seed.sh · demo.sh · demos/ · dev-restart.sh · ui_smoke.py
```

## Not done (on purpose, so far)

- **Real auth and a real group directory.** Headers are trusted; `directory_memberships` is a local stand-in for the company directory (Okta, Google Groups, LDAP).
- **Email transport.** EMAIL notifications land in the outbox and the service log, nothing is sent. Slack sends only if `slack_webhook_url` is set (never exercised against real Slack).
- **Retention / hard delete.** Everything is soft-deleted or append-only; logs grow forever.
- **The dependency graph is hand-fed.** Nothing syncs edges from a real rule engine; `POST /api/edges` is the integration point. Edges are current-state only.
- **Safe direction is not modelled.** Break-glass assumes the action is reversible and leaves "is off safer than on" to the rule author (see `docs/ownership.md`).
- **Scale shortcuts**, each marked in code: tag state is folded from the op log per query; ownership coverage folds a whole type in memory (capped at 20k records); "my queue" scans up to 1000 pending approvables; lists are `limit`-capped without cursors (feeds and logs have cursors).
- Not deployed anywhere; runs on this Mac against the native mysqld.
