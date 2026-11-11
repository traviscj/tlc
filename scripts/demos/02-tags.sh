#!/usr/bin/env bash
# Tags: effective-dated, append-only. "was tagged" is "is tagged" at a different time.
. "$(dirname "$0")/_lib.sh"
E="sentinel/proposed_changes/t$RUN"

say "tag / untag are idempotent; 'changed' says whether anything was written."
run --as alice tag add "$E" urgent
run --as alice tag add "$E" urgent
run --as alice tag of "$E"

say "is tagged (exit status is the answer, so it works in shell conditionals)"
if tlc --as alice tag is "$E" urgent >/dev/null; then echo "   -> yes"; fi

say "Operations carry an effective time. Record that it was under 'fraud-review' for part of January:"
run --as alice tag add "$E" fraud-review --at 2026-01-10T00:00:00Z
run --as bob tag remove "$E" fraud-review --at 2026-01-20T00:00:00Z

say "was tagged: at an instant..."
run --as alice tag is "$E" fraud-review --at 2026-01-15T00:00:00Z
run --as alice tag is "$E" fraud-review --at 2026-01-25T00:00:00Z || true
say "...over a window, with the intervals and who opened/closed them"
run --as alice tag was "$E" fraud-review
run --as alice tag was "$E" fraud-review --from 2026-01-12T00:00:00Z --to 2026-01-14T00:00:00Z
run --as alice tag was "$E" fraud-review --from 2026-02-01T00:00:00Z --to 2026-03-01T00:00:00Z

say "known_at: what would TLC have answered before it learned of the backdated tagging?"
note "(asks about Jan 15, as known one minute ago — the backdated ops were recorded seconds ago)"
BEFORE=$(python3 -c 'import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=1)).strftime("%Y-%m-%dT%H:%M:%SZ"))')
run --as alice tag is "$E" fraud-review --at 2026-01-15T00:00:00Z --known-at "$BEFORE" || true

say "The raw operation log (effective vs recorded time, ldap AND service)"
run --service sentinel --as carol tag add "$E" auto-flagged
run --as alice tag log --target "$E"

say "Retiring a tag: nothing new can be tagged with it; what's tagged stays tagged; removal still works."
run --as alice tag mk "old-$RUN" --desc "about to be retired"
run --as alice tag add "$E" "old-$RUN"
run --as alice tag rm "old-$RUN"
refused --as alice tag add "sentinel/proposed_changes/other$RUN" "old-$RUN"
run --as alice tag is "$E" "old-$RUN"
run --as alice tag remove "$E" "old-$RUN"

say "TLC's own objects are taggable too (threads, comments, approvables)"
T=$(tlc --as alice --json thread mk "pin me" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
run --as alice tag add "$T" pinned
run --as alice tag targets pinned
