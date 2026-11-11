#!/usr/bin/env bash
# The same service with nothing but curl: what an integration actually sends.
set -euo pipefail
U="${TLC_URL:-http://localhost:8790}"; RUN="$(date +%H%M%S)"; E="billing/invoices/inv-$RUN"
j() { python3 -m json.tool --compact 2>/dev/null || cat; echo; }
c() { local who="$1" m="$2" p="$3"; shift 3; printf '\033[33m%s %s  (as %s)\033[0m\n' "$m" "$p" "$who" >&2; curl -s -X "$m" -H "X-TLC-User: $who" -H 'Content-Type: application/json' "$U$p" "$@"; }

c "${TLC_ADMIN:-traviscj}" POST /api/entity-types -d '{"source_service":"billing","source_entity":"invoices","url_template":"https://billing.example/i/{id}"}' | j
c alice POST /api/taggings/tag -d "{\"target\":\"$E\",\"tag\":\"disputed\",\"create_tag\":true,\"note\":\"customer emailed\"}" | j
c alice GET "/api/taggings/is-tagged?target=$E&tag=disputed" | j
c alice POST /api/logs -d "{\"target\":\"$E\",\"level\":\"WARN\",\"source\":\"billing.dunning\",\"message\":\"dunning paused while disputed\"}" | j
T=$(c alice POST /api/threads -d "{\"target\":\"$E\",\"title\":\"refund or hold?\",\"body\":\"customer says double-charged\"}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
C=$(c bob POST "/api/threads/$T/comments" -d '{"content":"ledger shows one charge. hold."}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["comment_token"])')
c alice POST "/api/comments/$C/like" | j
c alice POST /api/comments -d "{\"parent_comment\":\"$C\",\"content\":\"agreed\"}" | j
c alice GET "/api/threads/$T" | j
c "${TLC_ADMIN:-traviscj}" POST /api/approval-rules -d "{\"token\":\"AR-C$RUN\",\"description\":\"refunds over \$500\",\"requirement\":\"ANY_NON_SELF_APPROVAL\"}" | j
AP=$(c alice POST /api/approvables -d "{\"target\":\"$E\",\"rule\":\"AR-C$RUN\",\"context\":\"refund \$612\"}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["approvable"]["token"])')
c alice POST "/api/approvables/$AP/approve" -d '{}' ; echo "   ^ 403: self"
c bob POST "/api/approvables/$AP/approve" -d '{"comment":"ok"}' | j
c alice POST "/api/approvables/$AP/execute" -d '{}' | j
c alice GET "/api/summary?target=$E" | j
