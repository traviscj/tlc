# Demo recordings

One video per demo script. Left pane: the demo's terminal output as it runs. Right pane: the TLC console
(`/?user=alice#target=…`) on the record that step is about, updating from the same server. 1920×1080, H.264, 8 fps, no audio.

| recording | demo | length | size | the console is showing |
|---|---|---:|---:|---|
| [01-entities.mp4](01-entities.mp4) | `scripts/demos/01-entities.sh` | 0:20 | 0.4 MB | `sentinel/proposed_changes/e$RUN` appearing on first write, renamed, destroyed, revived |
| [02-tags.mp4](02-tags.mp4) | `scripts/demos/02-tags.sh` | 0:36 | 1.5 MB | `…/t$RUN`: current tags and the operation log (backdated add/remove, retired tag), then the pinned thread |
| [03-logs.mp4](03-logs.mp4) | `scripts/demos/03-logs.sh` | 0:22 | 0.7 MB | `…/l$RUN`: log entries by level as sentinel appends them, then TLC's own `tlc.*` audit entries |
| [04-comments.mp4](04-comments.mp4) | `scripts/demos/04-comments.sh` | 0:24 | 0.8 MB | `…/c$RUN`: the thread opened as a tree; likes, the edit, the tombstone, the retitle |
| [05-approvals.mp4](05-approvals.mp4) | `scripts/demos/05-approvals.sh` | 0:51 | 3.0 MB | directory and rules filling in; then `…/a$RUN` … `…/g$RUN`, one per numbered step, PENDING → APPROVED → EXECUTED / DECLINED; outbox |
| [06-feeds.mp4](06-feeds.mp4) | `scripts/demos/06-feeds.sh` | 0:20 | 0.4 MB | `…/q$RUN`: PENDING → APPROVED by bob → EXECUTED by `svc:sentinel` as the feed consumer claims it; feed sequences |
| [07-curl.mp4](07-curl.mp4) | `scripts/demos/07-curl.sh` | 0:28 | 0.7 MB | `billing/invoices/inv-$RUN`: tag, log, thread, approvable arriving from bare curl |
| [08-ownership.mp4](08-ownership.mp4) | `scripts/demos/08-ownership.sh` | 1:08 | 4.6 MB | `rx$RUN/rules/R1`, `R2`, the freeze hook, the signals: ownership cells (OWNED / CONTESTED), "built on this", coverage and gaps, break-glass ACTED → RATIFIED and REVERTED |
| [09-revisions-checks-agents.mp4](09-revisions-checks-agents.mp4) | `scripts/demos/09-revisions-checks-agents.sh` | 0:36 | 2.0 MB | `…/r$RUN`: the request at `@3` then `@4`, check results against their bounds, the dismissed approval, the thread on the request; then `…/k$RUN` (KEEP + agent) |

## Regenerate

Against a running, seeded TLC (README "Run it"; a fresh database gives the same console contents as these):

```sh
python3 -m venv /tmp/tlc-rec-venv && /tmp/tlc-rec-venv/bin/pip install playwright imageio-ffmpeg   # once; uses the local Chrome
scripts/seed.sh
/tmp/tlc-rec-venv/bin/python scripts/record_demos.py          # all nine -> docs/recordings/*.mp4  (≈6 min)
/tmp/tlc-rec-venv/bin/python scripts/record_demos.py 05 09    # just these; --help for pacing, fps, crf, --keep-frames
```

`TLC_URL` picks the server (default `http://localhost:8790`). These were recorded against a throwaway instance:
port 8792, empty database, `scripts/seed.sh`, then all nine in order.

## How they are made

`scripts/record_demos.py` opens one headless Chrome page holding a `<pre>` and an `<iframe>` of the console, runs
the demo script for real, appends its output to the `<pre>` as it arrives, screenshots the page a few times a
second, and encodes the PNGs with their real timestamps (ffmpeg, libx264, crf 26). So the video is in real time.

- **Pacing.** `TLC_DEMO_PACE` (seconds held at each `##` step header, 1.6 here) and `TLC_DEMO_CMD_PACE` (after each
  command, 0.8 here; 1.6 for 07) are read by `scripts/demos/_lib.sh` and `07-curl.sh`. Unset, the demos run as fast as before.
- **Two things the recorder does that a person watching the console would not.** It calls the console's own
  `tick()` and `panel.refresh()` whenever the demo prints, so the panes stay in step (unattended, the console polls
  every few seconds); and it moves `#target=` to the record the current step is about (the `DEMOS` table at the
  top of the script). Everything in the right pane is the real console rendering real responses.
- The viewer is `alice`, who is not an admin, so the admin-only forms are absent.
