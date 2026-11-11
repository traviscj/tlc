#!/usr/bin/env bash
# Logs: append-only entries, optionally about one record or one type.
. "$(dirname "$0")/_lib.sh"
E="sentinel/proposed_changes/l$RUN"

say "A service appends; source defaults to the calling service."
run --service sentinel log add "$E" "proposed change created"
run --service sentinel log add "$E" "backtest finished: 0.4% more holds" --source sentinel.backtest
run --service sentinel log add "$E" "backtest sample smaller than usual (n=812)" --level WARN --source sentinel.backtest
run --service sentinel log add "$E" "deploy to canary failed: timeout" --level ERROR --source sentinel.deploy

say "Batch append is one POST (all-or-nothing); entries may also be scoped to a type, or to nothing."
printf '\033[33m$ curl -X POST %s/api/logs -d {"entries":[…]}\033[0m\n' "$TLC_URL"
curl -s -H 'X-TLC-Service: sentinel' -H 'Content-Type: application/json' "$TLC_URL/api/logs" -d '{"entries":[
  {"entity_type":"sentinel/proposed_changes","level":"INFO","message":"nightly reindex of proposed_changes done"},
  {"level":"DEBUG","message":"sentinel heartbeat"}]}'; echo

say "Query: by target, minimum level, source prefix, substring"
run --as alice log ls --target "$E"
run --as alice log ls --target "$E" --level WARN
run --as alice log ls --source sentinel.backtest
run --as alice log ls -q canary

say "TLC writes its own audit trail into the same log (source tlc.*), in the same transaction as the change"
run --as alice tag add "$E" needs-eyes
run --as alice thread mk "why did canary time out?" --target "$E" >/dev/null
run --as alice log ls --target "$E" --source tlc.

say "tail (one shot here; drop --once to follow)"
run --as alice log tail --target "$E" --once
