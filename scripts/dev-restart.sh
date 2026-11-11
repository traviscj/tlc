#!/usr/bin/env bash
# Rebuild and (re)start the dev server in the background; waits until it answers. Log: build/tlc-run.log
set -euo pipefail
cd "$(dirname "$0")/.."
. bin/activate-hermit >/dev/null
PORT="${TLC_PORT:-8790}"
pid=$(lsof -tiTCP:"$PORT" -sTCP:LISTEN || true)
[ -n "$pid" ] && kill $pid && sleep 1
./gradlew installDist -q
mkdir -p build
TLC_CONFIG="${TLC_CONFIG:-$([ -f tlc-local.yaml ] && echo "$PWD/tlc-local.yaml" || true)}" nohup build/install/tlc/bin/tlc > build/tlc-run.log 2>&1 &
for i in $(seq 1 60); do curl -sf -o /dev/null "http://localhost:$PORT/" && { echo "tlc up on :$PORT (pid $(lsof -tiTCP:"$PORT" -sTCP:LISTEN))"; exit 0; }; sleep 0.5; done
echo "tlc did not come up; see build/tlc-run.log"; tail -20 build/tlc-run.log; exit 1
