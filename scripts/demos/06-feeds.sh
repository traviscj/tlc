#!/usr/bin/env bash
# Feeds: how a source service reacts to TLC. Here: "sentinel" tails approvables and applies approved changes.
. "$(dirname "$0")/_lib.sh"
ap() { python3 -c 'import json,sys;print(json.load(sys.stdin)["approvable"]["token"])'; }
CUR="$(mktemp -t tlc-cursor)"; trap 'rm -f "$CUR" "$CUR.tmp"' EXIT
tlc --as "$ADMIN" rule mk --token "AR-Q$RUN" --requirement ANY_NON_SELF_APPROVAL --desc "feed demo" >/dev/null
# start this consumer at the current head so it only sees this run
python3 - "$TLC_URL" "$CUR" <<'PY'
import json,sys,urllib.request
u,cur=sys.argv[1:]; c=0
while True:
    r=json.load(urllib.request.urlopen(urllib.request.Request(f"{u}/_feeds/fetch/approvables?after={c}&limit=1000",headers={"X-TLC-User":"alice"})))
    if not r["entries"]: break
    c=int(r["cursor"])
open(cur,"w").write(str(c))
PY

say "Feed status: one monotonically increasing sequence per feed"
run --as alice feed status

# The consumer: read a page past the cursor; for each APPROVED approvable filed via sentinel, claim it, and
# only if the claim is ours (changed=true) apply the change. Commit the cursor after the page is handled.
consume() {
  tlc --service sentinel feed follow approvables --cursor-file "$CUR" --once | while read -r line; do
    tok=$(python3 -c 'import json,sys;e=json.loads(sys.argv[1]);print(e["token"],e["approval_state"],e["target"],e["feed_sync_id"])' "$line")
    set -- $tok
    printf '   feed: fsi=%-4s %-9s %s %s\n' "$4" "$2" "$1" "$3"
    if [ "$2" = APPROVED ]; then
      if tlc --service sentinel ap execute "$1" >/dev/null; then echo "   sentinel: claimed $1 -> APPLYING $3"; else echo "   sentinel: $1 already claimed elsewhere, skipping"; fi
    fi
  done
}

say "sentinel files two changes on alice's behalf"
A=$(tlc --service sentinel --json ap mk "sentinel/proposed_changes/q$RUN" "AR-Q$RUN" --requested-by alice --context "feed demo 1" | ap)
B=$(tlc --service sentinel --json ap mk "sentinel/proposed_changes/r$RUN" "AR-Q$RUN" --requested-by alice --context "feed demo 2" | ap)
sleep 1.2; note "consumer pass 1: sees both PENDING, nothing to do"
consume

say "bob approves one; the row is republished with a NEW, higher feed_sync_id"
run --as bob ap approve "$A"
sleep 1.2; note "consumer pass 2: sees only what changed since its cursor, and executes it"
consume
sleep 1.2; note "consumer pass 3: its own execute republished the row as EXECUTED"
consume

say "Nothing new: an empty page leaves the cursor where it was"
consume; echo "   cursor=$(cat "$CUR")"

say "Other feeds: taggings, threads, comments. Shards split a feed across parallel consumers."
tlc --as alice tag add "sentinel/proposed_changes/q$RUN" fed >/dev/null; sleep 1.2
printf '\033[33m$ curl "%s/_feeds/fetch/taggings?after=0&limit=3&shard=0&shard_count=2"\033[0m\n' "$TLC_URL"
curl -s -H 'X-TLC-User: alice' "$TLC_URL/_feeds/fetch/taggings?after=0&limit=3&shard=0&shard_count=2" | python3 "$HERE/_fmt.py" taggings
run --as alice feed status
