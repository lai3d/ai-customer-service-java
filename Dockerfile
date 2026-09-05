# syntax=docker/dockerfile:1.7
#
# Multi-stage build for ai-customer-service-java.
#
#   docker build -t ai-customer-service-java:local .
#
# Three stages:
#   build  - JDK 21, Maven wrapper, produces a Spring Boot layered jar and explodes it
#   onnx   - downloads the multilingual-e5-small embedding model so it is baked into the image
#   final  - JRE 21, non-root, runs the exploded jar via JarLauncher
#
# See docs/deployment.md for the reasoning, especially around the ONNX model.

# Pinned base image tags. No bare `latest` -- a JDK/JRE bump should be a visible commit.
ARG JDK_IMAGE=eclipse-temurin:21.0.9_10-jdk-noble
ARG JRE_IMAGE=eclipse-temurin:21.0.9_10-jre-noble


# ---------------------------------------------------------------------------
# Stage 1: build
# ---------------------------------------------------------------------------
FROM ${JDK_IMAGE} AS build

WORKDIR /build

# Dependency resolution is its own layer, keyed only on pom.xml and the wrapper.
# Editing anything under src/ leaves this layer -- and the ~500 MB of Maven
# downloads behind it -- untouched.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -ntp -DskipTests dependency:go-offline

COPY src/ src/
# Tests are skipped deliberately: the integration tests need Testcontainers, i.e. a
# Docker socket, which is not available inside a `docker build`. CI runs `./mvnw verify`
# separately -- the image build is not the test gate.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -ntp -DskipTests package \
 && cp target/*.jar /build/app.jar

# Explode the layered jar. The payoff here is large and specific to this app: the
# `dependencies` layer is 168 MB (onnxruntime alone is 89 MB, djl tokenizers 18 MB)
# while `application` is 184 KB. Shipping the fat jar as one COPY would push 168 MB
# of unchanged bytes through the registry on every source edit; layering pushes ~190 KB.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination /build/extracted

# Warm the DJL native cache.
#
# This one is easy to miss. TransformersEmbeddingModel runs inference through ONNX
# Runtime, but it allocates its tensors through DJL's NDManager, and NDManager picks
# whatever DJL engine is on the classpath -- which is pytorch-engine, pulled in
# transitively by spring-ai-starter-model-transformers. The first time an embedding is
# computed, PtEngine downloads ~170 MB of libtorch from publish.djl.ai. That first
# embedding happens during startup, in FaqIngestionService, so every cold container
# would otherwise pull 170 MB before it could become ready.
#
# Triggering NDManager.newBaseManager() here does that download once, at build time,
# into a directory that gets copied into the final image. The tokenizers JNI library
# lands in the same cache, so that is baked too.
#
# The program is inlined rather than added to src/ deliberately: it is a build artifact
# of the image, not part of the application.
RUN <<'EOF'
set -eux
mkdir -p /build/warmup
cat > /build/warmup/DjlWarmup.java <<'JAVA'
public final class DjlWarmup {
    public static void main(String[] args) throws Exception {
        try (var manager = ai.djl.ndarray.NDManager.newBaseManager()) {
            System.out.println("DJL engine warmed: " + manager.getEngine().getEngineName()
                    + " " + manager.getEngine().getVersion());
        }
        ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
                .newInstance(java.nio.file.Path.of("/dev/null"), java.util.Map.of());
    }
}
JAVA
CP="$(find /build/extracted/dependencies/BOOT-INF/lib -name '*.jar' | tr '\n' ':')"
export DJL_CACHE_DIR=/build/djl-cache
# The tokenizer instantiation on /dev/null always throws; loading the JNI library is the
# point, and that happens in LibUtils' static initialiser before the parse fails. Only
# the NDManager half has to succeed, so check the cache is populated rather than the exit
# code.
java -cp "${CP}" /build/warmup/DjlWarmup.java || true
test -d /build/djl-cache/pytorch
test -n "$(find /build/djl-cache/pytorch -name 'libtorch_cpu.so' -o -name 'libtorch.so' | head -1)"
test -n "$(find /build/djl-cache -name 'libtokenizers.so' | head -1)"
# DJL creates its cache directories 0700. This runs as root; the app runs as uid 10001
# and would get "Permission denied", silently fall back to $TMPDIR and re-download.
chmod -R a+rX /build/djl-cache
# The .gz archives DJL downloaded are dead weight once the .so files are extracted.
find /build/djl-cache -name '*.gz' -delete
du -sh /build/djl-cache
EOF


# ---------------------------------------------------------------------------
# Stage 2: onnx model
# ---------------------------------------------------------------------------
# These are the exact defaults compiled into
# org.springframework.ai.transformers.TransformersEmbeddingModel
# (DEFAULT_ONNX_MODEL_URI / DEFAULT_ONNX_TOKENIZER_URI) for Spring AI 1.1.8.
# They are ARGs so an air-gapped build can point them at an internal mirror.
FROM ${JRE_IMAGE} AS onnx

ARG ONNX_MODEL_URI=https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/onnx/model.onnx
ARG ONNX_TOKENIZER_URI=https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/tokenizer.json

RUN set -eux; \
    mkdir -p /onnx; \
    curl -fsSL --retry 3 --retry-delay 2 -o /onnx/model.onnx "${ONNX_MODEL_URI}"; \
    curl -fsSL --retry 3 --retry-delay 2 -o /onnx/tokenizer.json "${ONNX_TOKENIZER_URI}"; \
    # Git LFS pointer files are ~130 bytes and return HTTP 200. Fail loudly rather than
    # baking a text file that only blows up at runtime inside OrtSession.
    test "$(stat -c %s /onnx/model.onnx)" -gt 50000000; \
    test "$(stat -c %s /onnx/tokenizer.json)" -gt 100000


# ---------------------------------------------------------------------------
# Stage 3: runtime
# ---------------------------------------------------------------------------
FROM ${JRE_IMAGE}

# Fixed, high, non-system uid/gid so the same numeric id can be asserted in the
# Kubernetes securityContext (runAsUser: 10001) without depending on /etc/passwd.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

# Baked embedding model. Read-only, owned by root -- the app only ever reads it.
COPY --from=onnx /onnx/ /opt/onnx/

# Baked DJL native cache (libtorch + libtokenizers). Same idea: nothing is downloaded at
# runtime. DJL only reads this directory when the natives are already present, so it is
# fine for it to be root-owned and on a read-only root filesystem.
COPY --from=build /build/djl-cache/ /opt/djl/

# Ordered least- to most-frequently-changed so the cheap layer is the one that churns.
COPY --from=build --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/application/ ./

# Container awareness. The JVM reads cgroup limits on its own since JDK 10, but its
# default MaxRAMPercentage of 25% wastes three quarters of a small container, and the
# ONNX session plus the Hikari pool want more than that.
#   MaxRAMPercentage=70   leaves ~30% for the ONNX runtime's native (off-heap) arenas,
#                         Netty/NIO buffers and thread stacks. Do not raise it blindly:
#                         this workload's native footprint is unusually large for a
#                         Spring Boot app.
#   ExitOnOutOfMemoryError so an OOM becomes a pod restart instead of a zombie that
#                         still passes TCP checks.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -Djava.awt.headless=true"

# Point Spring AI at the baked model instead of the GitHub URLs. ResourceCacheService
# excludes the `file` and `classpath` schemes from caching, so with these set the 449 MiB
# download and the on-disk cache are both bypassed entirely.
ENV SPRING_AI_EMBEDDING_TRANSFORMER_ONNX_MODEL_URI=file:/opt/onnx/model.onnx \
    SPRING_AI_EMBEDDING_TRANSFORMER_TOKENIZER_URI=file:/opt/onnx/tokenizer.json

# TransformersEmbeddingModel.afterPropertiesSet() constructs a ResourceCacheService
# unconditionally, and its constructor mkdirs() the cache directory even when caching is
# disabled. application.yml points that at ${user.dir}, which is /app and is read-only
# under a hardened securityContext. Redirect it to /tmp, which is writable (an emptyDir
# in Kubernetes). Nothing is ever written there now that the model comes from file:.
ENV SPRING_AI_EMBEDDING_TRANSFORMER_CACHE_DIRECTORY=/tmp/onnx-model-cache \
    SERVER_PORT=8080

# Point DJL at the cache baked in above. Without this it defaults to $user.home/.djl.ai
# -- which is /app, read-only under a hardened securityContext, so DJL silently falls
# back to java.io.tmpdir and re-downloads ~170 MB of libtorch on every container start.
ENV DJL_CACHE_DIR=/opt/djl

EXPOSE 8080

# Worth having: `docker run` and plain Compose have no other way to know the app is
# ready, and readiness here is genuinely late -- Spring must reach Postgres, create the
# pgvector schema and ingest 18 FAQ documents first. start-period covers that; failures
# only start counting after it. Kubernetes ignores this and uses the probes in
# k8s/deployment.yaml instead.
HEALTHCHECK --interval=15s --timeout=3s --start-period=120s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1

USER 10001:10001

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
