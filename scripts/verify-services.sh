#!/usr/bin/env bash
# Bring up the distributed Compose stack and assert what only separate processes can show.
#
#   scripts/verify-services.sh           build, start, assert, leave it running
#   scripts/verify-services.sh --down    stop and remove it, including the volume
#
# The single-JVM TopologyParityTest covers the seams. This covers the fleet: an importer that
# exits, serving processes that wait for it, readiness that crosses a container boundary,
# twelve concurrent ticket writes from outside the JVM leaving three rows, and what the public
# port does when a downstream container is stopped and started again. No model key is needed:
# a placeholder key proves retrieval crosses the seam before the provider is reached, because
# the failure is then a 502 from the provider rather than a 503 from knowledge.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
COMPOSE=(docker compose -f "$ROOT/docker-compose.services.yml")
export INTERNAL_TOKEN=${INTERNAL_TOKEN:-$(openssl rand -hex 16)}
export ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY:-placeholder-no-model-call-is-made-during-startup}
PASS=0; FAIL=0

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }
# Assertions compare values captured in *this* shell. The first version wrapped pipelines in
# `sh -c` the way k8s/kind/verify.sh does and called `inside` and `sql` from there -- shell
# functions a child `sh` has never heard of -- and five assertions failed against a stack
# that was doing everything right. Capture, then compare; no function crosses a `sh -c`.
expect(){ local d=$1 got=$2 want=$3; if [[ $got == $want ]]; then ok "$d ($got)"; else bad "$d: got '$got', want '$want'"; fi; }
contains(){ local d=$1 hay=$2 needle=$3; if [[ $hay == *"$needle"* ]]; then ok "$d"; else bad "$d: no '$needle' in: ${hay:0:160}"; fi; }
# curl from inside the chat container, which is on the compose network and has curl (the
# image's HEALTHCHECK uses it). Only chat has a host port.
inside(){ "${COMPOSE[@]}" exec -T chat "$@"; }
sql()   { "${COMPOSE[@]}" exec -T postgres psql -U "${POSTGRES_USER:-csagent}" -d "${POSTGRES_DB:-csagent}" -tAc "$1"; }
status(){ curl -s -o /dev/null -w '%{http_code}' "$@" || echo 000; }

if [[ ${1:-} == --down ]]; then
  "${COMPOSE[@]}" down -v --remove-orphans
  exit 0
fi

say "up"
"${COMPOSE[@]}" up --build -d --wait --wait-timeout 300

say "the importer ran once and exited"
import_state=$("${COMPOSE[@]}" ps -a --format json import | python3 -c '
import json,sys
rows=[json.loads(l) for l in sys.stdin if l.strip()]
print(rows[0]["State"], rows[0].get("ExitCode", "?")) if rows else print("absent ?")')
[[ $import_state == "exited 0" ]] && ok "import exited 0 ($import_state)" || bad "import state: $import_state, want exited 0"
expect "the corpus version is recorded once" "$(sql 'select count(*) from corpus_import')" 1
expect "36 documents are in the store"       "$(sql 'select count(*) from vector_store')" 36

say "readiness crosses the seam"
knowledge_ready=$(inside curl -s http://knowledge:8080/actuator/health/readiness)
chat_ready=$(curl -s "localhost:${APP_PORT:-8080}/actuator/health/readiness")
ticket_ready=$(inside curl -s http://ticket:8080/actuator/health/readiness)
contains "knowledge readiness reports its corpus"        "$knowledge_ready" '"documents":36'
contains "chat readiness is UP and names knowledge"      "$chat_ready"      '"knowledge"'
contains "ticket readiness is UP"                        "$ticket_ready"    '"status":"UP"'
[[ $ticket_ready != *corpus* ]] && ok "ticket readiness has no corpus indicator at all" \
                                 || bad "ticket readiness mentions a corpus it does not own"

say "the token"
without=$(inside curl -s -o /dev/null -w '%{http_code}' 'http://ticket:8080/internal/v1/tickets?conversationId=x')
with=$(inside curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $INTERNAL_TOKEN" 'http://ticket:8080/internal/v1/tickets?conversationId=x')
[[ $without == 401 ]] && ok "no token is 401" || bad "no token returned $without, want 401"
[[ $with == 200 ]]    && ok "the token is 200" || bad "the token returned $with, want 200"

say "twelve concurrent ticket writes from outside the JVM"
# Distinct summaries, one conversation, all at once, straight at the ticket process. The
# guard row is what makes the answer three; nothing in the caller does.
inside sh -c "for i in \$(seq 1 12); do
  curl -s -o /dev/null -X POST http://ticket:8080/internal/v1/tickets \
    -H 'Content-Type: application/json' -H 'Authorization: Bearer $INTERNAL_TOKEN' \
    -d \"{\\\"operationId\\\":\\\"smoke-op-\$i\\\",\\\"conversationId\\\":\\\"smoke-conversation\\\",\\\"summary\\\":\\\"Distinct problem \$i\\\",\\\"category\\\":\\\"other\\\"}\" &
done; wait"
tickets=$(sql "select count(*) from support_ticket where conversation_id='smoke-conversation'")
operations=$(sql "select count(*) from ticket_operation where conversation_id='smoke-conversation'")
[[ $tickets == 3 ]]     && ok "3 rows in support_ticket"      || bad "$tickets rows in support_ticket, want 3"
[[ $operations == 12 ]] && ok "12 rows in ticket_operation"   || bad "$operations rows in ticket_operation, want 12"
# Which three won is a race; the record says. Replaying one that was refused must stay
# refused, and replaying one that was created must return the same ticket, not a new one.
created_op=$(sql "select operation_id from ticket_operation where conversation_id='smoke-conversation' and status='CREATED' limit 1")
refused_op=$(sql "select operation_id from ticket_operation where conversation_id='smoke-conversation' and status='REFUSED' limit 1")
replay_created=$(inside curl -s -X POST http://ticket:8080/internal/v1/tickets \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $INTERNAL_TOKEN" \
    -d "{\"operationId\":\"$created_op\",\"conversationId\":\"smoke-conversation\",\"summary\":\"Distinct problem ${created_op##*-}\",\"category\":\"other\"}")
replay_refused=$(inside curl -s -X POST http://ticket:8080/internal/v1/tickets \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $INTERNAL_TOKEN" \
    -d "{\"operationId\":\"$refused_op\",\"conversationId\":\"smoke-conversation\",\"summary\":\"Distinct problem ${refused_op##*-}\",\"category\":\"other\"}")
contains "replaying a created operation answers CREATED from its record" "$replay_created" '"status":"CREATED"'
contains "replaying a refused operation stays REFUSED"                    "$replay_refused" '"status":"REFUSED"'
expect   "and the replays wrote nothing" "$(sql "select count(*) from support_ticket where conversation_id='smoke-conversation'")" 3

say "a turn crosses the seam before it reaches the provider"
turn=$(status localhost:${APP_PORT:-8080}/api/v1/chat -H 'Content-Type: application/json' \
         -d '{"message":"How long do I have to return an item?"}')
if [[ $ANTHROPIC_API_KEY == placeholder* ]]; then
  [[ $turn == 502 ]] && ok "a bad key surfaces as 502 after retrieval, not a 503 from knowledge" \
                     || bad "a turn returned $turn, want 502"
else
  [[ $turn == 200 ]] && ok "a real turn answered (200)" || bad "a real turn returned $turn, want 200"
fi

say "with knowledge stopped"
"${COMPOSE[@]}" stop knowledge >/dev/null
sleep 2
readiness=$(status localhost:${APP_PORT:-8080}/actuator/health/readiness)
turn=$(status localhost:${APP_PORT:-8080}/api/v1/chat -H 'Content-Type: application/json' \
         -d '{"message":"How long do I have to return an item?"}')
[[ $readiness == 503 ]] && ok "chat readiness is 503" || bad "chat readiness returned $readiness, want 503"
[[ $turn == 503 ]]      && ok "a turn is 503, not an ungrounded answer" || bad "a turn returned $turn, want 503"
"${COMPOSE[@]}" start knowledge >/dev/null
for _ in $(seq 1 60); do
  [[ $(status localhost:${APP_PORT:-8080}/actuator/health/readiness) == 200 ]] && break
  sleep 2
done
expect "chat readiness recovers when knowledge is back" "$(status "localhost:${APP_PORT:-8080}/actuator/health/readiness")" 200

say "result"
printf '  %d passed, %d failed\n' "$PASS" "$FAIL"
printf '  stack left running; %s --down to remove it\n' "$0"
[[ $FAIL -eq 0 ]]
