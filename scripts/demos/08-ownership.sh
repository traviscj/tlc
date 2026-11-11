#!/usr/bin/env bash
# Ownership: per ASPECT of a record, by directory group, effective-dated. Coverage, blast radius over the
# dependency graph, approval rules that resolve approvers from owners, and break-glass with ratification.
. "$(dirname "$0")/_lib.sh"
ap() { python3 -c 'import json,sys;print(json.load(sys.stdin)["approvable"]["token"])'; }
adm() { tlc --as "$ADMIN" "$@"; }
ago() { python3 -c "import datetime,sys;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(days=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))" "$1"; }
S="rx$RUN"   # a throwaway source service per run, so coverage numbers below are exactly this demo's
SIG="$S/signals"; RL="$S/rules"; HOOK="$S/hooks/freeze_account"; TRIG="$S/triggers/payout"

say "Setup: 2 signals -> 3 rules -> 1 trigger -> 1 hook, and three teams"
for e in signals rules triggers hooks; do adm type mk "$S/$e" >/dev/null; done
for m in trust-infra:bob trust-infra:dave trust-ops:olga trust-ops:omar trust-ds:dana; do IFS=: read -r g l <<<"$m"; adm group add "$g" "$l" >/dev/null; done
adm group contact trust-infra --slack '#trust-infra-oncall' >/dev/null; adm group contact trust-ops --slack '#ops-review' >/dev/null; adm group contact trust-ds --email ds-oncall@example.com >/dev/null
e() { tlc --service "$S" edge mk "$@" >/dev/null; }
e "$SIG/velocity_24h" "$RL/R1"; e "$SIG/velocity_24h" "$RL/R2"; e "$SIG/geo_mismatch" "$RL/R2"; e "$SIG/geo_mismatch" "$RL/R3"
for r in R1 R2 R3; do e "$RL/$r" "$TRIG" --kind TRIGGERS; done
e "$TRIG" "$HOOK" --kind HOOKS
note "edges are pushed by the source service (it owns the graph; TLC mirrors it):"
run --as alice edge ls --target "$RL/R2"

say "1. Ownership is per ASPECT. Say what every rule is expected to have an owner for (the coverage denominator)"
adm own expect "$RL" availability --desc "the rule is deployed, evaluates on every eligible event in prod, and returns inside its latency budget" >/dev/null
adm own expect "$RL" disposition --desc "every account the rule flags lands in a review queue that is staffed, and is worked inside the SLA" >/dev/null
adm own expect "$RL" efficacy --desc "measures whether we are catching enough of the fraud that exists at a low enough false-positive cost" >/dev/null
run --as alice own aspects --type "$RL"

say "Type-wide defaults: ENG owns availability of every rule, DS owns efficacy of every rule"
run --as bob own claim "$RL/*" availability trust-infra
run --as dana own claim "$RL/*" efficacy trust-ds
say "Per record: OPS owns disposition, rule by rule. You can only speak for a group you are in."
run --as olga own claim "$RL/R1" disposition trust-ops
tlc --as olga own claim "$RL/R2" disposition trust-ops >/dev/null
refused --as alice own claim "$RL/R3" disposition trust-ops
note "R1 now: ~ marks an owner inherited from the type default"
run --as alice own of "$RL/R1"

say "2. Squishy: OPS takes efficacy of R2 for itself (overrides the default). DS disagrees."
run --as olga own claim "$RL/R2" efficacy trust-ops --note "we tune this one weekly"
note "a second OWNER is not refused — it is recorded, and the cell reads CONTESTED until someone lets go"
run --as dana own claim "$RL/R2" efficacy trust-ds --note "no: model-backed, ours"
note "ENG doesn't own it but wants to hear about it: STAKEHOLDER (told, never asked; doesn't contest)"
run --as bob own claim "$RL/R2" efficacy trust-infra --role STAKEHOLDER
run --as alice own of "$RL/R2"

say "3. Dynamic: last week != this week. Claims are effective-dated; nothing is overwritten."
tlc --as bob own claim "$HOOK" availability trust-infra --at "$(ago 21)" >/dev/null
tlc --as bob own transfer "$HOOK" availability trust-infra trust-ops --at "$(ago 3)" --note "ops took the freeze hook" >/dev/null
note "who owned the freeze hook 10 days ago?"
run --as alice own of "$HOOK" --at "$(ago 10)"
note "and now?"
run --as alice own of "$HOOK"
run --as alice own log --target "$HOOK"

say "4. Coverage: the share of (record, aspect) cells with exactly one live owner"
run --as alice own coverage --type "$RL"
note "the worklist behind that number:"
run --as alice own gaps --type "$RL"
note "DS lets go of R2; OPS has it alone -> no longer contested"
tlc --as dana own release "$RL/R2" efficacy trust-ds >/dev/null
run --as alice own gaps --type "$RL"
note "what does trust-ops hold?"
run --as alice own by trust-ops

say "5. Something is breaking: signal velocity_24h is stale. Who is on the hook downstream?"
run --as alice own affected "$SIG/velocity_24h"
note "and from the other end: what does the freeze hook depend on?"
run --as alice own affected "$HOOK" --direction up

say "6. Approval rules name ASPECTS, not teams. Who must consent is resolved from ownership when the request is filed."
adm rule mk --token "AR-K$RUN" --requirement ALL_GROUPS --desc "disable a rule" --aspect availability --aspect disposition --aspect efficacy:notify >/dev/null
run --as alice rule show "AR-K$RUN"
K=$(tlc --as alice --json ap mk "$RL/R1" "AR-K$RUN" --context "R1 double-fires since the 14:00 deploy" | ap)
run --as alice ap show "$K"
refused --as dana ap approve "$K"
run --as bob ap approve "$K"
run --as olga ap approve "$K"
note "R3 has no disposition owner. The rule has no fallback for it, so the ask is refused: go find an owner."
refused --as alice ap mk "$RL/R3" "AR-K$RUN"
note "the parties were frozen at filing; ownership moving later does not reach back into an open request"

say "A rule is an ACTION, not an entity. applies_to says which types it is for; listing a type closes it to other rules."
run --as "$ADMIN" rule edit "AR-K$RUN" --applies-to "$RL"
refused --as alice ap mk "$SIG/velocity_24h" "AR-K$RUN"

say "Impacts. The source service declares what the change DOES; a threshold on the rule aspect decides ask / tell / skip."
adm rule mk --token "AR-T$RUN" --requirement ALL_GROUPS --desc "change a rule threshold" --applies-to "$RL" --aspect availability:above=0 --aspect disposition:above=50:unit=flags/day --aspect efficacy >/dev/null
run rule show "AR-T$RUN"
note "small change: 0 availability impact, -12 flags/day. ENG and OPS are told (with the number), only DS is asked"
run --service "$S" --as alice ap mk "$RL/R1" "AR-T$RUN" --context "limit 12 -> 13" --impact availability=0 --impact "disposition=-12:flags/day:30d backtest"
note "big change, and an aspect the service could not measure: unknown is never the cheap path"
run --service "$S" --as alice ap mk "$RL/R1" "AR-T$RUN" --context "limit 12 -> 40" --impact "availability=?" --impact disposition=-340:flags/day
note "a human can't declare impacts: that would be picking your own approvers"
refused --as alice ap mk "$RL/R1" "AR-T$RUN" --impact disposition=0:flags/day

say "7. Break-glass. bob (ENG owns availability of rules built on the signal) pins the stale signal NOW and asks afterwards."
adm rule mk --token "AR-B$RUN" --requirement ALL_GROUPS --desc "pin a signal to last-known-good" --aspect disposition:downstream --aspect efficacy:notify:downstream >/dev/null
note "alice owns nothing downstream of it:"
refused --as alice ap mk "$SIG/velocity_24h" "AR-B$RUN" --ratify
B=$(tlc --as bob --json ap mk "$SIG/velocity_24h" "AR-B$RUN" --ratify --ttl 3600 --context "velocity_24h stale since 14:02; pinned to last-known-good" | ap)
run --as bob ap show "$B"
note "bob can't ratify his own pull. olga (OPS) can't flip it back either — her lever is to withhold, and the TTL reverts it."
refused --as bob ap approve "$B"
refused --as olga ap decline "$B"
run --as olga ap approve "$B" --comment "ok, queue is quiet"

say "Nobody ratifies in time -> REVERTED. The approvables feed tells the source service to undo it."
B2=$(tlc --as bob --json ap mk "$SIG/geo_mismatch" "AR-B$RUN" --ratify --ttl 2 --context "geo vendor flapping; pinned" | ap)
note "ttl=2s for the demo (default is 4h) …"; sleep 4
run --as bob ap show "$B2"

say "What was said, to whom (consenting owners are asked; NOTIFY owners and stakeholders are told)"
printf '\033[33m$ curl %s/api/notifications?approvable=%s\033[0m\n' "$TLC_URL" "$B"
curl -s -H 'X-TLC-User: alice' "$TLC_URL/api/notifications?approvable=$B" | python3 "$HERE/_fmt.py" notifications

say "The audit trail on the signal"
run --as alice log ls --target "$SIG/velocity_24h"

# leave nothing in the type lists; the records and their history stay queryable
for e in signals rules triggers hooks; do adm type rm "$S/$e" >/dev/null; done
