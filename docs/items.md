# Items

An item is an open loop: one thing waiting on a person, whatever raised it. TLC keeps the list, its history, and
the record of what the person did when shown it.

```sh
POST /api/items            {"title":"call the roofer","kind":"property","due_at":"…","detail":"…","url":"…","key":"…","source":"…","priority":2.5}
POST /api/items/sync       {"source":"srcage","items":[{"key":"inforad-net","title":"inforad-net is 3 commits behind","kind":"deploy"}]}
GET  /api/items?state=open|snoozed|done|dropped|all&source=&kind=&limit=      -> {items:[…], counts:{open,snoozed,done,dropped}}
GET  /api/items/I-…        -> {item, events:[…]}
POST /api/items/I-…/done | drop | reopen     {"surface":"deck","note":"…"}
POST /api/items/I-…/snooze                   {"seconds":3600} or {"until":"…"}
POST /api/items/shown      {"tokens":["I-…","I-…"],"surface":"far"}
POST /api/items/pick       {"winner":"I-…","loser":"I-…","surface":"deck"}
```

## Codes, notes, tags

```sh
GET  /api/items/8a5                         any item path takes the short code in place of the token (any case)
POST /api/items/8a5/note    {"text":"paid from the operating account","delegate":true,"surface":"phone"}
POST /api/items/8a5/tag     {"tags":["apt"],"untag":["fix"]}
GET  /api/items?tag=apt     (tag=- lists the untagged)      -> also {tags:{apt:3, home:2, "-":9}} for the open set
```

- **Code.** Each item has a short code, written `OL#8a5`: three characters (four past 29,791 items) from
  `23456789abcdefghjkmnpqrstuvwxyz`, so nothing in it can be misread as 0/o or 1/i/l. It is a fixed scramble of the
  row id, so it never changes and is not stored. In a URL path use it bare (`#` starts a fragment); the CLI accepts
  `OL#8a5`, `ol#8A5` and `8a5`.
- **Notes.** An item has one thread, an ordinary TLC thread whose target is the item (`thread` on the item,
  `notes` = its comment count, `GET /api/items/…` returns them oldest first). `…/note` appends to it. With
  `"delegate": true` the agent is queued again afterwards, which is how a `NEEDS_YOU` question gets answered; if a
  run is already queued or in flight the note is just added. A worker's results are appended as `[STATE] text`, so
  the thread reads: context, result or question, answer, next result.
- **Session.** A worker may report `"session"` with a result; it is kept as `run_session` across later
  delegations, so the next run can resume the same agent session and a person can open it.
- **Tags.** Up to a few slugs (`a-z0-9-`). A new item with none gets one from its kind or source: `property` →
  `apt`, `chore` → `home`, `person` → `family`, `idea` → `idea`, `deploy`/`commit`/`broken` → `fix`. `POST /api/items`
  with `tags` sets them; a sync's `tags` apply only when the item is new, so a person's retag sticks. Two tags move
  the order: `home` +0.25 and `fix` −0.25, which puts a home chore above a fix of the same kind and age.

## Redaction

```sh
GET /api/items?redact=-1|0|inf        GET /api/items/8a5?redact=0
```

| level | shows |
|---|---|
| `-1` (or no `redact`) | everything |
| `0` | everything except the text of **private** items |
| `inf` | no item's text |

An item is private when it is tagged `private`, or when its title, source or key matches one of the deployment's
`item_private_patterns` (regexes, case-insensitive; the tag `public` overrides a match). A blanked item has
`redacted: true`, a placeholder title, and no detail, url, key, result, notes or event details; it keeps its code,
kind, tags, score, dates and run state, so a screen can still count it, order it and act on it. Redaction is applied
by the server, so a display that asks for level 0 never receives the text. The CLI defaults to `0` on a terminal
and `-1` when its output is piped or `--json` (`--redact`, `TLC_REDACT`, or `~/.config/tlc/redact` set it).

## Two kinds of source

| | captured | derived |
|---|---|---|
| written with | `POST /api/items` | `POST /api/items/sync` |
| is | a todo, an idea, a question that needs an answer | a condition some system can see: a service behind its branch, unpushed work, tonight's chore |
| closes when | a person says so | the source stops reporting it (`closed_by: source`), or a person says so |
| same `(source, key)` again | updates it; reopens it if it was closed | see below |

`source` defaults to the calling service, else `capture`. `key` defaults to a random one, so give a key to anything
that might be reported twice.

A sync is the source's **whole current set**: an empty list closes everything it had open. For an item a person
closed, the rule is that a close holds for as long as the condition does:

| the item was | and this sync | then |
|---|---|---|
| open | lists it | updated in place (no event unless something changed) |
| open | omits it | `DONE`, `closed_by: source` |
| closed by the source | lists it | reopened: a new occurrence, age starts over |
| closed by a person | lists it | stays closed ("I know") |
| closed by a person | omits it | marked cleared |
| closed by a person, cleared | lists it | reopened: it went away and came back |

## Order

`GET /api/items` is ordered by `score = max(base, hazard)`:

- **base** = the source's `priority` hint, else the kind's weight (`question` 3, `broken` 3, `property` 2.5, `deploy`
  `commit` `chore` `person` `todo` 2, `idea` 1, anything else 2), plus up to 1 for age, linear over two weeks. Old items
  drift above newer ones of their kind and never past the kind above.
- **hazard** = 10 once `due_at` has passed, else `10·e^(−hours to due / 24)`: 7.8 six hours out, 3.7 a day out, 1.4
  two days out. A near deadline beats anything undated; a far one costs nothing.

Snoozed items are left out of `state=open` until their time.

This rule is a placeholder for a learned one. What it will be learned from is already recorded:

## Delegation

```sh
POST /api/items/I-…/delegate   {"to":"claude","surface":"deck","note":"…"}    -> run_state QUEUED (409 while one is queued or running)
POST /api/items/I-…/recall                                                    -> un-queues it, if nobody has claimed it yet
GET  /api/items?run=queued&assigned=claude                                     what a worker polls
POST /api/items/I-…/run        {"state":"RUNNING"}                            the claim: succeeds for exactly one caller
POST /api/items/I-…/run        {"state":"DONE|NEEDS_YOU|FAILED","result":"…"}
```

Delegating records a request; TLC runs nothing. A worker claims the item, does the work and reports back. The item
stays **open** with `run_state` and `run_result` on it: whoever asked reads the result and closes the item. While a
result is waiting to be read the item's score is 1.5 higher.

## Page

`/items` is the list as a dense table, most pressing first, with the selected item's detail, any agent result and
its full history beside it. Keys: `j`/`k` move, `d` done, `h`/`s`/`w` snooze an hour / a day / a week, `x` drop, `c` hand
to an agent (again while queued = recall), `u` undo, `p` choose between two, `1`-`4` open / snoozed / done / dropped,
`a` add (`?` prefix = question, `!` = todo), `r` blur the text. It reports what it shows (surface `console`).

## Feed

`GET /_feeds/fetch/items` (see [feeds](feeds.md)) is every item in the order it last changed, latest state only, with
server-side cursors for consumers that name themselves. Use it to mirror the queue or react to changes without
polling the list; use the events below for the history of one item.

## Events

Every item has an append-only history (`GET /api/items/I-…`): `CREATED`, `SHOWN` (with the surface and position it
was shown at), `PICKED` (preferred over `other` when asked to choose between two), `SNOOZED`, `DONE`, `DROPPED`,
`REOPENED`, `RESOLVED` (closed by its source), `DELEGATED`, `RECALLED`, `RUN` (each state a worker reported), `NOTED`, `TAGGED`. Surfaces that display items should report `shown` for what was
actually on screen: shown-and-passed-over is the signal that cannot be reconstructed afterwards.
