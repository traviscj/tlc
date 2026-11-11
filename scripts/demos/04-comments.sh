#!/usr/bin/env bash
# Comments: threads on anything, nested replies, likes. Nouns thread|comment x verbs mk/ls/edit/rm.
. "$(dirname "$0")/_lib.sh"
E="sentinel/proposed_changes/c$RUN"
tok() { python3 -c 'import json,sys;d=json.load(sys.stdin);print(d.get("token") or d["comment_token"])'; }

say "thread mk / comment mk"
T=$(tlc --as alice --json thread create "is 20 the right threshold?" --body "p99 of legit users is 14/day" --target "$E" | tok)
A=$(tlc --as bob --json comment mk "$T" "20 feels high. what does the backtest say?" | tok)
B=$(tlc --as alice --json comment mk "$T" "0.4% more holds at 20, 1.9% at 16" --parent "$A" | tok)
C=$(tlc --as carol --json comment mk "$T" "risk is fine with 20 if we alert at 16" --parent "$B" | tok)
D=$(tlc --as dave --json comment mk "$T" "unrelated: can we rename the rule?" | tok)
tlc --as bob --json comment mk "$T" "alerting at 16 works for me" --parent "$C" >/dev/null
run --as alice thread show "$T"
note "order is depth-first by thread_path (zero-padded ancestor ids), so one indexed range scan renders the tree:"
run --as alice comment ls "$T"

say "likes are episodes: unlike closes the row, like again opens a new one"
run --as bob comment like "$C"
run --as alice comment like "$C"
run --as bob comment unlike "$C"
run --as bob comment like "$C"
run --as alice comment likes "$C" --all

say "edit: author (or admin) only"
refused --as bob comment edit "$D" "hijacked"
run --as dave comment update "$D" "unrelated: can we rename the rule to ATO_VELOCITY.velocity_1d?"

say "rm tombstones: text is erased, the row stays so the replies under it keep their place"
run --as alice comment rm "$B"
run --as alice thread show "$T"
note "cached_comments on the thread is maintained in the same transaction:"
run --as alice thread ls --target "$E"

say "thread edit / rm"
run --as alice thread edit "$T" --title "is 20 the right threshold? (settled: yes, alert at 16)"
refused --as bob thread rm "$T"
