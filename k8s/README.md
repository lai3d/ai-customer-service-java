# Kubernetes manifests

Plain YAML, no Helm. Kustomize only for the two things a deployment actually changes.

| Path | Object | Notes |
| --- | --- | --- |
| `base/namespace.yaml` | Namespace `ai-customer-service` | Everything else is namespaced into it. |
| `base/configmap.yaml` | ConfigMap `ai-customer-service-config` | Non-secret env: Postgres host/port/db, graceful shutdown. |
| `base/deployment.yaml` | Deployment `ai-customer-service` | 2 replicas, non-root, read-only rootfs, startup/liveness/readiness probes. |
| `base/service.yaml` | Service `ai-customer-service` | ClusterIP on port 8080. |
| `base/kustomization.yaml` | — | Lists exactly those four. Generates and transforms nothing. |
| `overlays/example/` | — | The image reference and the Postgres coordinates, as an overlay. Copy it; do not edit the base. |
| `examples/secret.yaml` | Secret `ai-customer-service-secrets` | **Template. Placeholder values only.** Outside `base/`, so nothing that applies the base can reach it. |
| `kind/` | — | A throwaway-cluster harness that applies the base **unmodified** and asserts eleven things about the running pods. `--roles` does the same for the split. |
| `roles/` | Deployments `chat`, `knowledge`, `ticket`; Services of the same names; Job `knowledge-import`; a ConfigMap; a NetworkPolicy | The distributed topology of [ADR 001](../docs/adr/001-deployment-targets.md): the same image as three roles, the corpus imported once by a Job, internal endpoints reachable only from chat pods. Applied with `kubectl apply -k k8s/roles`; needs `INTERNAL_TOKEN` in the Secret. |
| `kind-roles/` | — | `roles/` plus the throwaway Postgres, for `kind/verify.sh --roles`. |
| `observability/` | ServiceMonitor `ai-customer-service`; PrometheusRule `customer-service`; ConfigMaps `grafana-dashboard-customer-service`, `grafana-dashboard-customer-service-roles` | For a cluster running kube-prometheus-stack. One monitor scrapes both layouts; the rule and the dashboards are the Compose ones from `observability/`, rendered by `scripts/render-k8s-observability.sh`. Needs the prometheus-operator CRDs, so the kind harness builds it and does not apply it. `kubectl apply -k k8s/observability`; see [Observability](#observability). |

**Why an overlay at all, when the previous answer was "no Kustomize".** This README used to
say: edit `deployment.yaml`'s image and `configmap.yaml`'s `POSTGRES_HOST` before applying.
An instruction to hand-edit a tracked file before deploying it is a drift generator — it
guarantees that the manifests in git are not the manifests anyone runs, and makes `git diff`
on them ambiguous between "someone changed the system" and "someone deployed it". Kustomize
is in `kubectl` already, so this costs no dependency, and the base is still four readable
YAML files that mean what they say.

The overlays deliberately stop there. Nothing here patches replica counts or resource limits
per environment; those numbers were [measured](#what-running-them-found) and an overlay that
quietly lowers them is how a verified manifest stops being the one you deploy.

There is intentionally **no Postgres manifest here**. The app keeps business data and the
pgvector embeddings in the same database, which makes it a stateful system of record, not
a cache — give it a managed instance with backups and point `POSTGRES_HOST` at it. The
`docker-compose.yml` Postgres is for local development only.

The database must have the `vector` extension available; see
`docker/postgres/init/01-extensions.sql` for exactly what the local stack creates.

## Apply

Order matters only in that the namespace has to exist first and the Secret has to exist
before the pods start (they mount it with `envFrom`, so a missing Secret leaves pods in
`CreateContainerConfigError`).

```sh
kubectl apply -f k8s/base/namespace.yaml

# Create the Secret imperatively so real values never touch a file.
kubectl -n ai-customer-service create secret generic ai-customer-service-secrets \
  --from-literal=ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
  --from-literal=POSTGRES_USER='csagent' \
  --from-literal=POSTGRES_PASSWORD="$PGPASSWORD" \
  --from-literal=ADMIN_SEED_USERNAME='root' \
  --from-literal=ADMIN_SEED_PASSWORD="$ADMIN_SEED_PASSWORD"   # the operations admin's first account; safe to keep

kubectl apply -k k8s/overlays/mine     # your copy of overlays/example
```

`kubectl apply -k k8s/base` applies the base as committed, which is what the kind harness
does and what you want if the defaults already suit you.

Neither form can reach the Secret template, and that was not always true. It used to sit
beside the other manifests, where `kubectl apply -f k8s/` swept it up and replaced working
credentials with `REPLACE_ME_*`. kubectl's only objection was a warning about a missing
annotation ending "The missing annotation will be patched automatically", which reads like
reassurance. This README warned about it in prose, one screen above the command, and the
warning did not stop anything — so the template moved out of the applied path entirely.

## Before you apply

Edit two things:

1. `deployment.yaml` → `spec.template.spec.containers[0].image` — currently
   `ghcr.io/lai3d/ai-customer-service-java:0.1.0-SNAPSHOT`. Point it at your registry
   and, in anything you care about, an immutable tag or a digest.
2. `configmap.yaml` → `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB`.

## Verify on kind, before a real cluster

```sh
k8s/kind/verify.sh          # create a throwaway cluster, deploy, assert; ~2 min warm
k8s/kind/verify.sh --down   # delete it
```

It applies the manifests in this directory **unmodified**, adds the two things they
deliberately do not ship — a Postgres (`kind/postgres.yaml`) and a Secret, created
imperatively — and then asserts eleven things: both replicas ready, nothing OOMKilled,
the Secret untouched by the directory apply, uid 10001, a read-only root filesystem, a
writable `/tmp`, ONNX Runtime's native library unpacked there, health and readiness UP
through the Service, Prometheus serving, and a bad key surfacing as `502` rather than as
a healthy pod returning errors. No API key is needed; export `ANTHROPIC_API_KEY` to check
the model call too.

This exists because these manifests were committed without ever being applied to a
cluster, and two of them were wrong — see [What running them found](#what-running-them-found).
Running the old files through this script fails with
`a container was OOMKilled -- the memory limit is too low`, which is the check working.

## What running them found

| | |
| --- | --- |
| The memory limit OOM-killed the pod during startup | The comment above it predicted this failure exactly and then set the number too low anyway. Measured: 2Gi and 2560Mi are OOMKilled; 3Gi starts at 94% of the limit; 4Gi at 70%. Peak plateaus at ~2.8 GiB, steady state is 1.65 GiB. Lowering `-XX:InitialRAMPercentage` does not help — the footprint is native, and heap in use is 0.13 GiB. |
| `kubectl apply -f k8s/` replaced a working Secret with `REPLACE_ME_*` | This README warned about it in prose one screen above the command, and the warning stopped nothing. The template is outside the applied path now — first by moving it to `examples/`, and since the Kustomize split, by not being listed in any kustomization. |
| On a cold database, one replica always loses a `CREATE EXTENSION` race and restarts | **Fixed**, twice. First by `SchemaInitializationLock`, a `BeanPostProcessor` holding a Postgres advisory lock across the two Spring AI beans that issued schema DDL, with the race reproduced deterministically in its test: an uncommitted `CREATE EXTENSION` is invisible to another session, so its `IF NOT EXISTS` finds nothing, proceeds, and collides on the catalogue's unique index. Then the schema moved into Flyway migrations (`db/migration`) with Spring AI's initialisers switched off, and Flyway's own advisory lock does the same job for every statement; the application-level lock and its test were removed. Was: |
| ~~On a cold database, one replica always loses a `CREATE EXTENSION` race~~ | **The original finding, kept because it is the reason the lock exists.** `CREATE EXTENSION IF NOT EXISTS vector` is not concurrency-safe in Postgres: two replicas starting together, and one gets `duplicate key value violates unique constraint "pg_extension_name_index"`, fails its Spring context, and is restarted. It recovers on the retry because the extension now exists, so the cost is a slower first rollout and a `CrashLoopBackOff`-shaped event every time a fresh database is deployed against. Reproduces on every cold-database run, exactly one replica of two. Only visible with `replicas > 1`, which is why neither the Compose stack nor the Testcontainers suite has ever seen it. |
| Fixing that race removed an accidental stagger, and a capacity problem surfaced | The crash it caused was spacing out the two replicas' 470 MB model loads. With both starting cleanly they load together, and a default single-node kind cluster — 7.75 GiB, with 2 × 4Gi limits on it, 108% of the node — OOM-kills one intermittently. Nothing wrong with the manifests: on a real cluster the replicas land on different nodes. `verify.sh` now checks node capacity up front and says so, because an OOM reported at the end reads as the manifests' fault when it is the laptop's. **A bug can be load-bearing.** |
| The requests were below the real steady state | 1Gi requested against 1.65 GiB steady, and the peak is at *startup* — so a node packed to requests does not degrade the pod, it crash-loops it. Now 3Gi. |

### What running the split found

`k8s/kind/verify.sh --roles` applies `k8s/roles` the same way. Its first run failed before
asserting anything and the next two failed for reasons worth keeping:

| | |
| --- | --- |
| The roles layout does not fit this laptop's node, and the Job is what starves | Two knowledge replicas request 3Gi each and the import Job 3Gi, on a 7.9 GiB node that also holds chat, ticket and Postgres. The second knowledge pod and the Job sit `Pending` with `Insufficient memory` forever, and without the Job no knowledge pod is ever ready, so nothing else is either. The script now applies Postgres and the Job first and the Deployments after the Job completes -- order only, the manifests are unchanged -- and `--fit` scales knowledge to one replica *after* applying the committed manifests, printing a `FIT` line and reporting that replica count as scaled. On a real cluster none of this applies. |
| Each role's real footprint, from the cgroup | `memory.peak` inside the containers, at startup with no traffic: **knowledge 2848--2911 MiB**, chat **432--494 MiB**, ticket **333--372 MiB**. Knowledge's peak is the single-process pod's 2.8 GiB peak, which says the ONNX session was the whole footprint all along; the other two roles are ordinary Spring MVC processes. The requests and limits in `roles/*.yaml` are these numbers with room, not guesses. |
| A negative assertion passed against a pod that did not exist | "a chat pod did not unpack ONNX Runtime" was green while no chat pod was ready: `! kubectl exec '' ...` fails, and the negation made that a pass. Every negative exec assertion is now guarded by the pod's existence. The same shape as the fixture-shaped tests the cross-review kept finding, in a shell script. |
| A fresh cluster pulls `pgvector/pgvector:pg17` before anything else can happen | 3m45s on this machine's network, against a 180s rollout timeout, so the first roles run died at Postgres. 480s now, for both layouts. |
| `python3 - <<PY` inside a pipeline hands python the pipe as its script | The capacity check summed limits over the rendered manifests and read them from stdin -- which is where the heredoc had just put the script. The script lives in a variable now and runs with `-c`. `bash -n` caught neither this nor the quoting error before it. |

### Which of these assertions has ever been seen to fail

An assertion nobody has watched go red is a claim. Nine of the twelve have failed in front of
me: `readyReplicas`, `OOMKilled`, uid, the read-only root filesystem, `/tmp`, ONNX, health,
readiness and metrics all went red on a run with the original 2Gi limit. Two have not, and are
listed here rather than counted as evidence:

- **the Secret untouched by a directory apply** — passes, and has never been observed failing
  since the template moved to `examples/`.
- **no replica lost the `CREATE EXTENSION` race** — passes under Flyway, with a caveat about
  what was watched: in the runs recorded here the database was cold only when the import Job
  migrated it alone, so two replicas racing Flyway itself on a cold database has not been
  observed on this cluster. Forcing it red needs
  two replicas starting together against a cold database with `spring.flyway.enabled=false`
  and Spring AI's initialisers turned back on, and this machine's single 7.75 GiB kind node
  cannot hold that reliably: the attempt thrashed the API server into TLS timeouts. The race
  itself was reproduced deterministically in the now-removed `SchemaInitializationLockTest`,
  which is better evidence than a flaky cluster run would have been — but it is not the same
  as having watched *this* check fail, and it is not written down as if it were.

Also worth knowing, from watching the OOM run: **`both replicas ready` stayed green while a
container was being OOM-killed and restarted**. The two checks look redundant and are not — a
pod can OOM its way to Ready.

Everything else the manifests asserted turned out to be true on a real cluster: uid 10001,
the read-only root filesystem, `/tmp` being exec-capable enough for ONNX Runtime to
`System.load()` out of (`~14 MB` used, exactly as the comment claimed), the ConfigMap's
`POSTGRES_HOST` wiring, and `502` for a bad credential.

## Verify by hand

```sh
kubectl -n ai-customer-service rollout status deploy/ai-customer-service
kubectl -n ai-customer-service get pods
kubectl -n ai-customer-service logs -l app.kubernetes.io/name=ai-customer-service-java --tail=100

kubectl -n ai-customer-service port-forward svc/ai-customer-service 8080:8080
curl -s localhost:8080/actuator/health | jq .
curl -s localhost:8080/actuator/health/readiness
```

A healthy first start logs `Ingested 36 FAQ documents` (18 entries, two languages) and a
restart against the same database logs `already imported; skipping`. If a pod sits in
`CrashLoopBackOff`, the usual cause is Postgres being unreachable or missing the `vector`
extension.

A **missing** `ANTHROPIC_API_KEY` now crash-loops the pod: `ChatProviderCredentialsValidator`
refuses to start on a blank or unresolved key. It did not always — Spring's
configuration-property binder ignores an unresolvable placeholder, so the property used to bind
to the literal `${ANTHROPIC_API_KEY}` and the pod went green with no working key.

A **wrong** key still shows up nowhere: health checks never call Anthropic, so the first chat
request is what returns 401. Smoke-test the actual chat endpoint after a deploy, not just
`/actuator/health`.

## Observability

`kubectl apply -k k8s/observability` gives a cluster running
[kube-prometheus-stack](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack)
the monitoring Compose has: a `ServiceMonitor` that scrapes `/actuator/prometheus` every 15 s
from every Service carrying `app.kubernetes.io/name: ai-customer-service-java` -- the base's
one or the roles' three, one object for both layouts -- and copies each Service's component
label into the `role` label the dashboards split by; a `PrometheusRule` with the eight alerts
from `observability/prometheus/rules/`; and one ConfigMap per dashboard, labelled
`grafana_dashboard: "1"` for the chart's Grafana sidecar. It needs the `monitoring.coreos.com`
CRDs, which is why `kind/verify.sh` only builds it -- an apply on the throwaway cluster would
fail on the missing CRDs before saying anything about the objects -- and why neither layout's
kustomization includes it. Apply it after the application, into the same namespace.

Two things about the chart's defaults. Its Prometheus picks up only the `ServiceMonitor`s and
`PrometheusRule`s labelled with the Helm release name unless
`prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues` and
`ruleSelectorNilUsesHelmValues` are set to `false`; the objects here carry no release label,
because the release name is yours. And the roles layout's NetworkPolicy admits connections to
knowledge and ticket pods on port 8080 from chat pods, the import Job, and Prometheus pods in
the `monitoring` namespace -- the metrics endpoint is on that port. The first version of the
policy did not admit Prometheus at all; the overlay's author noticed that on an enforcing CNI
both roles would be unscrapeable and `TargetDown` would fire for them, the policy doing its job
against the wrong caller. If your Prometheus runs elsewhere or is labelled differently, patch
that rule in your overlay before trusting the alert.

The rule and the dashboards are copies of the files under `observability/`, not references
to them: Kustomize refuses any file outside the kustomization's own directory, which this
repository found out the hard way. `scripts/render-k8s-observability.sh` regenerates the three
files from the sources -- sed only: the rules file indented under `spec:` is the
PrometheusRule's spec, comments included, and a dashboard under a block scalar is a
ConfigMap's data -- and `ObservabilityManifestsTest` fails whenever a copy differs from its
source, so a threshold changed on one side and not the other is a red build rather than a
quiet difference between Compose and the cluster. Edit under `observability/`, run the
script, commit both.

## Dry-run validation

```sh
kubectl apply --dry-run=client -o yaml \
  -k k8s/base
```

Client dry-run only checks schema and structure. `--dry-run=server` additionally runs
admission and validates against the target cluster's API versions; use it when you have
a cluster.

## Deliberately not included

- **Ingress / Gateway.** The app has no authentication. Exposing it needs a decision
  about who is in front of it, which belongs with whoever owns the edge.
- **HorizontalPodAutoscaler.** The useful signal here is in-flight LLM calls, not CPU;
  a CPU-based HPA on a workload that spends its life blocked on Anthropic's API would
  scale on the wrong thing. `/actuator/prometheus` is exposed for a KEDA/Prometheus HPA
  once someone picks the metric.
- **PodDisruptionBudget.** Worth adding (`minAvailable: 1`) as soon as this runs on a
  cluster with real node churn.
- **NetworkPolicy.** Depends entirely on the CNI and the cluster's existing conventions.
