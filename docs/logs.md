# Logs

> Names like `sentinel`, `ATO_VELOCITY`, `trust-infra` and `alice` are a made-up example company: [who and what they are](example-world.md).

Append-only entries: `timestamp, level, message, source` plus an optional scope — an entity
**type** ([`sentinel/proposed_changes`](example-world.md#sentinel-proposed_changes)), one **record** ([`sentinel/proposed_changes/1234`](example-world.md#sentinel-proposed_changes), which
implies its type), or nothing.

```sh
POST /api/logs  {"target":"sentinel/proposed_changes/1234","level":"WARN","source":"sentinel.backtest","message":"…","timestamp":"…"}
POST /api/logs  {"entries":[ {...}, {...} ]}          # ≤1000, all-or-nothing
GET  /api/logs?target=…&level=WARN&source=sentinel.&q=timeout&since=…&until=…&limit=200
GET  /api/logs?after_id=<cursor>                      # tail: ascending, returns the next cursor
```

- **level**: `TRACE DEBUG INFO WARN ERROR FATAL` (also accepts `WARNING`, `ERR`); the filter is a *minimum*.
- **source** defaults to the calling service, else the calling user. A filter ending in `.` or `*`
  is a prefix match (`source=tlc.`).
- **timestamp** is the caller's clock (defaults to now); `created_at` is the server's. Both are returned.
- Default order is newest-first; with `after_id` it is oldest-first so a tailer can append.

## TLC's own audit trail

TLC writes to the same log, in the same transaction as the change it describes:

| source | events |
|---|---|
| `tlc.tags` | tagged / untagged, by whom, backdated to when |
| `tlc.comments` | thread opened |
| `tlc.approvals` | requested, each approval and withdrawal, every state transition with the evaluation that caused it, decline (WARN), execute |

So `GET /api/logs?target=X&source=tlc.approvals&order=asc` is the full approval history of X.

## Limits

`id` is the cursor. Ids are allocated at insert but transactions commit in any order, so under
concurrent appenders a tailer can, rarely, be handed id 12 before id 11 becomes visible and then
skip 11. Logs are the cheap path; if you need a gap-free stream use a feed (`docs/feeds.md`),
or re-read a small overlap. There is no retention policy and `q=` is an unindexed `LIKE`.
