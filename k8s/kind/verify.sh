#!/usr/bin/env bash
# Verify the Kubernetes manifests on a throwaway kind cluster.
#
#   k8s/kind/verify.sh            create the cluster, deploy, assert, leave it running
#   k8s/kind/verify.sh --down     delete the cluster and exit
#   k8s/kind/verify.sh --keep     skip the image rebuild if the tag is already present
#
# This applies the manifests in k8s/ *unmodified*. That is the whole point: a harness that
# patches the resources or the image before applying would verify the patch, not the file
# anyone else is going to use. The only things it adds are the two the manifests
# deliberately do not ship -- a Postgres (k8s/kind/postgres.yaml) and a Secret, created
# imperatively the way k8s/README.md tells you to.
#
# The Secret gets a placeholder ANTHROPIC_API_KEY unless one is exported. Nothing in
# startup or in either probe calls the model, so a fake key verifies everything except the
# model call itself -- and it verifies that a bad key surfaces as 502 rather than as a
# healthy pod serving errors. Export a real key to check the model path too.
#
# Written because the manifests were committed unverified and were wrong: the memory limit
# OOM-killed the pod during startup, and the Secret template was in the directory apply
# path. Both are regressions this script would now catch.
set -euo pipefail

CLUSTER=${CLUSTER:-ai-cs}
NS=ai-customer-service
IMAGE=$(grep -m1 'image: ghcr.io' "$(dirname "$0")/../deployment.yaml" | awk '{print $2}')
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PASS=0; FAIL=0

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }
check(){ local d=$1; shift; if "$@" >/dev/null 2>&1; then ok "$d"; else bad "$d"; fi; }

if [[ ${1:-} == --down ]]; then kind delete cluster --name "$CLUSTER"; exit 0; fi

for t in kind kubectl docker; do
  command -v "$t" >/dev/null || { echo "missing: $t" >&2; exit 1; }
done

say "cluster"
kind get clusters 2>/dev/null | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" --wait 120s
kubectl config use-context "kind-$CLUSTER" >/dev/null

say "image  $IMAGE"
if [[ ${1:-} == --keep ]] && docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "  reusing the local image"
else
  docker build -t "$IMAGE" "$ROOT"
fi
kind load docker-image "$IMAGE" --name "$CLUSTER"

say "deploy"
kubectl apply -f "$ROOT/k8s/namespace.yaml"
kubectl apply -f "$ROOT/k8s/kind/postgres.yaml"
kubectl -n "$NS" rollout status deploy/postgres --timeout=180s

kubectl -n "$NS" create secret generic ai-customer-service-secrets \
  --from-literal=ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-placeholder-no-model-call-is-made-during-startup}" \
  --from-literal=POSTGRES_USER=csagent \
  --from-literal=POSTGRES_PASSWORD=csagent \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# The directory form on purpose: it must be safe, and it was not before the Secret
# template moved into k8s/examples/.
kubectl apply -f "$ROOT/k8s/"
# Deliberately not fatal. A failed rollout is a result, not a reason to stop: the
# assertions below are what say *why* it failed, and "OOMKilled -- the memory limit is
# too low" is a better last line than a rollout timeout.
kubectl -n "$NS" rollout status deploy/ai-customer-service --timeout=300s || true

say "assertions"
POD=$(kubectl -n "$NS" get pods -l app.kubernetes.io/component=app \
        -o jsonpath='{.items[?(@.status.phase=="Running")].metadata.name}' | awk '{print $1}')

replicas=$(kubectl -n "$NS" get deploy ai-customer-service -o jsonpath='{.status.readyReplicas}')
[[ ${replicas:-0} == 2 ]] && ok "both replicas ready" || bad "readyReplicas=${replicas:-0}, want 2"

# The bug this script exists for. A too-small limit does not fail the rollout on a fast
# machine every time -- it fails during the ONNX session, which is late enough to look
# like a probe problem.
if kubectl -n "$NS" get pods -l app.kubernetes.io/component=app -o json |
     grep -q OOMKilled; then bad "a container was OOMKilled -- the memory limit is too low"
else ok "no container was OOMKilled"; fi

# The second bug this script exists for. The Secret is created before the directory apply
# above; if a template with placeholders is ever reachable from `kubectl apply -f k8s/`
# again, this is the credential it will be holding afterwards.
if kubectl -n "$NS" get secret ai-customer-service-secrets \
     -o jsonpath='{.data.ANTHROPIC_API_KEY}' | base64 -d | grep -q REPLACE_ME; then
  bad "the directory apply overwrote the Secret with placeholders"
else ok "the directory apply left the Secret alone"; fi

check "runs as uid 10001"        sh -c "kubectl -n $NS exec $POD -- id -u | grep -qx 10001"
check "root filesystem read-only" sh -c "kubectl -n $NS exec $POD -- sh -c 'touch /nope' 2>&1 | grep -q 'Read-only'"
check "/tmp is writable"          kubectl -n "$NS" exec "$POD" -- sh -c 'touch /tmp/.probe && rm /tmp/.probe'
# ONNX Runtime System.load()s its .so out of this emptyDir; a noexec mount fails startup
# with UnsatisfiedLinkError. The unpacked directory is the evidence it worked.
check "ONNX unpacked its native library into /tmp" \
      sh -c "kubectl -n $NS exec $POD -- ls /tmp | grep -q onnxruntime-java"

kubectl -n "$NS" port-forward svc/ai-customer-service 18080:8080 >/dev/null 2>&1 &
PF=$!; trap 'kill $PF 2>/dev/null || true' EXIT
sleep 4

check "health is UP through the Service" \
      sh -c "curl -sf localhost:18080/actuator/health | grep -q '\"status\":\"UP\"'"
check "readiness includes the datasource" \
      sh -c "curl -sf localhost:18080/actuator/health/readiness | grep -q UP"
check "Prometheus endpoint serves metrics" \
      sh -c "curl -sf localhost:18080/actuator/prometheus | grep -q '^jvm_memory_used_bytes'"

# `|| true`: when the deployment is broken there is nothing to connect to, curl exits 7,
# and under `set -e` that killed the script before it printed the summary -- so a failing
# run reported less than a passing one, which is backwards.
status=$(curl -s -o /dev/null -w '%{http_code}' localhost:18080/api/v1/chat \
           -H 'Content-Type: application/json' \
           -d '{"message":"How long do I have to return an item?"}' || echo 000)
if [[ -n ${ANTHROPIC_API_KEY:-} ]]; then
  [[ $status == 200 ]] && ok "a real turn answered (200)" || bad "a real turn returned $status, want 200"
else
  # Retrieval runs before the model call, so this exercises the embedding path and then
  # fails at the provider -- which must be a 502, not a 500 and not a healthy 200.
  [[ $status == 502 ]] && ok "a bad key surfaces as 502, not a healthy error" \
                       || bad "a bad key returned $status, want 502"
fi

say "result"
printf '  %d passed, %d failed\n' "$PASS" "$FAIL"
printf '  cluster left running; %s --down to remove it\n' "$0"
[[ $FAIL -eq 0 ]]
