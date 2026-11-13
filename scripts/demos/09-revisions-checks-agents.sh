#!/usr/bin/env bash
# What is approved is a *revision* of the record; machines assert things about it; agents take part.
. "$(dirname "$0")/_lib.sh"
ap() { python3 -c 'import json,sys;print(json.load(sys.stdin)["approvable"]["token"])'; }
tk() { python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])'; }
adm() { tlc --as "$ADMIN" "$@"; }
PC="sentinel/proposed_changes/r$RUN"
adm group add trust-infra bob >/dev/null; adm group add trust-risk carol --admin >/dev/null

say "A rule = who + how many + what machines must assert + what a new revision does. All four are per rule."
adm rule mk --token "AR-R$RUN" --requirement ALL_GROUPS --desc "raise a rule threshold" --group 'ENG:trust-infra' --group 'RISK:trust-risk' \
  --check 'zeroed_groups:max=0:unit=groups:reporter=sentinel:desc=rule groups whose flag rate drops to zero' \
  --check 'flag_rate_delta_pct:min=-15:max=15:unit=%:reporter=sentinel:desc=change in overall flag rate' >/dev/null
run --as alice rule show "AR-R$RUN"

say "1. sentinel files revision 3 of the change. The request is for $PC@3, not for '$PC'."
A=$(tlc --service sentinel --json ap mk "$PC" "AR-R$RUN" --requested-by alice --rev 3 --context "threshold 12 -> 20" | ap)
run --as bob ap approve "$A" --rev 3 --comment "eng ok"

say "2. Checks: sentinel reports what it measured; the rule's bound decides. -22.5% is outside -15..15."
run --service sentinel ap check "$A" zeroed_groups --value 0
run --service sentinel ap check "$A" flag_rate_delta_pct --value -22.5 --note "30d backtest"
note "a person can't vouch for a machine, and only the named reporter's word counts:"
refused --as alice ap check "$A" flag_rate_delta_pct --value 1
refused --service backtester ap check "$A" flag_rate_delta_pct --value 1

say "3. The discussion hangs on the request, and each comment remembers the revision it was about"
T=$(tlc --as carol --json thread mk "20 loses a fifth of the holds" --target "$A" | tk)
tlc --as alice comment mk "$T" "ok, going to 16" >/dev/null

say "4. alice edits the change: sentinel moves the request to @4. bob approved @3, so (this rule: DISMISS) his approval is withdrawn."
run --service sentinel ap revise "$A" --rev 4 --context "threshold 12 -> 16 (was 20)"
note "checks are per revision too: @3's results say nothing about @4"
tlc --as carol comment mk "$T" "16 works for risk" >/dev/null
run --as alice thread show "$T"
note "approving the revision you actually read: carol still has @3 on screen"
refused --as carol ap approve "$A" --rev 3
run --as carol ap approve "$A" --rev 4
run --as bob ap approve "$A" --rev 4
note "both groups have signed, and it is still PENDING: the checks have not passed on @4"
run --service sentinel ap check "$A" zeroed_groups --value 0
run --service sentinel ap check "$A" flag_rate_delta_pct --value -6.2
run --as alice ap show "$A"

say "5. Execute what was approved, not what happens to be in the table"
refused --service sentinel ap execute "$A" --rev 3
run --service sentinel ap execute "$A" --rev 4

say "6. Another team's policy: approvals survive a revision (KEEP) and agents count (ALLOW). Same engine, different rule."
adm agent add "reviewbot" --operator dave --desc "reads the diff and the backtest" >/dev/null
adm group add trust-infra reviewbot >/dev/null; adm group add trust-infra dave >/dev/null
adm rule mk --token "AR-K$RUN" --requirement ANY_NON_SELF_APPROVAL --desc "copy edits to a rule description" --group 'ENG:trust-infra' --on-revision KEEP --agents ALLOW >/dev/null
B=$(tlc --service sentinel --json ap mk "sentinel/proposed_changes/k$RUN" "AR-K$RUN" --requested-by alice --rev a1f3 | ap)
run --as reviewbot ap approve "$B"
run --service sentinel ap revise "$B" --rev b72c --context "typo"
run --as alice ap show "$B"
note "under the people-only rule above, the same agent is refused; and it never counts on its operator's request"
C=$(tlc --service sentinel --json ap mk "sentinel/proposed_changes/m$RUN" "AR-R$RUN" --requested-by alice --rev 1 | ap)
refused --as reviewbot ap approve "$C"
D=$(tlc --service sentinel --json ap mk "sentinel/proposed_changes/n$RUN" "AR-K$RUN" --requested-by dave --rev 1 | ap)
refused --as reviewbot ap approve "$D"

say "The trail on the record: every revision, check result and approval, with the revision it was for"
run --as alice log ls --target "$PC" --source tlc.approvals
