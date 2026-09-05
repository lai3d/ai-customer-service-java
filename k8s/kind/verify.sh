#!/usr/bin/env bash
# Verify the Kubernetes manifests on a throwaway kind cluster.
#
#   k8s/kind/verify.sh            create the cluster, deploy the base, assert, leave it running
#   k8s/kind/verify.sh --roles    the same for k8s/roles: three Deployments and the import Job
#   k8s/kind/verify.sh --fit      with --roles: scale knowledge to one replica so the layout fits
#                                 a laptop-sized node, and say so on every line it affects
#   k8s/kind/verify.sh --down     delete the cluster and exit
#   k8s/kind/verify.sh --keep     skip the image rebuild if the tag is already present
#
# `--roles`, `--fit` and `--keep` combine. The two layouts share the namespace and the Secret and can
# be applied to one cluster in turn; each run deletes the other layout's workloads first so
# the capacity check and the assertions are about one of them.
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
# The CREATE EXTENSION assertion below is about Flyway's advisory lock now (db/migration);
# the application-level lock it used to be about is gone. To see it go red, run two replicas
# against a cold database with `spring.flyway.enabled=false` and Spring AI's schema
# initialisers turned back on -- which is the configuration this repository shipped before
# the race was found.
set -euo pipefail

LAYOUT=base; FIT=0
for arg in "$@"; do
  [[ $arg == --roles ]] && LAYOUT=roles
  [[ $arg == --fit ]] && FIT=1
done

CLUSTER=${CLUSTER:-ai-cs}
NS=ai-customer-service
IMAGE=$(grep -m1 'image: ghcr.io' "$(dirname "$0")/../base/deployment.yaml" | awk '{print $2}')
# What this run applies and asserts about. The base is one Deployment; the roles layout is
# three plus a Job, and the pod that gets the exec-level assertions is a chat pod.
if [[ $LAYOUT == roles ]]; then
  OVERLAY=kind-roles; SOURCE=roles
  DEPLOYMENTS="knowledge ticket chat"; APP_COMPONENT=chat; PUBLIC_SVC=chat
  # The one deliberate departure from "unmodified", and it is not quiet: --fit scales the
  # knowledge Deployment to one replica *after* applying the real manifests, because the split
  # at its declared replicas needs 2 x 3Gi for knowledge plus 3Gi for the import Job, and a
  # 7.9 GiB kind node cannot hold that -- the second knowledge pod and the Job sit Pending
  # forever, each with "Insufficient memory". Everything else is applied as committed, the
  # scaled line is reported as scaled, and the capacity NOTE above still prints.
  KNOWLEDGE_REPLICAS=2; [[ $FIT -eq 1 ]] && KNOWLEDGE_REPLICAS=1
else
  OVERLAY=kind; SOURCE=base
  DEPLOYMENTS="ai-customer-service"; APP_COMPONENT=app; PUBLIC_SVC=ai-customer-service
fi
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
# This script never opens the kubeconfig you use for real clusters. `kind create` writes to
# its own file here, and every kubectl call reads that file, so there is no current context to
# switch and nothing to restore.
#
# That is the third design for this, and the first two were both fixed and both wrong. The
# original ran `kubectl config use-context`, which edits the shared kubeconfig -- a parallel
# session found its namespace apparently empty because this script had moved its context.
# Save-and-restore replaced it, and then a second `trap ... EXIT` further down silently
# replaced that handler, so the restore never ran once.
#
# The reason not to try a fourth save-and-restore is what is actually in this machine's
# kubeconfig: `ind91-prod` and `central-platform`, next to the kind clusters. A test harness
# that leaves the current context somewhere other than it found it is choosing where the next
# `kubectl delete` lands. Not touching the file removes the whole class -- there is no restore
# to get wrong, and no trap to disable.
KUBECONFIG_FILE=${KUBECONFIG_FILE:-${TMPDIR:-/tmp}}
KUBECONFIG_FILE=${KUBECONFIG_FILE%/}/ai-customer-service-kind.kubeconfig
# Exported, not just wrapped in a shell function. A function is not inherited by `sh -c`,
# and three assertions here run their kubectl inside one -- so with only the function they
# silently read the *default* kubeconfig, found no such context, and failed while the
# deployment was perfectly healthy. Introduced by the fix on the line above it, and it looked
# exactly like the pod-selection bug I had just been told about, which is what I spent a
# rerun confirming it was not.
export KUBECONFIG="$KUBECONFIG_FILE"
kubectl() { command kubectl --context "kind-$CLUSTER" "$@"; }
PF=""
trap '[[ -n $PF ]] && kill "$PF" 2>/dev/null; true' EXIT

if [[ ${1:-} == --down ]]; then
  kind delete cluster --name "$CLUSTER" --kubeconfig "$KUBECONFIG_FILE"
  rm -f "$KUBECONFIG_FILE"
  exit 0
fi

for t in kind kubectl docker; do
  command -v "$t" >/dev/null || { echo "missing: $t" >&2; exit 1; }
done

say "cluster"
kind get clusters 2>/dev/null | grep -qx "$CLUSTER" ||
  kind create cluster --name "$CLUSTER" --wait 120s --kubeconfig "$KUBECONFIG_FILE"
# An existing cluster from an earlier run has no entry in this file yet.
kubectl cluster-info >/dev/null 2>&1 ||
  kind export kubeconfig --name "$CLUSTER" --kubeconfig "$KUBECONFIG_FILE" >/dev/null


say "image  $IMAGE"
if [[ " $* " == *" --keep "* ]] && docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "  reusing the local image"
else
  docker build -t "$IMAGE" "$ROOT"
fi
kind load docker-image "$IMAGE" --name "$CLUSTER"
UI_IMAGE=$(grep -m1 'image: ghcr.io' "$(dirname "$0")/../base/admin-ui.yaml" | awk '{print $2}')
say "ui     $UI_IMAGE"
if [[ " $* " == *" --keep "* ]] && docker image inspect "$UI_IMAGE" >/dev/null 2>&1; then
  echo "  reusing the local image"
else
  docker build -t "$UI_IMAGE" "$ROOT/admin-ui"
fi
kind load docker-image "$UI_IMAGE" --name "$CLUSTER"

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
# Allocatable minus what everything else has already reserved. Comparing against
# allocatable alone passes on a node that is already full and keeps passing right up until a
# pod goes Pending -- which is the failure this check exists to pre-empt. Found by the
# parallel session running its copy while a stray namespace of mine sat on its node: 81% of
# requests taken, and its brand-new precheck said fine.
node_ki=$(kubectl get node "${CLUSTER}-control-plane" -o jsonpath='{.status.allocatable.memory}' | sed 's/Ki$//')
reserved_mib=$(kubectl get pods -A \
  -o jsonpath="{range .items[?(@.metadata.namespace!='$NS')]}{range .spec.containers[*]}{.resources.requests.memory}{'\n'}{end}{end}" 2>/dev/null \
  | python3 -c "
import re, sys
total = 0
for line in sys.stdin:
    m = re.match(r'(\d+)(Gi|Mi|Ki|G|M|K)?\s*\$', line.strip())
    if m:
        total += int(m.group(1)) * {'Gi':1024,'Mi':1,'Ki':1/1024,'G':954,'M':1,'K':1/1024,None:1/1048576}[m.group(2)]
print(int(total))" 2>/dev/null || echo 0)
# Summed over every Deployment the layout applies: replicas x limit, read from the rendered
# manifests rather than from a number typed here.
# The script comes from a quoted heredoc into a variable, not `python3 -c "..."` (bash tried
# to parse the regexes' quotes and parentheses inside the command substitution, and `bash -n`
# did not catch it) and not `python3 - <<PY` (that hands python the heredoc as stdin, and the
# rendered manifests piped into it became the "script").
CAPACITY_PY=$(cat <<'PY'
import re, sys

def mib(value):
    m = re.match(r'(\d+)(Gi|Mi|G|M)?$', value)
    return int(m.group(1)) * {'Gi': 1024, 'Mi': 1, 'G': 954, 'M': 1}[m.group(2) or 'Mi']

total = 0
for doc in sys.stdin.read().split('\n---\n'):
    if not re.search(r'^kind: Deployment$', doc, re.M):
        continue
    replicas = re.search(r'^  replicas: (\d+)', doc, re.M)
    limits = doc[doc.find('limits:'):]
    limit = re.search(r'^\s+memory: "?([0-9]+[A-Za-z]*)"?\s*$', limits, re.M)
    total += int(replicas.group(1)) * mib(limit.group(1))
print(total + 1024)
PY
)
want_mib=$(command kubectl kustomize "$ROOT/k8s/$SOURCE" | python3 -c "$CAPACITY_PY")
if [[ ! $node_ki =~ ^[0-9]+$ || ! $want_mib =~ ^[0-9]+$ ]]; then
  echo "  could not read node capacity or the deployments' limits (node=$node_ki want=$want_mib)" >&2
  exit 1
fi
node_mib=$((node_ki / 1024))
free_mib=$((node_mib - ${reserved_mib:-0}))
printf '  node allocatable %s MiB, %s MiB reserved by other namespaces, %s MiB free\n' \
       "$node_mib" "${reserved_mib:-0}" "$free_mib"
printf '  %s MiB wanted for the %s layout at its limits, plus Postgres\n' "$want_mib" "$LAYOUT"
if [[ $free_mib -lt $want_mib ]]; then
  printf '  \033[33mNOTE\033[0m this node cannot hold every replica at its limit. Any OOMKill\n'
  printf '       below is this machine, not the manifests -- give Docker more memory, or\n'
  printf '       scale down to check everything else.\n'
fi

# The property the kind overlay exists to have: it adds Postgres and changes nothing else.
# An overlay that quietly lowered the replica count or the memory limit to fit a laptop would
# make every assertion below true of a manifest nobody deploys.
say "overlay"
command kubectl kustomize "$ROOT/k8s/$SOURCE" > /tmp/verify-base.yaml
command kubectl kustomize "$ROOT/k8s/$OVERLAY" > /tmp/verify-kind.yaml
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
then ok "the $OVERLAY overlay leaves $SOURCE unmodified"
else bad "the $OVERLAY overlay modifies $SOURCE -- the assertions below would not be about the real manifests"; fi

# An example that stopped building would be worse than no example, and each layout's other
# half has to keep building too.
check "the example overlay builds"  command kubectl kustomize "$ROOT/k8s/overlays/example"
check "the base layout builds"      command kubectl kustomize "$ROOT/k8s/base"
check "the roles layout builds"     command kubectl kustomize "$ROOT/k8s/roles"
check "the observability overlay builds" command kubectl kustomize "$ROOT/k8s/observability"

say "deploy"
kubectl apply -f "$ROOT/k8s/base/namespace.yaml"

kubectl -n "$NS" create secret generic ai-customer-service-secrets \
  --from-literal=ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-placeholder-no-model-call-is-made-during-startup}" \
  --from-literal=POSTGRES_USER=csagent \
  --from-literal=POSTGRES_PASSWORD=csagent \
  --from-literal=INTERNAL_TOKEN="${INTERNAL_TOKEN:-$(openssl rand -hex 16)}" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# The overlay, which is what the README tells people to apply. The Secret template is not
# reachable from it -- it was reachable from `kubectl apply -f k8s/`, and overwrote working
# credentials with placeholders.
# The other layout's workloads, if a previous run left them: one layout at a time, so the
# assertions and the capacity check are about this one. The namespace and the Secret stay.
if [[ $LAYOUT == roles ]]; then
  kubectl -n "$NS" delete deploy,svc -l app.kubernetes.io/component=app --ignore-not-found >/dev/null
else
  kubectl -n "$NS" delete deploy,svc,job,networkpolicy \
    -l 'app.kubernetes.io/component in (chat,knowledge,ticket,knowledge-import)' --ignore-not-found >/dev/null
  kubectl -n "$NS" delete networkpolicy internal-endpoints-from-chat-only --ignore-not-found >/dev/null
fi
if [[ $LAYOUT == roles ]]; then
  # A clean slate for this layout on a re-run: the Job is immutable once applied, and a
  # previous run's Deployments would hold the memory the Job needs to schedule on a small
  # node. The assertions are about a fresh apply of the committed manifests either way.
  kubectl -n "$NS" delete job knowledge-import --ignore-not-found >/dev/null
  kubectl -n "$NS" delete deploy chat knowledge ticket --ignore-not-found >/dev/null
  kubectl -n "$NS" wait --for=delete pod -l 'app.kubernetes.io/component in (chat,knowledge,ticket,knowledge-import)' --timeout=120s >/dev/null 2>&1 || true
  # Order, not content: Postgres and the import Job first, the Deployments once the Job is
  # done. On a real cluster they co-schedule and the knowledge pods simply wait not-ready for
  # the Job; on one small node the Job's 3Gi request and the Deployments' compete, and the
  # Job -- which frees its memory in a minute -- is the one that should win. The manifests
  # applied are the same either way; only this script's `kubectl apply` calls are sequenced.
  command kubectl kustomize "$ROOT/k8s/$OVERLAY" | python3 -c "
import sys
docs = sys.stdin.read().split('\n---\n')
print('\n---\n'.join(d for d in docs if 'kind: Deployment' not in d or 'name: postgres' in d))" \
    | kubectl apply -f - >/dev/null
  # 480s, not 180: a fresh cluster pulls pgvector/pgvector:pg17 first, which took 3m45s on
  # this machine's network and timed the first roles run out before anything was asserted.
  kubectl -n "$NS" rollout status deploy/postgres --timeout=480s
  kubectl -n "$NS" wait --for=condition=complete job/knowledge-import --timeout=480s || true
  kubectl apply -k "$ROOT/k8s/$OVERLAY" >/dev/null
  if [[ $FIT -eq 1 ]]; then
    kubectl -n "$NS" scale deploy/knowledge --replicas=1 >/dev/null
    printf '  \033[33mFIT\033[0m knowledge scaled to 1 replica for this node; k8s/roles says 2\n'
  fi
else
  kubectl apply -k "$ROOT/k8s/$OVERLAY"
  kubectl -n "$NS" rollout status deploy/postgres --timeout=480s
fi
# Deliberately not fatal. A failed rollout is a result, not a reason to stop: the
# assertions below are what say *why* it failed, and "OOMKilled -- the memory limit is
# too low" is a better last line than a rollout timeout.
for d in $DEPLOYMENTS; do
  kubectl -n "$NS" rollout status "deploy/$d" --timeout=300s || true
done

say "assertions"
# Ready and not being deleted. `phase == "Running"` is true of a pod that is shutting down,
# and exec into it fails with "cannot exec into a container in a completed pod" -- which
# shows up as three unrelated assertions failing on a rerun against an existing cluster,
# while the deployment itself is perfectly healthy. The parallel session hit this first and
# told me; I did not apply it here until it cost me the same three assertions.
#
# jsonpath cannot express "field is absent", so the deletionTimestamp filter is done in the
# shell after the query rather than inside it.
pick_pod() {
  kubectl -n "$NS" get pods -l "app.kubernetes.io/component=${1:-$APP_COMPONENT}" -o json |
    python3 -c "
import json, sys
for p in json.load(sys.stdin)['items']:
    if p['metadata'].get('deletionTimestamp'):
        continue
    ready = any(c['type'] == 'Ready' and c['status'] == 'True'
                for c in p['status'].get('conditions', []))
    if ready:
        print(p['metadata']['name'])
        break
"
}
POD=$(pick_pod)

for d in $DEPLOYMENTS; do
  want=2; [[ $d == knowledge ]] && want=$KNOWLEDGE_REPLICAS
  replicas=$(kubectl -n "$NS" get deploy "$d" -o jsonpath='{.status.readyReplicas}')
  label="$d: all $want replica(s) ready"; [[ $d == knowledge && $FIT -eq 1 ]] && label="$d: 1 replica ready (scaled by --fit; the manifest says 2)"
  [[ ${replicas:-0} == $want ]] && ok "$label" || bad "$d: readyReplicas=${replicas:-0}, want $want"
done
if [[ $LAYOUT == roles ]]; then
  succeeded=$(kubectl -n "$NS" get job knowledge-import -o jsonpath='{.status.succeeded}')
  [[ ${succeeded:-0} == 1 ]] && ok "the import Job completed" || bad "the import Job did not complete (succeeded=${succeeded:-0})"
fi

# The bug this script exists for. A too-small limit does not fail the rollout on a fast
# machine every time -- it fails during the ONNX session, which is late enough to look
# like a probe problem.
if kubectl -n "$NS" get pods -l app.kubernetes.io/name=ai-customer-service-java -o json |
     grep -q OOMKilled; then bad "a container was OOMKilled -- the memory limit is too low"
else ok "no container was OOMKilled"; fi

# The second bug this script exists for. The Secret is created before the directory apply
# above; if a template with placeholders is ever reachable from `kubectl apply -f k8s/`
# again, this is the credential it will be holding afterwards.
if kubectl -n "$NS" get secret ai-customer-service-secrets \
     -o jsonpath='{.data.ANTHROPIC_API_KEY}' | base64 -d | grep -q REPLACE_ME; then
  bad "the directory apply overwrote the Secret with placeholders"
else ok "the directory apply left the Secret alone"; fi

# This was a KNOWN line reporting an unfixed defect until the schema creation was
# serialised -- first by an application-level advisory lock, now by Flyway's own, with Spring
# AI's initialisers switched off (db/migration/V1). It is an assertion because the race is
# supposed to be gone, and this is the only place two replicas start against a cold database.
#
# `grep -c`, not `grep -q`, and the `|| true` is load-bearing. Under `set -o pipefail`,
# `kubectl logs | grep -q` fails *because it matched*: grep exits at the first hit, kubectl
# takes SIGPIPE and exits 141, and pipefail reports the pipeline as failed. This check
# silently reported nothing on a cluster where the race had definitely happened -- the
# detector broke in exactly the case it existed for.
raced=0
for p in $(kubectl -n "$NS" get pods -l app.kubernetes.io/name=ai-customer-service-java -o name); do
  hits=$(kubectl -n "$NS" logs "$p" --previous 2>/dev/null | grep -c pg_extension_name_index || true)
  if [[ ${hits:-0} -gt 0 ]]; then raced=$((raced + 1)); fi
done
if [[ $raced -eq 0 ]]; then
  ok "no replica lost the CREATE EXTENSION race"
else
  bad "$raced replica(s) lost the CREATE EXTENSION race -- Flyway's lock is not holding, or an initialiser is back on"
fi

check "runs as uid 10001"        sh -c "kubectl -n $NS exec $POD -- id -u | grep -qx 10001"
check "root filesystem read-only" sh -c "kubectl -n $NS exec $POD -- sh -c 'touch /nope' 2>&1 | grep -q 'Read-only'"
check "/tmp is writable"          kubectl -n "$NS" exec "$POD" -- sh -c 'touch /tmp/.probe && rm /tmp/.probe'
# ONNX Runtime System.load()s its .so out of this emptyDir; a noexec mount fails startup
# with UnsatisfiedLinkError. The unpacked directory is the evidence it worked.
if [[ $LAYOUT == roles ]]; then
  # The claim the split makes about memory: only the knowledge role loads the model. A chat
  # pod and a ticket pod that had unpacked ONNX Runtime would be carrying it for nothing.
  KPOD=$(pick_pod knowledge); TPOD=$(pick_pod ticket)
  # A negative assertion against a pod that does not exist passes for the wrong reason; the
  # first version of this reported "a chat pod did not [unpack ONNX]" while no chat pod was
  # ready at all. Each negative is guarded by the pod's existence.
  check "a knowledge pod unpacked ONNX Runtime into /tmp" \
        sh -c "[ -n '$KPOD' ] && kubectl -n $NS exec $KPOD -- ls /tmp | grep -q onnxruntime-java"
  check "a chat pod did not" \
        sh -c "[ -n '$POD' ] && ! kubectl -n $NS exec $POD -- ls /tmp | grep -q onnxruntime-java"
  check "a ticket pod did not" \
        sh -c "[ -n '$TPOD' ] && ! kubectl -n $NS exec $TPOD -- ls /tmp | grep -q onnxruntime-java"
  # Peak memory per role, from the cgroup, so the limits in k8s/roles are measured numbers.
  for pod in "$POD" "$KPOD" "$TPOD"; do
    [[ -z $pod ]] && continue
    peak=$(kubectl -n "$NS" exec "$pod" -- cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo 0)
    printf '  peak RSS %-45s %5d MiB\n' "$pod" "$((peak / 1048576))"
  done
  # The token, through the ticket Service.
  kubectl -n "$NS" port-forward svc/ticket 18081:8080 >/dev/null 2>&1 &
  TPF=$!; sleep 3
  tstatus=$(curl -s -o /dev/null -w '%{http_code}' 'localhost:18081/internal/v1/tickets?conversationId=x' || echo 000)
  [[ $tstatus == 401 ]] && ok "an internal call without the token is 401" || bad "an internal call without the token returned $tstatus, want 401"
  kill "$TPF" 2>/dev/null || true
else
  check "ONNX unpacked its native library into /tmp" \
        sh -c "kubectl -n $NS exec $POD -- ls /tmp | grep -q onnxruntime-java"
fi

kubectl -n "$NS" port-forward "svc/$PUBLIC_SVC" 18080:8080 >/dev/null 2>&1 &
PF=$!
sleep 4

check "health is UP through the Service" \
      sh -c "curl -sf localhost:18080/actuator/health | grep -q '\"status\":\"UP\"'"
check "readiness is UP through the Service" \
      sh -c "curl -sf localhost:18080/actuator/health/readiness | grep -q UP"
if [[ $LAYOUT == roles ]]; then
  check "chat readiness names the knowledge indicator" \
        sh -c "curl -sf localhost:18080/actuator/health/readiness | grep -q '\"knowledge\"'"
fi
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

# The operations UI, through its own Service: the bundle is served, and /admin/api is
# proxied to the app, which wants a login -- a 401 from nginx's upstream, not a 404 or a 502.
kubectl -n "$NS" port-forward svc/admin-ui 18084:8084 >/dev/null 2>&1 &
UPF=$!; sleep 3
check "the operations UI serves its page" \
      sh -c "curl -sf localhost:18084/ | grep -q '<div id=\"root\">'"
ustatus=$(curl -s -o /dev/null -w '%{http_code}' localhost:18084/admin/api/me || echo 000)
[[ $ustatus == 401 ]] && ok "the UI proxies /admin/api to the app (401 without a login)" \
                      || bad "the UI's proxy to /admin/api returned $ustatus, want 401"
kill "$UPF" 2>/dev/null || true

say "result"
printf '  %d passed, %d failed\n' "$PASS" "$FAIL"
printf '  cluster left running; %s --down to remove it\n' "$0"
printf '  its kubeconfig is %s -- your own is untouched\n' "$KUBECONFIG_FILE"
[[ $FAIL -eq 0 ]]
