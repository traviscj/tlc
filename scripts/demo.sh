#!/usr/bin/env bash
# Runs every demo in order against a running TLC (default http://localhost:8790).
set -euo pipefail
cd "$(dirname "$0")/demos"
for d in 0*.sh; do printf '\n\033[1;35m════════ %s ════════\033[0m\n' "$d"; RUN="$(date +%H%M%S)" bash "$d"; done
