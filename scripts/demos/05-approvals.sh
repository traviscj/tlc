#!/usr/bin/env bash
# Approvals: rules say what's required, groups say who counts, approvables move PENDING → APPROVED → EXECUTED.
. "$(dirname "$0")/_lib.sh"
ap() { python3 -c 'import json,sys;print(json.load(sys.stdin)["approvable"]["token"])'; }
adm() { tlc --as "$ADMIN" "$@"; }

say "Directory (local stand-in for the real one): who is in which group"
adm group add trust-infra bob >/dev/null; adm group add trust-infra dave >/dev/null; adm group add trust-risk bob >/dev/null
adm group add trust-risk carol --admin >/dev/null; adm group add trust-legal lena >/dev/null
run --as alice group ls

say "Rules (admin-only). One per requirement type:"
adm rule mk --token "AR-N$RUN" --requirement ANY_NON_SELF_APPROVAL --desc "any second pair of eyes" >/dev/null
adm rule mk --token "AR-G$RUN" --requirement MIN_GROUPS --min 2 --desc "for approving a new ATO_VELOCITY rule" \
  --group 'ENG:trust-infra:#trust-infra-oncall' --group 'RISK:trust-risk' --group 'LEGAL:trust-legal:legal@example.com' >/dev/null
adm rule mk --token "AR-A$RUN" --requirement ALL_GROUPS --desc "risk ADMINS and eng must both sign" --group 'ENG:trust-infra' --group 'RISK:trust-risk:admins' >/dev/null
adm rule mk --token "AR-F$RUN" --requirement SLACK_NOTIFICATION --desc "FYI-only changes" --group 'ENG:trust-infra:#trust-infra-oncall' >/dev/null
run --as alice rule ls

say "1. ANY_NON_SELF_APPROVAL"
A1=$(tlc --as alice --json ap mk "sentinel/proposed_changes/a$RUN" "AR-N$RUN" --context "threshold 12 -> 20" | ap)
refused --as alice ap approve "$A1"
refused --as alice ap execute "$A1"
run --as bob ap approve "$A1" --comment "lgtm"
note "execute is a CLAIM: do the change only if changed=true. The second caller loses (exit 3)."
run --as alice ap execute "$A1"
run --as alice ap execute "$A1" || echo "   (exit $? — already claimed)"

say "2. MIN_GROUPS>=2 needs DISTINCT people. bob is in ENG and RISK — that is still one person."
A2=$(tlc --as alice --json ap mk "sentinel/proposed_changes/b$RUN" "AR-G$RUN" --context "new ATO_VELOCITY rule" | ap)
refused --as mallory ap approve "$A2"
run --as bob ap approve "$A2"
note "dave is ENG-only. A greedy assignment would leave bob on ENG and count 1; the matching moves bob to RISK:"
run --as dave ap approve "$A2"
run --as alice ap show "$A2"

say "Withdrawing an approval before execution reopens it"
run --as dave ap unapprove "$A2"
refused --as alice ap execute "$A2"
note "who is it waiting on? lena (LEGAL) sees it in her queue; bob doesn't (already approved)"
run --as lena ap ls --awaiting me
run --as bob ap ls --awaiting me
run --as lena ap approve "$A2"

say "3. ALL_GROUPS with an admins-only group: bob is a trust-risk member but not an admin"
A3=$(tlc --as alice --json ap mk "sentinel/proposed_changes/c$RUN" "AR-A$RUN" | ap)
run --as bob ap show "$A3"
run --as bob ap approve "$A3"
run --as carol ap approve "$A3"

say "4. Notification-only: nobody approves; the channel is told and it is APPROVED at once"
A4=$(tlc --as alice --json ap mk "sentinel/proposed_changes/d$RUN" "AR-F$RUN" --context "comment-only change" | ap)
run --as alice ap show "$A4"
refused --as bob ap approve "$A4"

say "5. Decline is terminal"
A5=$(tlc --as alice --json ap mk "sentinel/proposed_changes/e$RUN" "AR-N$RUN" | ap)
run --as bob ap decline "$A5" --reason "threshold too high for Q4"
refused --as bob ap approve "$A5"

say "6. A service files on a user's behalf (client_id makes the retry idempotent); only that service may execute"
A6=$(tlc --service sentinel --json ap mk "sentinel/proposed_changes/f$RUN" "AR-N$RUN" --requested-by alice --client-id "pc-f$RUN" | ap)
A6b=$(tlc --service sentinel --json ap mk "sentinel/proposed_changes/f$RUN" "AR-N$RUN" --requested-by alice --client-id "pc-f$RUN" | ap)
echo "   first=$A6 retry=$A6b"
run --as bob ap approve "$A6"
refused --as alice ap execute "$A6"
run --service sentinel ap execute "$A6"

say "7. Changing what a rule requires while approvables are open under it is refused (or forced + re-evaluated)"
A7=$(tlc --as alice --json ap mk "sentinel/proposed_changes/g$RUN" "AR-A$RUN" | ap)
tlc --as bob ap approve "$A7" >/dev/null
refused --as "$ADMIN" rule edit "AR-A$RUN" --requirement MIN_GROUPS --min 1
run --as "$ADMIN" rule edit "AR-A$RUN" --requirement MIN_GROUPS --min 1 --force
run --as alice ap show "$A7"

say "The outbox: what was (or would have been) said, to whom"
sleep 1
printf '\033[33m$ curl %s/api/notifications?approvable=%s\033[0m\n' "$TLC_URL" "$A2"
curl -s -H 'X-TLC-User: alice' "$TLC_URL/api/notifications?approvable=$A2" | python3 "$HERE/_fmt.py" notifications

say "And the audit trail on the entity"
run --as alice log ls --target "sentinel/proposed_changes/b$RUN" --source tlc.approvals
