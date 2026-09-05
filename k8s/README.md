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
| `kind/` | — | A throwaway-cluster harness that applies the base **unmodified** and asserts eleven things about the running pods. |

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
  --from-literal=POSTGRES_PASSWORD="$PGPASSWORD"

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
| On a cold database, one replica always loses a `CREATE EXTENSION` race and restarts | **Fixed** by `SchemaInitializationLock`, a `BeanPostProcessor` holding a Postgres advisory lock across the two beans that issue schema DDL. The race is real and is now reproduced deterministically in `SchemaInitializationLockTest` rather than by hoping threads collide: an uncommitted `CREATE EXTENSION` is invisible to another session, so its `IF NOT EXISTS` finds nothing, proceeds, and collides on the catalogue's unique index. Measured cost of the lock: one replica waited 102 ms. Was: |
| ~~On a cold database, one replica always loses a `CREATE EXTENSION` race~~ | **The original finding, kept because it is the reason the lock exists.** `CREATE EXTENSION IF NOT EXISTS vector` is not concurrency-safe in Postgres: two replicas starting together, and one gets `duplicate key value violates unique constraint "pg_extension_name_index"`, fails its Spring context, and is restarted. It recovers on the retry because the extension now exists, so the cost is a slower first rollout and a `CrashLoopBackOff`-shaped event every time a fresh database is deployed against. Reproduces on every cold-database run, exactly one replica of two. Only visible with `replicas > 1`, which is why neither the Compose stack nor the Testcontainers suite has ever seen it. |
| Fixing that race removed an accidental stagger, and a capacity problem surfaced | The crash it caused was spacing out the two replicas' 470 MB model loads. With both starting cleanly they load together, and a default single-node kind cluster — 7.75 GiB, with 2 × 4Gi limits on it, 108% of the node — OOM-kills one intermittently. Nothing wrong with the manifests: on a real cluster the replicas land on different nodes. `verify.sh` now checks node capacity up front and says so, because an OOM reported at the end reads as the manifests' fault when it is the laptop's. **A bug can be load-bearing.** |
| The requests were below the real steady state | 1Gi requested against 1.65 GiB steady, and the peak is at *startup* — so a node packed to requests does not degrade the pod, it crash-loops it. Now 3Gi. |

### Which of these assertions has ever been seen to fail

An assertion nobody has watched go red is a claim. Nine of the twelve have failed in front of
me: `readyReplicas`, `OOMKilled`, uid, the read-only root filesystem, `/tmp`, ONNX, health,
readiness and metrics all went red on a run with the original 2Gi limit. Two have not, and are
listed here rather than counted as evidence:

- **the Secret untouched by a directory apply** — passes, and has never been observed failing
  since the template moved to `examples/`.
- **no replica lost the `CREATE EXTENSION` race** — passes with the lock. Forcing it red needs
  two replicas starting together against a cold database with
  `app.schema.serialize-initialization=false`, and this machine's single 7.75 GiB kind node
  cannot hold that reliably: the attempt thrashed the API server into TLS timeouts. The
  mechanism is proven deterministically in `SchemaInitializationLockTest` instead, which is
  better evidence than a flaky cluster run would have been — but it is not the same as having
  watched *this* check fail, and it is not written down as if it were.

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
