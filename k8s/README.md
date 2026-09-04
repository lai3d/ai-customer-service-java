# Kubernetes manifests

Plain YAML, no Helm, no Kustomize overlay. Four objects plus a namespace:

| File | Object | Notes |
| --- | --- | --- |
| `namespace.yaml` | Namespace `ai-customer-service` | Everything else is namespaced into it. |
| `configmap.yaml` | ConfigMap `ai-customer-service-config` | Non-secret env: Postgres host/port/db, graceful shutdown. |
| `secret.example.yaml` | Secret `ai-customer-service-secrets` | **Template. Placeholder values only.** Do not apply as-is. |
| `deployment.yaml` | Deployment `ai-customer-service` | 2 replicas, non-root, read-only rootfs, startup/liveness/readiness probes. |
| `service.yaml` | Service `ai-customer-service` | ClusterIP on port 8080. |

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
kubectl apply -f k8s/namespace.yaml

# Create the Secret imperatively so real values never touch a file.
kubectl -n ai-customer-service create secret generic ai-customer-service-secrets \
  --from-literal=ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
  --from-literal=POSTGRES_USER='csagent' \
  --from-literal=POSTGRES_PASSWORD="$PGPASSWORD"

kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

Or, once the Secret exists, everything else at once:

```sh
kubectl apply -f k8s/namespace.yaml -f k8s/configmap.yaml -f k8s/deployment.yaml -f k8s/service.yaml
```

Note that `kubectl apply -f k8s/` would also try to apply `secret.example.yaml` and
install placeholder credentials. Name the files explicitly.

## Before you apply

Edit two things:

1. `deployment.yaml` → `spec.template.spec.containers[0].image` — currently
   `ghcr.io/lai3d/ai-customer-service-java:0.1.0-SNAPSHOT`. Point it at your registry
   and, in anything you care about, an immutable tag or a digest.
2. `configmap.yaml` → `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB`.

## Verify

```sh
kubectl -n ai-customer-service rollout status deploy/ai-customer-service
kubectl -n ai-customer-service get pods
kubectl -n ai-customer-service logs -l app.kubernetes.io/name=ai-customer-service-java --tail=100

kubectl -n ai-customer-service port-forward svc/ai-customer-service 8080:8080
curl -s localhost:8080/actuator/health | jq .
curl -s localhost:8080/actuator/health/readiness
```

A healthy first start logs `Ingested 18 FAQ documents`. If a pod sits in
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
  -f k8s/namespace.yaml -f k8s/configmap.yaml -f k8s/deployment.yaml -f k8s/service.yaml
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
