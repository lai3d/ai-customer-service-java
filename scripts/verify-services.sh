#!/usr/bin/env bash
# Bring up the distributed Compose stack and assert what only separate processes can show.
#
#   scripts/verify-services.sh           build, start, assert, leave it running
#   COLLECTOR=1 scripts/verify-services.sh
#                                        the same through the OpenTelemetry Collector: the
#                                        application pushes traces and metrics over OTLP and the
#                                        pipeline assertions must still hold, including a series
#                                        only the push path produces
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
# The Grafana stack is optional in the file and required by the pipeline assertions below.
# Set as an environment variable rather than --profile, because the app's trace export reads
# COMPOSE_PROFILES too: that is what keeps export off when nobody asked for the stack.
export COMPOSE_PROFILES=observability
if [[ ${COLLECTOR:-0} == 1 ]]; then
  export COMPOSE_PROFILES=observability,collector
  export OTLP_TRACING_ENDPOINT=http://otel-collector:4318/v1/traces
  export OTLP_METRICS_EXPORT_ENABLED=true
  export OTLP_METRICS_ENDPOINT=http://otel-collector:4318/v1/metrics
fi
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

say "the observability pipeline"
# The trace id of a turn, read from a Prometheus exemplar: metric -> trace is the first link
# in the chain, and with a placeholder key the model call fails before the stream's usage
# event would have carried the id. Prometheus scrapes every 15 s, so wait for one.
curl -s -o /dev/null "localhost:${APP_PORT:-8080}/api/v1/chat" -H 'Content-Type: application/json' -d '{"message":"运费多少钱"}'
trace_id=""
for _ in $(seq 1 20); do
  trace_id=$(curl -s "localhost:${PROMETHEUS_PORT:-9090}/api/v1/query_exemplars" \
      --data-urlencode 'query=http_server_requests_seconds_bucket{uri=~"/api/v1/chat.*"}' \
      --data-urlencode "start=$(( $(date +%s) - 900 ))" --data-urlencode "end=$(date +%s)" \
      | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; ex=[e for s in d for e in s["exemplars"]]; print(ex[-1]["labels"]["trace_id"] if ex else "")')
  [[ ${#trace_id} -eq 32 ]] && break; sleep 3
done
[[ ${#trace_id} -eq 32 ]] && ok "a turn's latency bucket carries an exemplar with its trace id ($trace_id)" \
                          || bad "no exemplar on http_server_requests_seconds_bucket for the chat endpoints"
targets=$(curl -s "localhost:${PROMETHEUS_PORT:-9090}/api/v1/targets" | python3 -c '
import json,sys
t=json.load(sys.stdin)["data"]["activeTargets"]
down=[a["labels"].get("job") for a in t if a["health"]!="up"]
print("all %d up" % len(t) if not down else "down: " + ",".join(down))')
contains "every Prometheus target is up" "$targets" "all "
buckets=$(curl -s "localhost:${PROMETHEUS_PORT:-9090}/api/v1/query" --data-urlencode 'query=count(http_server_requests_seconds_bucket{uri=~"/api/v1/chat.*"})' \
    | python3 -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else 0)')
[[ ${buckets:-0} -gt 0 ]] && ok "turn latency is a histogram in Prometheus ($buckets series)" || bad "no http_server_requests buckets for the chat endpoints"
datasources=$(curl -s "localhost:${GRAFANA_PORT:-3000}/api/datasources" | python3 -c 'import json,sys; print(",".join(sorted(d["uid"] for d in json.load(sys.stdin))))')
expect "Grafana has the three datasources" "$datasources" "loki,prometheus,tempo"
dashboards=$(curl -s "localhost:${GRAFANA_PORT:-3000}/api/search?type=dash-db" | python3 -c 'import json,sys; print(",".join(sorted(d["uid"] for d in json.load(sys.stdin))))')
expect "Grafana provisioned both dashboards" "$dashboards" "cs-roles,cs-service"
# Tempo and Loki ingest in the background; a few seconds before calling anything absent.
found=000
for _ in $(seq 1 20); do
  found=$(inside curl -s -o /dev/null -w '%{http_code}' "http://tempo:3200/api/traces/$trace_id")
  [[ $found == 200 ]] && break; sleep 2
done
expect "Tempo has the turn's trace" "$found" 200
services_in_trace=$(inside curl -s "http://tempo:3200/api/traces/$trace_id" | python3 -c '
import json,sys
names=set()
for batch in json.load(sys.stdin).get("batches",[]):
    for a in batch.get("resource",{}).get("attributes",[]):
        if a["key"]=="service.name": names.add(a["value"]["stringValue"])
print(",".join(sorted(names)))')
contains "the trace carries the application's spans" "$services_in_trace" "ai-customer-service-java"
logs=0
for _ in $(seq 1 20); do
  logs=$(inside curl -s -G "http://loki:3100/loki/api/v1/query_range" \
      --data-urlencode "query={service=~\".+\"} | traceId = \"$trace_id\"" --data-urlencode 'limit=5' | python3 -c '
import json,sys
r=json.load(sys.stdin).get("data",{}).get("result",[]); print(sum(len(s["values"]) for s in r))')
  [[ ${logs:-0} -gt 0 ]] && break; sleep 2
done
[[ ${logs:-0} -gt 0 ]] && ok "Loki has log lines for the trace, by structured metadata ($logs)" || bad "no log lines in Loki carry traceId=$trace_id"
if [[ ${COLLECTOR:-0} == 1 ]]; then
  # The Collector adds the resource attributes as labels; a scrape never carries service_name.
  # So this series exists only if the push path delivered the same histogram under the same name.
  pushed=0
  for _ in $(seq 1 20); do
    pushed=$(curl -s "localhost:${PROMETHEUS_PORT:-9090}/api/v1/query" --data-urlencode 'query=count(http_server_requests_seconds_bucket{service_name="ai-customer-service-java", uri=~"/api/v1/chat.*"})' \
        | python3 -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else 0)')
    [[ ${pushed:-0} -gt 0 ]] && break; sleep 3
  done
  [[ ${pushed:-0} -gt 0 ]] && ok "the OTLP push path delivered the turn histogram under the pull path's name ($pushed series)" \
                           || bad "no http_server_requests_seconds_bucket with service_name from the Collector"
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
