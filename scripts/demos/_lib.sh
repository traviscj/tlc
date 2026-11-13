# Sourced by every demo. Each run uses a fresh id suffix so demos can be re-run against the same DB.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export TLC_URL="${TLC_URL:-http://localhost:8790}"
ADMIN="${TLC_ADMIN:-traviscj}"          # must be in tlc.admins
RUN="${RUN:-$(date +%H%M%S)}"
tlc() { "$HERE/../tlc" "$@"; }
# Pacing, for recordings (scripts/record_demos.py): seconds to hold after each ## step header / after each command.
pace() { [ "${1:-0}" = 0 ] || sleep "$1"; }
say() { printf '\n\033[1;36m## %s\033[0m\n' "$*"; pace "${TLC_DEMO_PACE:-0}"; }
note() { printf '\033[2m   %s\033[0m\n' "$*"; }
run() { local rc=0; printf '\033[33m$ tlc %s\033[0m\n' "$*"; tlc "$@" || rc=$?; pace "${TLC_DEMO_CMD_PACE:-0}"; return $rc; }
# for commands that are *supposed* to be refused
refused() { printf '\033[33m$ tlc %s\033[0m\n' "$*"; if out=$(tlc "$@" 2>&1); then echo "$out"; echo "!! expected a refusal"; exit 1; else printf '\033[31m%s\033[0m\n' "$out"; fi; pace "${TLC_DEMO_CMD_PACE:-0}"; }
curl -sf -o /dev/null "$TLC_URL/" || { echo "TLC isn't up at $TLC_URL — start it: (cd $(dirname "$HERE")/.. && . bin/activate-hermit && ./gradlew run)"; exit 1; }
# shared fixtures; conflicts mean "already there"
tlc --as "$ADMIN" type mk sentinel/proposed_changes --url-template 'https://sentinel.example/pc/{id}' >/dev/null 2>&1 || true
