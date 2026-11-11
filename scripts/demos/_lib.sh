# Sourced by every demo. Each run uses a fresh id suffix so demos can be re-run against the same DB.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export TLC_URL="${TLC_URL:-http://localhost:8790}"
ADMIN="${TLC_ADMIN:-traviscj}"          # must be in tlc.admins
RUN="${RUN:-$(date +%H%M%S)}"
tlc() { "$HERE/../tlc" "$@"; }
say() { printf '\n\033[1;36m## %s\033[0m\n' "$*"; }
note() { printf '\033[2m   %s\033[0m\n' "$*"; }
run() { printf '\033[33m$ tlc %s\033[0m\n' "$*"; tlc "$@"; }
# for commands that are *supposed* to be refused
refused() { printf '\033[33m$ tlc %s\033[0m\n' "$*"; if out=$(tlc "$@" 2>&1); then echo "$out"; echo "!! expected a refusal"; exit 1; else printf '\033[31m%s\033[0m\n' "$out"; fi; }
curl -sf -o /dev/null "$TLC_URL/" || { echo "TLC isn't up at $TLC_URL — start it: (cd $(dirname "$HERE")/.. && . bin/activate-hermit && ./gradlew run)"; exit 1; }
# shared fixtures; conflicts mean "already there"
tlc --as "$ADMIN" type mk sentinel/proposed_changes --url-template 'https://sentinel.example/pc/{id}' >/dev/null 2>&1 || true
