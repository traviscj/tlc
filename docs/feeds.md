# Feeds

How another service *reacts* to TLC without polling every object: a cursor over rows in the order
they last changed.

```
GET /_feeds/fetch/{taggings|threads|comments|approvables|ownerships}?after=<cursor>&limit=100[&shard=i&shard_count=n]
 →  {"feed": "...", "entries": [ …rows, each with feed_sync_id and shard… ], "cursor": <next after>}
GET /_feeds/status      → per feed: sequence, rows, pending (unpublished), publishing_enabled; per cursor: token, lag, enabled, timestamps
GET /_admin/feedz/      → the same as a page, with the operator commands
```

The machinery is the `misk-feedz` library (`FeedzModule` in `TlcModule.kt`); `TlcFeedsModule` in
`Feeds.kt` only says which tables are feeds and how a page of each is read.

## Mechanics

- Each feed table has `feed_sync_id` (unique, nullable) and `shard`. **Every mutation sets
  `feed_sync_id = NULL`**; the background publisher (every `background_interval_ms`, 500 ms) assigns
  pending rows fresh ids from a per-feed counter in `sequences`.
- A changed row is *re*-published under a new, higher id. It is a **state feed**: you get each row's
  latest state at least once, not every intermediate state. For the event history use the logs
  (`tlc.approvals`) or, for tags, the taggings feed — taggings are immutable, so there it *is* the event log.
- **No gaps behind the cursor.** Ids are assigned and committed while holding the feed's sequence
  row lock, so they become visible in id order. A consumer never sees N+1 and later finds N.
- **Delivery is at-least-once.** Commit your cursor after handling a page; make handlers idempotent.
- **Shards.** `shard` is fixed at write time in `[0, feed_shards)` (16): taggings and approvables by
  target, comments by thread. `shard=i&shard_count=n` selects `shard % n = i`, so *n* consumers split
  a feed without coordination while everything about one target stays in order on one consumer.
- An empty page returns the same cursor you sent.
- Admin: `POST /_feeds/{feed}/pause|resume` (persisted), `POST /_feeds/publish` (publish now, even if paused).

## Keeping the cursor in TLC

A consumer that would rather not store its own position names itself instead of sending `after`:

```
GET  /_feeds/fetch/{feed}?consumer=NAME[&shard=i&shard_count=n]     page after NAME's cursor (created on first use)
POST /_feeds/{feed}/cursors/NAME/commit?token=<page.cursor>[&shard=i]   → {"advanced": true|false}
```

- Fetching does not move the cursor; **commit** does, and only forward. Fetch again without
  committing and the same page comes back.
- One row per (consumer, feed, shard) in `feed_cursors`. `last_advanced_at` moves only when a commit
  moves the token; `updated_at` moves only when an operator touches the cursor. So "switched off at
  14:02" and "last made progress at 13:10" are separate facts.
- A cursor's `shard_count` is fixed once any shard exists; fetching with another is a 409 until an
  admin reshards it.
- Admin, per cursor: `POST /_feeds/{feed}/cursors/NAME/disable|enable[?shard=]`,
  `…/seek?token=N[&shard=]` (rewind or skip; 0 = from the start), `…/reshard?shard_count=N` (every
  new shard restarts at the lowest old position), `DELETE /_feeds/{feed}/cursors/NAME`.
- **A disabled cursor answers 409, not an empty page**, and refuses commits, so a switched-off
  consumer cannot look caught up or undo a rewind with a late commit.
- `lag` in the status is `sequence − token`: an upper bound on entries left (ids of other shards and
  of republished rows count too).

## The intended loop

```
cursor = load()
loop:
  page = GET /_feeds/fetch/approvables?after=cursor
  for a in page.entries where a.approval_state == APPROVED and a.requested_via_service == me:
      r = POST /api/approvables/{a.token}/execute        # claim
      if r.changed: apply(a.target)                      # exactly one claimant gets true
  for a in page.entries where a.mode == RATIFY and a.approval_state == REVERTED and mine(a):
      undo(a.target)                                     # break-glass nobody ratified; must be idempotent
  save(cursor = page.cursor)
```

`scripts/demos/06-feeds.sh` runs exactly this; `tlc feed follow FEED --cursor-file F` is a
15-line reference consumer.

`ownerships` is the claim log (immutable rows, so an event log like taggings), sharded by target.

Logs are not a feed (their `id` cursor is weaker — see `docs/logs.md`); likes surface through their
comment's row (`likes` changes ⇒ comment republished).
