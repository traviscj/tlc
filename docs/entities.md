# Entities

> Names like `sentinel`, `ATO_VELOCITY`, `trust-infra` and `alice` are a made-up example company: [who and what they are](example-world.md).

TLC attaches things to records it doesn't own. It models just enough to point at them.

| table | is | example |
|---|---|---|
| `external_entity_types` | a *table* in some service that TLC has been told about | [`sentinel`](example-world.md#sentinel) / `proposed_changes` |
| `external_entities` | a *row* of such a table | type ↑ + `source_entity_id = "1234"` |
| `tlcers` | a person who has acted through TLC | `alice` |

## Targets

Anywhere the API takes a `target`, it accepts:

| form | means |
|---|---|
| `service/entity/id` | an external record (split on the first two `/`; the id may itself contain `/`) |
| `E-…` | the same record by TLC token |
| `T-…` `C-…` `AP-…` | a TLC-internal object: thread, comment, approvable |

Tags and threads attach to any target. Logs and approvables attach to external records only
(that is what the note's schemas say: `Entity type id / Entity id`, `external_entity_id`).

## Lifecycle rules

- **Types are explicit.** A ref to an unregistered `(service, entity)` is a 404. Otherwise
  `sentinel/propsed_changes/1` silently mints a second "table" and its tags are never found again.
  Flip `tlc.auto_register_entity_types` to get the permissive behaviour.
- **Records are vivified.** TLC can't pre-know every row of someone else's table, so the first
  *write* that mentions [`sentinel/proposed_changes/1234`](example-world.md#sentinel-proposed_changes) creates the record. *Reads* never create:
  `is-tagged` on a never-seen record is `false`, `GET /api/entities/get` is a 404, untag is a no-op.
- **Destroy is soft** (`deleted_at`). History hung on the record stays readable. A later write
  revives it — the source service evidently still has the row. Destroying a *type* (admin) blocks
  new records under it; re-registering revives it.
- **`url_template`** on a type (`https://sentinel.example/pc/{id}`) gives each record a `url` back to
  its owner; the console and iframe link it.
- **tlcers** appear on first write (never by signup) and carry `last_seen_at`. `PATCH /api/me` sets a
  display name. Service-only callers (`X-TLC-Service` without a user) don't become tlcers.

`GET /api/summary?target=…` returns the record, its current tags, threads, approvables and recent
logs in one call; it is what the iframe renders.
