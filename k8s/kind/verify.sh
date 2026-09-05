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
#
# To see the CREATE EXTENSION assertion go red -- worth doing after changing anything near
# SchemaInitializationLock, because an assertion nobody has watched fail is a claim:
#
#   kubectl -n ai-customer-service set env deploy/ai-customer-service \
#     APP_SCHEMA_SERIALIZE_INITIALIZATION=false
#   kubectl -n ai-customer-service delete pod -l app.kubernetes.io/component=app
#   # then drop the schema so the cold path is exercised, and re-run this script
set -euo pipefail

CLUSTER=${CLUSTER:-ai-cs}
NS=ai-customer-service
IMAGE=$(grep -m1 'image: ghcr.io' "$(dirname "$0")/../base/deployment.yaml" | awk '{print $2}')
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PASS=0; FAIL=0

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }
# Every caller that needs a pipeline wraps it in `sh -c`, and that is load-bearing rather
# than stylistic: `set -o pipefail` is a shell option, not an environment variable, so a
# fresh `sh` starts with it off and the pipeline reports grep's status. Run the same
# pipeline in *this* shell and pipefail turns a match into a failure -- grep -q exits at the
# first hit, the producer takes SIGPIPE and exits 141. Verified both ways with a producer
# that keeps writing after the match; a short one completes first and hides it. Moving a
# pipeline out of `sh -c` here silently inverts the check.
check(){ local d=$1; shift; if "$@" >/dev/null 2>&1; then ok "$d"; else bad "$d"; fi; }

# Every kubectl call names its context instead of switching the current one. `kubectl config
# use-context` edits the user's kubeconfig globally, and a parallel session found its namespace
# apparently empty because this script had moved its context out from under it.
#
# `kind create` switches it anyway as a side effect, so the original is captured here -- before
# anything touches the kubeconfig -- and restored on exit. The first version captured it after
# the cluster was created, which faithfully restored the context kind had just set: a restore
# that runs, reports nothing, and puts back the wrong value.
kubectl() { command kubectl --context "kind-$CLUSTER" "$@"; }
ORIGINAL_CONTEXT=$(command kubectl config current-context 2>/dev/null || true)
trap '[[ -n $ORIGINAL_CONTEXT ]] && command kubectl config use-context "$ORIGINAL_CONTEXT" >/dev/null 2>&1 || true' EXIT

if [[ ${1:-} == --down ]]; then kind delete cluster --name "$CLUSTER"; exit 0; fi

for t in kind kubectl docker; do
  command -v "$t" >/dev/null || { echo "missing: $t" >&2; exit 1; }
done

say "cluster"
kind get clusters 2>/dev/null | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" --wait 120s


say "image  $IMAGE"
if [[ ${1:-} == --keep ]] && docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "  reusing the local image"
else
  docker build -t "$IMAGE" "$ROOT"
fi
kind load docker-image "$IMAGE" --name "$CLUSTER"

# Does this machine have room for what the manifests ask for? A kind cluster is one node,
# so both replicas land on it and their limits are added together -- 2 x 4Gi against a
# Docker VM that is 7.75 GiB by default is 108% of the node. The pods schedule anyway,
# because requests fit, and then the kernel kills one while both are loading the 470 MB
# ONNX model.
#
# This is worth checking up front rather than reporting as an OOM at the end, because it is
# a property of the laptop and not of the manifests -- and an assertion that blames the
# manifests for the machine is worse than no assertion. It stayed hidden until the
# CREATE EXTENSION race was fixed: the crash it caused was staggering the two model loads.
say "capacity"
# Read from the rendered spec, not from a grep and not from a number typed here. A check that
# hardcodes the limit it is checking keeps passing after someone changes the limit -- which is
# the failure this whole harness exists to catch, and it was in this function until a parallel
# session hit the same shape in theirs and said so.
node_ki=$(kubectl get node "${CLUSTER}-control-plane" -o jsonpath='{.status.allocatable.memory}' | sed 's/Ki$//')
spec=$(command kubectl create -f "$ROOT/k8s/base/deployment.yaml" --dry-run=client -o \
         jsonpath='{.spec.replicas} {.spec.template.spec.containers[0].resources.limits.memory}')
replicas=${spec%% *}; limit=${spec##* }
if [[ ! $node_ki =~ ^[0-9]+$ || ! $replicas =~ ^[0-9]+$ || -z $limit ]]; then
  echo "  could not read node capacity or the deployment's limits (node=$node_ki spec=$spec)" >&2
  exit 1
fi
limit_mib=$(python3 -c "
import re,sys
v=sys.argv[1]; m=re.match(r'(\d+)(Gi|Mi|G|M)?$', v)
u={'Gi':1024,'Mi':1,'G':954,'M':1}[m.group(2) or 'Mi']
print(int(m.group(1))*u)" "$limit")
node_mib=$((node_ki / 1024))
want_mib=$((replicas * limit_mib + 1024))
printf '  node allocatable %s MiB; %s replicas x %s = %s MiB, plus Postgres\n' \
       "$node_mib" "$replicas" "$limit" "$want_mib"
if [[ $node_mib -lt $want_mib ]]; then
  printf '  \033[33mNOTE\033[0m this node cannot hold both replicas at their limits. Any OOMKill\n'
  printf '       below is this machine, not the manifests -- give Docker more memory, or\n'
  printf '       scale to one replica to check everything else.\n'
fi

# The property the kind overlay exists to have: it adds Postgres and changes nothing else.
# An overlay that quietly lowered the replica count or the memory limit to fit a laptop would
# make every assertion below true of a manifest nobody deploys.
say "overlay"
command kubectl kustomize "$ROOT/k8s/base" > /tmp/verify-base.yaml
command kubectl kustomize "$ROOT/k8s/kind" > /tmp/verify-kind.yaml
if python3 - /tmp/verify-base.yaml /tmp/verify-kind.yaml << 'PY'
import re, sys

def name(doc):
    # Enough to say *which* object changed. Reporting the first line instead says
    # "apiVersion: apps/v1", which is true of half the file and useless in a failure.
    kind = re.search(r'^kind: (\S+)', doc, re.M)
    named = re.search(r'^metadata:\n(?:  .*\n)*?  name: (\S+)', doc, re.M)
    return f"{kind.group(1) if kind else '?'}/{named.group(1) if named else '?'}"

base, kind_build = (open(p).read() for p in sys.argv[1:3])
docs = [d for d in base.split('\n---\n') if d.strip()]
changed = [name(d) for d in docs if d.strip() not in kind_build]
print(f"  {len(docs) - len(changed)} of {len(docs)} base documents reproduced verbatim")
for c in changed:
    print(f"  CHANGED: {c}")
sys.exit(1 if changed else 0)
PY
then ok "the kind overlay leaves the base unmodified"
else bad "the kind overlay modifies the base -- the assertions below would not be about the real manifests"; fi

# An example that stopped building would be worse than no example.
check "the example overlay builds" command kubectl kustomize "$ROOT/k8s/overlays/example"

say "deploy"
kubectl apply -f "$ROOT/k8s/base/namespace.yaml"

kubectl -n "$NS" create secret generic ai-customer-service-secrets \
  --from-literal=ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-placeholder-no-model-call-is-made-during-startup}" \
  --from-literal=POSTGRES_USER=csagent \
  --from-literal=POSTGRES_PASSWORD=csagent \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# The overlay, which is what the README tells people to apply. The Secret template is not
# reachable from it -- it was reachable from `kubectl apply -f k8s/`, and overwrote working
# credentials with placeholders.
kubectl apply -k "$ROOT/k8s/kind"
kubectl -n "$NS" rollout status deploy/postgres --timeout=180s
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

# This was a KNOWN line reporting an unfixed defect until SchemaInitializationLock took an
# advisory lock across the schema-creating beans. It is an assertion now because the race
# is supposed to be gone -- and because the lock matches its targets by class name, which
# a Spring AI rename would silently defeat. A unit test catches the rename; this catches
# everything else that could stop the lock working on a real two-replica rollout.
#
# `grep -c`, not `grep -q`, and the `|| true` is load-bearing. Under `set -o pipefail`,
# `kubectl logs | grep -q` fails *because it matched*: grep exits at the first hit, kubectl
# takes SIGPIPE and exits 141, and pipefail reports the pipeline as failed. This check
# silently reported nothing on a cluster where the race had definitely happened -- the
# detector broke in exactly the case it existed for.
raced=0
for p in $(kubectl -n "$NS" get pods -l app.kubernetes.io/component=app -o name); do
  hits=$(kubectl -n "$NS" logs "$p" --previous 2>/dev/null | grep -c pg_extension_name_index || true)
  if [[ ${hits:-0} -gt 0 ]]; then raced=$((raced + 1)); fi
done
if [[ $raced -eq 0 ]]; then
  ok "no replica lost the CREATE EXTENSION race"
else
  bad "$raced replica(s) lost the CREATE EXTENSION race -- SchemaInitializationLock is not holding"
fi

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
