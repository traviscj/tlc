# Embedding (the iframe) and the bundled UIs

## `/iframe`

```html
<iframe src="https://tlc.example/iframe?target=sentinel/proposed_changes/1234"></iframe>
```

| param | |
|---|---|
| `target` | any target (`service/entity/id`, `E-`, `T-`, `C-`, `AP-`) |
| `panels` | subset of `ownership,approvals,tags,comments,logs` (default: all) |
| `thread` | open this thread initially |
| `interval` | poll period ms (default 5000, min 2000) |
| `user` | dev only: who to act as (see below) |

It shows, for that one target: approvables with the live evaluation and the buttons the viewer is
actually allowed to use (approve / unapprove / execute / decline), a request form; current tags
with since/by and the last operations; threads, the open thread as a tree with reply / like / edit /
rm; recent logs with a level filter. It re-fetches on an interval and after every action, and won't
re-render under a form someone is typing in.

**Sizing.** The frame posts `{type: "tlc:height", height, target}` to its parent after each render:

```js
addEventListener('message', e => {
  if (e.source === frame.contentWindow && e.data?.type === 'tlc:height') frame.style.height = e.data.height + 'px';
});
```

**Identity.** The page itself is static and unauthenticated; its API calls carry `X-TLC-User`.
In a real deployment the SSO proxy in front of TLC sets that header on every request and the page
needs nothing. In dev, `?user=alice` (or the console's "as" box, kept in localStorage) supplies it.

`/demo` is a fake sentinel page doing all of the above; change "viewing as" to see the buttons change.

## `/` — the console

Three columns, everything visible at once: types / entities / tags / tlcers / directory · the
selected target (the same panel the iframe uses) · approvables (▸ marks your queue) / rules /
outbox / feeds / global log tail. Click any row to open its target; `#target=…` and
`#approvable=AP-…` are linkable. Admins get inline forms for types, directory members and rules
(`groups: ENG:trust-infra:#chan, RISK:trust-risk:admins`).

Both UIs are plain files in `src/main/resources/web/` sharing `tlc.js` (API client, `TLC.panel`,
`TLC.poll`) and `tlc.css`. Polling is one self-rescheduling loop: at most one tick in flight, an
8 s abort on every request, parked while the tab is hidden, stopped on unload.

## Or skip the iframe

Everything the widget does is the public JSON API; `GET /api/summary?target=…` is the one-call
version for a server-rendered page.
