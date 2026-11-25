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

Two modes, selected by whether `tlc.oidc` is set.

**OIDC (`tlc.oidc` set) — for anything beyond localhost.** Identity comes from a bearer JWT issued
by an OIDC provider (written against Pocket ID). TLC verifies it itself: RS256/384/512 signature
against the issuer's JWKS, then `iss`, `aud`, `exp`, `nbf`. `X-TLC-User` on its own identifies
nobody, so nothing depends on a proxy stripping headers.

```yaml
tlc:
  oidc:
    issuer: https://id.example.net   # must equal the token's iss
    audiences: [tlc]                 # client ids whose tokens are accepted; required
  oidc_admin_groups: [tlc-admins]    # issuer groups that are admins (in addition to tlc.admins)
  oidc_services: { "<token sub>": sentinel }   # machine callers, keyed by the token's sub
```

- The token is read from `Authorization: Bearer`, else `X-Auth-Request-Access-Token`, else
  `X-Forwarded-Access-Token` (`oidc.token_headers`). The last two are what oauth2-proxy hands a
  backend, so the browser pages work behind oauth2-proxy + Caddy `forward_auth` once the vhost
  copies one of them; scripts and services send `Authorization` directly.
- A human's ldap is the `preferred_username` claim (`oidc.user_claim`), lowercased. An access token
  that carries only `sub` is resolved once through the issuer's userinfo endpoint and cached for
  `oidc.cache_seconds` (300).
- A token whose `sub` is in `oidc_services` is that service. It may name the human it acts for in
  `X-TLC-User`; a human named that way is never an admin.
- Revocation is not checked: a token is good until its `exp`.
- **Static API tokens** (`tlc.api_tokens`) are the other machine path, for callers that should not need the
  issuer to be up or do a token exchange per call (hooks, cron). `scripts/tlc mint-token <service>` prints a
  `tlc_…` token on stdout and, on stderr, the `"<sha256>": <service>` line for `tlc.api_tokens`; only the hash
  is ever stored. Such a caller is that service, may name the human it acts for in `X-TLC-User`, and is never
  an admin. Revoke by deleting the line and restarting. The CLI sends `TLC_TOKEN` (or the first line of
  `~/.config/tlc/token`) as the bearer.
- **People through an authenticating proxy** (`tlc.proxy_auth`). An oauth2-proxy session lasts days and the access
  token it began with lasts an hour, so forwarding that token gets people a 401 after the first hour. Instead the
  proxy names the signed-in user (`X-Remote-User`, groups in `X-Remote-Groups`) and sends a shared secret
  (`X-TLC-Proxy-Secret`; config holds its SHA-256). Without the secret those headers mean nothing; the proxy must
  strip all three from client requests. `deploy/Caddyfile.example` has the vhost.
- If TLC runs in a container, the issuer hostname must resolve from inside it.
- Smoke test against the real issuer:
  `OIDC_LIVE_ISSUER=https://id.example.net ./gradlew test --tests '*OidcAuthTest*' -i` in `misk-oidc`
  (add `OIDC_LIVE_AUDIENCE` and `OIDC_LIVE_TOKEN` to verify an actual token).

The verifier is the separate `misk-oidc` library (`tcj:misk-oidc`), which also ships
`OidcAuthModule`, a drop-in for other misk services: caller = username, capabilities = the token's
groups. `settings.gradle.kts` builds it from a sibling `../misk-oidc` checkout, else from its GitHub
repository as a Gradle source dependency.

**Trusted headers (`tlc.oidc` unset) — dev and tests.** Identity is whatever `X-TLC-User` /
`X-TLC-Service` say; TLC checks their shape, not their truth. Anyone who can reach the port can be
anyone, including an admin, and the bundled pages accept `?user=` for the same reason. Fine bound
to localhost; do not expose it.

Capabilities: every identified caller has `tlc_user`; admins also have `tlc_admin`.
`AccessControlModule` is installed (misk's `@Authenticated` is inert without it); the test suite
asserts the 401/403 paths in both modes.

The bundled pages ask `/api/me` who the viewer is at load. Behind an authenticating proxy that answers,
the name is shown read-only and `?user=` is ignored; in trusted-header mode it does not answer and the
pages take the name from `?user=` / the "as" box as before.

## Background work

Two daemon threads, each `scheduleWithFixedDelay` every `background_interval_ms`, so runs of one never overlap:
- `feedz` (misk-feedz): publish feeds (assign `feed_sync_id` to changed rows). At startup it checks
  that `sequences`, `feed_cursors` and every feed table have the columns it needs and refuses to
  start otherwise.
- `tlc-background`: revert unratified break-glass actions, then drain the notification outbox (≤100
  per pass, 5 attempts each, then `FAILED`).

Both are safe to run from several instances — the publisher serializes on the `sequences` row —
but notification delivery would then be more-than-once more often. One instance is the design point.

## Watching it

- `GET /_feeds/status`, or the page at `/_admin/feedz/` — `pending` should hover at 0; stuck > 0
  with `publishing_enabled` means the publish loop is failing (see the service log). Cursors show
  `lag` and when each last advanced; a cursor that is on, behind and not advancing is a stuck or dead
  consumer. (In trusted-header mode open the page as `/_admin/feedz/?hdr.X-TLC-User=<you>`.)
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
`public_url`, `admins` and `oidc` (see Auth above), plus an OIDC client for it at the issuer — do
not publish it in trusted-header mode.
