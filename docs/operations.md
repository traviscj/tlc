# Operations

## Build / run / test

```sh
. bin/activate-hermit                 # Hermit pins corretto 21; gradle wrapper pins 8.13. Needed in every shell.
./gradlew run                         # foreground on :8790
scripts/dev-restart.sh                # installDist + restart in background; waits for the port; log → build/tlc-run.log
./gradlew test                        # needs database tlc_test on the local mysqld
TLC_CONFIG=$PWD/tlc-local.yaml ./gradlew run     # layer overrides (see tlc-local.yaml.example)
```

The app runs as misk `DEVELOPMENT`, which makes `SchemaMigratorService` apply pending migrations at
startup — right for a single self-hosted instance. Migrations are **one statement per file**
(misk-jdbc executes each file as a single JDBC statement).

## Config (`src/main/resources/tlc-common.yaml`, overridable via `TLC_CONFIG`)

| key | default | |
|---|---|---|
| `web.port` | 8790 | |
| `data_source.*` | mysql root@127.0.0.1:3306 / `tlc_dev` | tests use `tlc_test` |
| `tlc.admins` | `[traviscj]` | ldaps with the admin capability |
| `tlc.auto_register_entity_types` | false | true: unknown `service/entity` refs register the type |
| `tlc.requirements` | unset (all) | the requirement types offered when a rule is written, e.g. `[ALL_GROUPS, MIN_GROUPS]`; see [approvals](approvals.md#requirements-a-menu) |
| `tlc.background_interval_ms` | 500 | feed publish + notification drain period |
| `tlc.feed_shards` | 16 | changing it only affects rows written afterwards |
| `tlc.max_comment_depth` | 16 | |
| `tlc.slack_webhook_url` | unset | set to actually post SLACK notifications |
| `tlc.ownership_lapse_days` | 90 | an ownership claim not re-affirmed for this long reads `LAPSED` |
| `tlc.ratify_ttl_seconds` | 14400 | default life of a break-glass action before it is `REVERTED`; expiry runs on the background loop |
| `tlc.public_url` | `http://localhost:8790` | used in notification links |

## Auth — read this before exposing it

Identity is **whatever the `X-TLC-User` / `X-TLC-Service` headers say**. TLC checks their shape, not
their truth. That is the normal arrangement behind an authenticating proxy / service mesh that
overwrites those headers, and it is wide open without one: anyone who can reach the port can be
anyone, including an admin. The bundled pages accept `?user=` for the same reason. Bound to
localhost on a dev box it's fine; anywhere else, front it and strip client-supplied copies of both
headers at the proxy.

Capabilities: every identified caller has `tlc_user`; ldaps in `tlc.admins` also have `tlc_admin`.
`AccessControlModule` is installed (misk's `@Authenticated` is inert without it); the test suite
asserts the 401/403 paths.

## Background work

One daemon thread (`tlc-background`), `scheduleWithFixedDelay`, so runs never overlap:
1. publish feeds (assign `feed_sync_id` to changed rows);
2. drain the notification outbox (≤100 per pass, 5 attempts each, then `FAILED`).

Both are safe to run from several instances — the publisher serializes on the `sequences` row —
but notification delivery would then be more-than-once more often. One instance is the design point.

## Watching it

- `GET /_feeds/status` — `pending` should hover at 0; stuck > 0 with `publishing_enabled` means the
  background loop is failing (see the service log).
- `GET /api/notifications?status=PENDING` / `FAILED`.
- `GET /api/info` — row counts.
- The console at `/` shows all three.

## Growth

Nothing is ever hard-deleted. `log_entries` and `taggings` are the tables that grow; both are
append-only with time-leading secondary keys, so range-partitioning or pruning by `timestamp_ms` /
`created_at_ms` is straightforward when it matters. Pruning `taggings` changes history; prune only
operations wholly superseded before your horizon.

## Deploying (not done)

Runs on the Mac against the native mysqld. For i711: `./gradlew installDist` gives
`build/install/tlc`; it needs a JDK 21, a MySQL database, `TLC_CONFIG` with real `data_source`,
`public_url` and `admins`, and a Caddy vhost that authenticates and sets `X-TLC-User` — do not
publish it ungated.
