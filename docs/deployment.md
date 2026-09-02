# Deployment

How to run this service in a container, locally with Docker Compose and on Kubernetes.

Everything in this document was executed on an arm64 macOS host against Docker 29.7.2;
the measured numbers below are from that run, not estimates.

---

## Contents

- [Environment variables](#environment-variables)
- [Docker Compose: the one-command stack](#docker-compose-the-one-command-stack)
- [Building the image](#building-the-image)
- [What is in the image, and why it is big](#what-is-in-the-image-and-why-it-is-big)
- [The ONNX / DJL model tradeoff](#the-onnx--djl-model-tradeoff)
- [Kubernetes](#kubernetes)
- [Things that will bite you](#things-that-will-bite-you)

---

## Environment variables

| Variable | Required | Default | Notes |
| --- | --- | --- | --- |
| `ANTHROPIC_API_KEY` | yes | none | No default in `application.yml`, on purpose. See the warning below — a missing key does **not** stop the app from starting. |
| `POSTGRES_HOST` | no | `localhost` | Set to `postgres` inside Compose; to your database's service name or endpoint in Kubernetes. |
| `POSTGRES_PORT` | no | `5432` | In `docker-compose.yml` this variable controls the **host** publish port only; the app container always talks to `postgres:5432` on the internal network. |
| `POSTGRES_DB` | no | `csagent` | |
| `POSTGRES_USER` | no | `csagent` | |
| `POSTGRES_PASSWORD` | no | `csagent` | Change this anywhere that is not a laptop. |
| `APP_PORT` | no | `8080` | Compose only: host port for the app. |
| `APP_IMAGE` | no | `ai-customer-service-java:local` | Compose only: lets you run a pre-built image instead of building. |

`.env.example` documents the first five. Copy it to `.env` (git-ignored) and fill it in,
or export the variables in your shell — Compose reads both.

The image additionally sets `JAVA_TOOL_OPTIONS`, `DJL_CACHE_DIR`,
`SPRING_AI_EMBEDDING_TRANSFORMER_ONNX_MODEL_URI`,
`SPRING_AI_EMBEDDING_TRANSFORMER_TOKENIZER_URI` and
`SPRING_AI_EMBEDDING_TRANSFORMER_CACHE_DIRECTORY`. Those are deployment plumbing, not
knobs — the Dockerfile explains each one at the point it is set.

> **A missing or wrong `ANTHROPIC_API_KEY` will not fail the deploy.**
> This was measured, not assumed. With the variable absent from the container entirely,
> the app starts in 1.4s, both probes pass, and `/actuator/health` returns `UP`. Spring's
> configuration-property binder ignores unresolvable placeholders, so
> `spring.ai.anthropic.api-key` binds to the literal string `${ANTHROPIC_API_KEY}` and the
> first chat request comes back 401. Health checks never call Anthropic.
>
> Practical consequence: **smoke-test `POST /api/v1/chat` after a deploy**, not just
> `/actuator/health`. A rollout can go fully green with no working API key.

---

## Docker Compose: the one-command stack

```sh
export ANTHROPIC_API_KEY=sk-ant-...      # or put it in .env
docker compose up --build
```

That builds the image, starts Postgres, waits for it to pass `pg_isready`
(`depends_on: condition: service_healthy`), then starts the app.

```sh
curl -s localhost:8080/actuator/health | jq .
curl -s localhost:8080/actuator/health/readiness
docker compose logs app | grep Ingested
docker compose ps            # the app has a real HEALTHCHECK, so this shows readiness
docker compose down
```

The key is passed through by name (`environment: - ANTHROPIC_API_KEY`), so it is never
written into a committed file and never appears in `docker compose config` output as a
literal.

### Dependencies only (IDE / `./mvnw spring-boot:run`)

Unchanged, and deliberately kept working:

```sh
docker compose up -d postgres
```

Naming the service starts nothing else. This works with no `ANTHROPIC_API_KEY` set
anywhere — which is why the compose file does *not* guard the key with
`${ANTHROPIC_API_KEY:?...}`. Compose interpolates the whole file before it looks at which
service you asked for, so that guard would break this workflow for anyone without a key.

### Port conflicts

If something already owns 5432 or 8080:

```sh
POSTGRES_PORT=55432 APP_PORT=18080 docker compose up
```

Both are host-side publish ports only. The app finds Postgres at `postgres:5432` over the
Compose network regardless.

### Measured

| | |
| --- | --- |
| `docker compose up` → `HTTP 200` on `/actuator/health` (Postgres container start + health gate + app) | **12.2 s** |
| App container recreate → ready, Postgres already warm (2 runs) | **2.6 s**, **2.6 s** |
| JVM `Started CustomerServiceApplication in` | **1.4 – 5.6 s** |
| `Ingested 18 FAQ documents` | **~250 ms** |
| Bytes downloaded at runtime | **0** |

---

## Building the image

```sh
docker build -t ai-customer-service-java:local .
```

Three stages:

1. **`build`** — JDK 21, Maven wrapper. Dependency resolution (`dependency:go-offline`)
   is a separate layer keyed only on `pom.xml`, so editing `src/` does not re-resolve.
   Produces the Spring Boot jar, explodes it with
   `java -Djarmode=tools -jar app.jar extract --layers --launcher`, and warms the DJL
   native cache (see below).
2. **`onnx`** — downloads `model.onnx` and `tokenizer.json`, with a size assertion so a
   Git-LFS pointer file (which returns HTTP 200 and is ~130 bytes) fails the build
   instead of exploding at runtime.
3. **runtime** — JRE 21, non-root uid/gid 10001, exploded jar, baked models.

Base images are pinned to `eclipse-temurin:21.0.9_10-jdk-noble` /
`...-jre-noble` and are overridable via `--build-arg JDK_IMAGE=` / `JRE_IMAGE=`.

### Is `layertools` worth it here?

Yes, and unusually clearly. The exploded layers are:

| Layer | Size |
| --- | --- |
| `dependencies` | 168 MB |
| `spring-boot-loader` | 696 kB |
| `snapshot-dependencies` | 4 kB |
| `application` | 184 kB |

Shipping the fat jar as one `COPY` would push 168 MB of unchanged bytes to the registry
on every source edit. Split, a source-only change rebuilds and pushes ~190 kB. The whole
mechanism is four `COPY` lines.

### Tests are not run during the build

`-DskipTests` is deliberate: the integration tests use Testcontainers, which needs a
Docker socket that `docker build` does not have. Run `./mvnw verify` in CI as a separate
step. The image build is not the test gate.

---

## What is in the image, and why it is big

`docker images` reports **1.29 GB**. That number needs decoding, because Docker's
containerd image store adds two different things together:

| Measure | Size |
| --- | --- |
| Sum of layer contents (what lands on disk, `docker history`) | **~874 MB** |
| Content blobs (what a registry pull transfers, compressed) | **~417 MB** |
| What `docker images` prints (the two added together) | **1.29 GB** |

The 874 MB breaks down as:

| Layer | Size | |
| --- | --- | --- |
| Ubuntu 24.04 base | 110 MB | base image |
| Temurin's apt layer (curl, wget, gnupg, fontconfig, tzdata, locales) | 52 MB | base image |
| JRE 21 | 165 MB | base image |
| **DJL native cache** (`libtorch_cpu.so` alone is 213 MB) | **288 MB** | baked, see below |
| Application dependencies (`onnxruntime` 89 MB, `tokenizers` 18 MB, everything else) | 168 MB | |
| **ONNX model + tokenizer** | **91 MB** | baked, see below |
| Loader + application classes | 0.9 MB | |

Two thirds of this image is machine-learning native code and model weights. That is the
shape of the application, not a packaging mistake — but if the size matters to you, the
levers, in descending order of payoff:

- **Do not bake the models** (−379 MB image, +260 MB downloaded on every cold start).
  See the tradeoff section below. This is the one real choice.
- **Drop `pytorch-engine`** (−288 MB baked cache, −some of the dependency layer). It is
  pulled in transitively by `spring-ai-starter-model-transformers` and is used *only* for
  `NDManager` tensor bookkeeping — the actual inference runs on ONNX Runtime. Removing it
  means a `pom.xml` exclusion plus a substitute DJL engine, and was out of scope here.
- **`jlink` a custom JRE** (−100 MB or so). Adds a module-detection step and a real risk
  of missing a reflectively-loaded module; not worth it until the two items above are done.
- Alpine is **not** an option: `onnxruntime` and `djl tokenizers` ship glibc natives.

---

## The ONNX / DJL model tradeoff

### What actually happens at startup

The stated behaviour — "downloads ~87 MB of ONNX model into `${user.dir}/onnx-model-cache`
on first start" — is real but incomplete. There is a second, larger download that only
shows up once the first one is fixed:

`TransformersEmbeddingModel` runs inference through ONNX Runtime, but allocates its
tensors through DJL's `NDManager`. `NDManager` resolves whichever DJL engine is on the
classpath, and `spring-ai-starter-model-transformers` brings in **`pytorch-engine`**. So
the first time an embedding is computed, `PtEngine` downloads **~170 MB of libtorch** from
`publish.djl.ai`. That first embedding happens during startup, inside
`FaqIngestionService`.

Total unbaked cold-start download: **~260 MB** (87 MB model + ~170 MB libtorch), from two
different hosts, before the app can serve a single request.

### The decision: bake both into the image

- `model.onnx` and `tokenizer.json` → `/opt/onnx`, with
  `SPRING_AI_EMBEDDING_TRANSFORMER_ONNX_MODEL_URI=file:/opt/onnx/model.onnx` and the
  matching tokenizer URI. Spring AI's `ResourceCacheService` excludes the `file` and
  `classpath` schemes from caching, so this bypasses both the download and the on-disk
  cache. Confirmed in the logs:
  `The URL [file:/opt/onnx/model.onnx] resource with URI schema [file] is excluded from caching`.
- The DJL native cache → `/opt/djl`, with `DJL_CACHE_DIR=/opt/djl`. Populated at build
  time by a small inlined program that calls `NDManager.newBaseManager()`.

### Why this over the alternatives

| Option | Image | Cold start | Needs egress? | Read-only rootfs? |
| --- | --- | --- | --- | --- |
| **Bake into the image** (chosen) | 874 MB | **2.6 s** | no | yes |
| Mount a shared volume / PVC | 495 MB | fast when warm | on first fill | needs a writable mount |
| Leave it, download per start | 495 MB | +260 MB of transfer | **yes, every pod** | **no** |

Baking costs ~379 MB of image. In exchange:

- **Cold start is 2.6 s instead of minutes.** For a Deployment that scales or reschedules,
  this is the whole argument. Pod churn stops being an availability event.
- **No runtime egress.** No dependency on `publish.djl.ai` or `githubusercontent.com`
  being up, or on egress policy allowing them. Verified: zero download log lines in a full
  startup.
- **Works with `readOnlyRootFilesystem: true`.** Verified in Compose with `read_only: true`
  — `touch /app/x` → `Read-only file system`, app healthy anyway.
- **Reproducible.** The unbaked path resolves `refs/heads/main`, so two pods started a
  month apart can silently get different weights. A baked image is one artifact.

The volume option was rejected because it trades a one-time 379 MB image cost for a
permanent operational one: a PVC to provision, a `ReadWriteMany` requirement (or a fill
job) if replicas are to share it, a first-pod-fills-it race, and a cache that can drift
from the code that expects it. The image *is* the right cache here — it is already
versioned, already distributed, and already has a registry in front of it.

Where baking is the wrong call: an air-gapped build farm with no egress (point the
`ONNX_MODEL_URI` / `ONNX_TOKENIZER_URI` build args at an internal mirror — that is why
they are `ARG`s), or a laptop-only workflow where a 379 MB image rebuild is more annoying
than a one-off download into a cache that then persists.

---

## Kubernetes

Manifests and apply instructions: [`k8s/README.md`](../k8s/README.md).

```sh
kubectl apply -f k8s/namespace.yaml

kubectl -n ai-customer-service create secret generic ai-customer-service-secrets \
  --from-literal=ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
  --from-literal=POSTGRES_USER='csagent' \
  --from-literal=POSTGRES_PASSWORD="$PGPASSWORD"

kubectl apply -f k8s/configmap.yaml -f k8s/deployment.yaml -f k8s/service.yaml
kubectl -n ai-customer-service rollout status deploy/ai-customer-service
```

Edit the image reference in `deployment.yaml` and the Postgres coordinates in
`configmap.yaml` first. `k8s/secret.example.yaml` is a template with placeholder values
only — do not apply it, and do not fill it in and commit it (`k8s/secret.yaml` is
git-ignored for the copy).

Highlights:

- **Probes** use the Actuator health groups, which exist because
  `management.endpoint.health.probes.enabled: true` is already set:
  `/actuator/health/liveness` and `/actuator/health/readiness`. A `startupProbe`
  (30 × 5s) absorbs the variable boot so the liveness probe can stay tight without ever
  killing a pod that is merely still starting.
- **Resources**: requests `500m` / `1Gi`, memory limit `2Gi`, no CPU limit. The JVM sizes
  its heap from the limit via `-XX:MaxRAMPercentage=70`, so `2Gi` means ~1.4 GB heap and
  ~600 MB for native. That headroom is not padding — a 90 MB ONNX model and an
  onnxruntime session live outside the heap.
- **Non-root and hardened**: `runAsNonRoot`, uid/gid 10001 (matching the image),
  `readOnlyRootFilesystem: true`, all capabilities dropped, `RuntimeDefault` seccomp, and
  a single writable `emptyDir` at `/tmp`.
- **No Postgres.** This app is a system of record; give it a managed database.

### Validation performed

`kubectl apply --dry-run=client` (client v1.36.1) passes for all five files. That checks
schema and structure only. **No server-side dry-run and no live apply was performed** —
there was no target cluster for this namespace, and namespaced objects cannot be
server-dry-run before their namespace exists. `kubeconform`/`kubeval` are not installed on
this machine.

---

## Things that will bite you

**`/tmp` must be executable.** ONNX Runtime extracts `libonnxruntime.so` into
`java.io.tmpdir` and `System.load()`s it. Docker mounts `tmpfs` `noexec` by default, so
`tmpfs: - /tmp:mode=1777,size=256m` fails at startup with:

```
UnsatisfiedLinkError: ... failed to map segment from shared object
```

Hence the explicit `exec` in `docker-compose.yml`. In Kubernetes a disk-backed `emptyDir`
is exec-capable by default — but if a cluster policy adds `noexec`, or you switch it to
`medium: Memory` on a runtime that mounts those `noexec`, you will hit the same wall.
Measured usage is ~14 MB; the mount is sized 256Mi so a heap dump also fits.

**DJL creates its cache directories `0700`.** Baked at build time as root, they are then
unreadable by uid 10001, and DJL responds by silently falling back to `$TMPDIR` and
re-downloading 170 MB. The Dockerfile `chmod -R a+rX` fixes it. If you change the cache
plumbing, verify with:

```sh
docker run --rm --entrypoint sh <image> -c 'ls -l /opt/djl/pytorch/*/ | head'
```

Silence is the failure mode here, not an error.

**`ResourceCacheService` mkdirs its cache directory even when caching is disabled.**
`TransformersEmbeddingModel.afterPropertiesSet()` constructs it unconditionally.
`application.yml` points it at `${user.dir}`, which is `/app` and read-only in the
container, so the image redirects it to `/tmp/onnx-model-cache`. It stays empty — the
model comes from `file:` — but the directory has to be creatable.

**A green rollout does not mean a working API key.** See the warning at the top.


## Tracing

`docker compose up` also starts Jaeger (`jaegertracing/jaeger:2.20.0`), which ingests OTLP
directly — no separate collector is needed for local work. A real deployment would put an
OpenTelemetry Collector between the application and its tracing backend.

| Variable | Compose value | Meaning |
| --- | --- | --- |
| `OTLP_TRACING_EXPORT_ENABLED` | `true` | Off by default, so running the app without a collector does not log export failures on every span |
| `OTLP_TRACING_ENDPOINT` | `http://jaeger:4318/v1/traces` | OTLP/HTTP traces endpoint |
| `TRACING_SAMPLE_RATE` | `1.0` | Spring Boot's default is `0.1`; lower this deliberately under real traffic |
| `TRACE_INCLUDE_QUERY_CONTENT` | unset (`false`) | Attaches the customer's question to vector-store spans. Debugging only — see the README's Observability section |

Jaeger's UI is on port 16686 and its OTLP/HTTP ingest on 4318; both are overridable with
`JAEGER_UI_PORT` and `OTLP_HTTP_PORT`.

The Kubernetes manifests do not deploy a tracing backend. Set `OTLP_TRACING_EXPORT_ENABLED` and
`OTLP_TRACING_ENDPOINT` in the ConfigMap to point at whatever collector the cluster already has.
Jaeger's storage here is in-memory and resets when the container restarts, which is fine for
local work and not a production configuration.

---

[← Back to the README](../README.md)
