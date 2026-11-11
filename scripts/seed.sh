#!/usr/bin/env bash
# The names used here are explained in docs/example-world.md.
# Seeds the scenario the /demo page and the console screenshots use: sentinel/proposed_changes/1234 mid-approval.
# Safe to re-run: creates that already exist are skipped; comments/logs are only added the first time.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; export TLC_URL="${TLC_URL:-http://localhost:8790}"; ADMIN="${TLC_ADMIN:-traviscj}"
t() { "$HERE/tlc" "$@" 2>/dev/null; }
tok() { python3 -c 'import json,sys;d=json.load(sys.stdin);print(d.get("token") or d.get("comment_token") or d["approvable"]["token"])'; }
E=sentinel/proposed_changes/1234
t --as "$ADMIN" type mk sentinel/proposed_changes --url-template 'https://sentinel.example/pc/{id}' --desc 'a change request against a fraud rule, waiting on review' >/dev/null
t --as "$ADMIN" type mk sentinel/rules --desc 'a condition over signals that flags an account' >/dev/null
for m in trust-infra:bob trust-infra:dave trust-risk:bob trust-risk:carol:admin trust-legal:lena; do IFS=: read -r g l a <<<"$m"; t --as "$ADMIN" group add "$g" "$l" ${a:+--admin} >/dev/null; done
t --as "$ADMIN" rule mk --token AR-6392 --requirement MIN_GROUPS --min 2 --desc "for approving a new ATO_VELOCITY rule" --group 'ENG:trust-infra:#trust-infra-oncall' --group 'RISK:trust-risk' --group 'LEGAL:trust-legal:legal@example.com' >/dev/null
t --as "$ADMIN" rule mk --token AR-1001 --requirement ANY_NON_SELF_APPROVAL --desc "any second pair of eyes" >/dev/null
t --as "$ADMIN" rule mk --token AR-2002 --requirement SLACK_NOTIFICATION --desc "FYI-only changes" --group 'ENG:trust-infra:#trust-infra-oncall' >/dev/null
# --- ownership: signals -> rules -> hook, three teams, one of every cell status, one open break-glass
if [ -z "$(t --as alice --json own log --target sentinel/rules/ATO_VELOCITY | grep OW-)" ]; then
  ago() { python3 -c "import datetime,sys;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(days=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))" "$1"; }
  R=sentinel/rules; SG=sentinel/signals; HK=sentinel/hooks/freeze_account
  t --as "$ADMIN" type mk sentinel/signals --desc 'a computed input a rule reads' >/dev/null; t --as "$ADMIN" type mk sentinel/hooks --desc 'an action taken when a rule fires' >/dev/null
  while IFS='|' read -r ref name; do t --service sentinel entity mk "sentinel/$ref" --name "$name" >/dev/null; done <<'NAMES'
signals/velocity_24h|money-movement attempts per account, 24h
signals/geo_mismatch|login geography differs from usual
rules/ATO_VELOCITY|account takeover: too many attempts in 24h
rules/VEL_2|second velocity rule
rules/GEO_1|current geo rule
rules/LEGACY_GEO|pre-migration geo rule
hooks/freeze_account|freeze the flagged account
NAMES
  for m in trust-ops:olga trust-ops:omar trust-ds:dana; do IFS=: read -r g l <<<"$m"; t --as "$ADMIN" group add "$g" "$l" >/dev/null; done
  t --as "$ADMIN" group contact trust-infra --slack '#trust-infra-oncall' >/dev/null; t --as "$ADMIN" group contact trust-ops --slack '#ops-review' >/dev/null; t --as "$ADMIN" group contact trust-ds --email ds-oncall@example.com >/dev/null
  t --as "$ADMIN" own expect $R availability --desc "the rule is deployed, evaluates on every eligible event in prod, and returns inside its latency budget" >/dev/null
  t --as "$ADMIN" own expect $R disposition --desc "every account the rule flags lands in a review queue that is staffed, and is worked inside the SLA" >/dev/null
  t --as "$ADMIN" own expect $R efficacy --desc "measures whether we are catching enough of the fraud that exists at a low enough false-positive cost" >/dev/null
  t --as "$ADMIN" own expect sentinel/signals availability --desc "fresh and populated" >/dev/null; t --as "$ADMIN" own expect sentinel/hooks disposition --desc "the action it takes is the right one" >/dev/null
  for e in velocity_24h:ATO_VELOCITY velocity_24h:VEL_2 geo_mismatch:GEO_1 geo_mismatch:LEGACY_GEO; do t --service sentinel edge mk "$SG/${e%%:*}" "$R/${e##*:}" >/dev/null; done
  t --service sentinel edge mk $R/ATO_VELOCITY $HK --kind HOOKS >/dev/null; t --service sentinel edge mk $R/GEO_1 $HK --kind HOOKS >/dev/null
  t --as bob own claim "$R/*" availability trust-infra --at "$(ago 30)" >/dev/null
  t --as dana own claim "$R/*" efficacy trust-ds --at "$(ago 30)" >/dev/null
  t --as olga own claim $R/ATO_VELOCITY disposition trust-ops >/dev/null; t --as olga own claim $R/GEO_1 disposition trust-ops >/dev/null; t --as olga own claim $R/VEL_2 disposition trust-ops >/dev/null
  t --as olga own claim $R/VEL_2 efficacy trust-ops --note "we tune this one weekly" >/dev/null; t --as dana own claim $R/VEL_2 efficacy trust-ds --note "model-backed, ours" >/dev/null
  t --as bob own claim $R/LEGACY_GEO availability trust-risk --at "$(ago 120)" --note "from the 2024 migration" >/dev/null
  t --as bob own claim $R/ATO_VELOCITY efficacy trust-infra --role STAKEHOLDER >/dev/null
  t --as dana own claim $SG/velocity_24h availability trust-ds >/dev/null; t --as olga own claim $HK disposition trust-ops >/dev/null
  t --as "$ADMIN" rule mk --token AR-3003 --requirement ALL_GROUPS --desc "pin a signal to last-known-good" --applies-to sentinel/signals --aspect disposition:downstream --aspect efficacy:notify:downstream >/dev/null
  t --as "$ADMIN" rule mk --token AR-3004 --requirement ALL_GROUPS --desc "disable a rule" --applies-to sentinel/rules --aspect availability --aspect disposition:fallback=trust-ops --aspect efficacy:notify >/dev/null
  t --as "$ADMIN" rule mk --token AR-3005 --requirement ALL_GROUPS --desc "change a rule threshold" --applies-to sentinel/rules --aspect availability:above=0 --aspect disposition:above=50:unit=flags/day --aspect efficacy >/dev/null
  t --service sentinel --as alice ap mk $R/VEL_2 AR-3005 --context "velocity limit 12 -> 14 per 24h" --impact availability=0 --impact "disposition=-18:flags/day:30d backtest" --impact "efficacy=?" >/dev/null
  t --as alice ap mk $R/ATO_VELOCITY AR-3004 --context "double-fires since the 14:00 deploy" >/dev/null
  t --as bob ap mk $SG/velocity_24h AR-3003 --ratify --ttl 604800 --context "velocity_24h stale since 14:02; pinned to last-known-good" >/dev/null
  echo "seeded ownership. try: $TLC_URL/#target=$SG/velocity_24h"
fi
if [ -n "$(t --as alice --json ap ls --target $E | grep AP-)" ]; then echo "already seeded: $TLC_URL/demo"; exit 0; fi
t --as alice entity mk $E --name "raise ATO_VELOCITY velocity limit" >/dev/null
t --as alice entity mk sentinel/proposed_changes/1235 --name "retire legacy geo rule" >/dev/null
t --as alice tag add $E ato >/dev/null; t --as alice tag add $E q4-freeze-exempt --at 2026-11-01T00:00:00Z >/dev/null
t --service sentinel --as carol tag add $E needs-backtest --at 2026-11-03T00:00:00Z >/dev/null; t --service sentinel tag remove $E needs-backtest >/dev/null
t --as alice tag add sentinel/proposed_changes/1235 ato >/dev/null
t --service sentinel log add $E "proposed change created by alice" >/dev/null
t --service sentinel log add $E "backtest finished: 0.4% more holds at 20/day" --source sentinel.backtest >/dev/null
t --service sentinel log add $E "backtest sample smaller than usual (n=812)" --level WARN --source sentinel.backtest >/dev/null
t --service sentinel log add $E "canary deploy failed: timeout talking to rule-engine" --level ERROR --source sentinel.deploy >/dev/null
T=$(t --as alice --json thread mk "is 20 the right threshold?" --body "p99 of legit users is 14/day" --target $E | tok)
A=$(t --as bob --json comment mk "$T" "20 feels high. what does the backtest say?" | tok)
B=$(t --as alice --json comment mk "$T" "0.4% more holds at 20, 1.9% at 16" --parent "$A" | tok)
C=$(t --as carol --json comment mk "$T" "risk is fine with 20 if we alert at 16" --parent "$B" | tok)
t --as dave comment mk "$T" "unrelated: can we rename the rule?" >/dev/null
t --as bob comment like "$C" >/dev/null; t --as alice comment like "$C" >/dev/null
t --as dave thread mk "canary timeout — infra or us?" --target $E >/dev/null
AP=$(t --as alice --json ap mk $E AR-6392 --context "threshold 12 -> 20 on ATO_VELOCITY.velocity_24h; backtest attached" | tok)
t --as bob ap approve "$AP" --comment "eng ok" >/dev/null
AP2=$(t --as alice --json ap mk sentinel/proposed_changes/1235 AR-1001 --context "rule has not fired since 2024" | tok)
t --as bob ap approve "$AP2" >/dev/null; t --as alice ap execute "$AP2" >/dev/null
t --as dave ap mk sentinel/proposed_changes/1236 AR-2002 --context "comment-only change" >/dev/null
echo "seeded. console: $TLC_URL/#target=$E   embed demo: $TLC_URL/demo"
