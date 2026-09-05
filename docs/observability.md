# Observability

Metrics, traces and logs, from one set of Micrometer instrumentation, into the Grafana stack:
Prometheus, Tempo and Loki, with Grafana in front and the links between the three provisioned.
Spring AI already emits OpenTelemetry's GenAI semantic conventions — `gen_ai.request.model`,
`gen_ai.usage.input_tokens`, `gen_ai.response.finish_reasons` — so nothing here invents a
vocabulary; the counters this repository adds fill the gaps Spring AI leaves and are listed
below.

```
COMPOSE_PROFILES=observability docker compose up -d   # or put the line in .env
open http://localhost:3000        # Grafana: two dashboards, Explore for traces and logs
open http://localhost:9090        # Prometheus: targets, rules, raw queries
```

The stack is a Compose profile, not the default: `docker compose up` alone is Postgres and
the application, which is everything the service needs to run. The five containers are
what you add to look at it. The application's trace export follows the same variable, so a
plain `docker compose up` never exports into a Tempo that is not there.

---

## Contents

- [What the stack is](#what-the-stack-is)
- [Traces](#traces)
- [Metrics](#metrics)
- [Logs](#logs)
- [The links between them](#the-links-between-them)
- [Dashboards and alerts, as code](#dashboards-and-alerts-as-code)
- [Pull or push](#pull-or-push)
- [Customer messages are kept out of traces](#customer-messages-are-kept-out-of-traces)

---

## What the stack is

| | Backend | How it gets there | Where to look |
| --- | --- | --- | --- |
| Traces | **Tempo** | The application exports OTLP/HTTP straight to Tempo | Grafana Explore, Tempo datasource; the demo page links each turn |
| Metrics | **Prometheus** | Prometheus pulls `/actuator/prometheus` every 15 s | Grafana dashboards; `:9090` for raw queries and alert state |
| Logs | **Loki** | **Alloy** tails every container over the Docker socket | Grafana Explore, Loki datasource; one click from a span |

Tempo rather than Jaeger, which is what this repository shipped first, for two reasons that
only matter once there is more than one process. Grafana can follow a link from a Prometheus
exemplar into a Tempo trace and from that trace into the Loki log lines written under it;
with Jaeger the trace lived in another UI and the chain broke there. And Tempo's
metrics-generator derives request rate, error rate, latency and a service graph from the spans
themselves, so in the [roles topology](deployment.md#running-the-roles-separately) the edges
chat → knowledge and chat → ticket are drawn without a line of instrumentation for them.

Everything is in `observability/`: one config file per backend, the Grafana provisioning for
datasources and dashboards, the dashboards as JSON, and the alert rules. Both Compose files
run the same stack; `docker-compose.services.yml` scrapes three roles instead of one process.

## Traces

Traces matter more for this kind of service than for an ordinary one. A single turn is
retrieval, then a model call, then possibly a tool call and a second model call. Metrics can
tell you a turn took eight seconds; only a trace tells you which of those it was:

```
http post /api/v1/chat
└─ spring_ai chat_client
   ├─ message_chat_memory        (advisor)
   ├─ question_answer            (advisor)
   │  ├─ embedding               (query → vector, in-process)
   │  └─ pg_vector query         top_k, threshold, dimensions
   ├─ chat claude-opus-5         gen_ai.usage.*, finish reasons
   ├─ lookup_order_status        (tool)
   └─ chat claude-opus-5         the second call, after the tool
```

In the roles topology the `question_answer` span has a child `http post /internal/v1/knowledge/search`
in the chat process and a server span in the knowledge process under the same trace id, because
the internal clients are built from Spring's `RestClient.Builder` and carry `traceparent`;
`TopologyParityTest` asserts the header crosses.

**Sampling is set to 1.0, not Spring Boot's default 0.1.** At the default rate nine out of ten
conversations produce no trace, which reads as "tracing is broken" rather than "tracing is
sampled". Lower it deliberately under real traffic. Export is off when the application runs on
its own, so `./mvnw spring-boot:run` does not fill the log with failed exports; Compose turns
it on because it brings Tempo up alongside.

## Metrics

Spring AI's, from its observations:

| Metric | What it is |
| --- | --- |
| `gen_ai_client_operation_seconds` | One model call: latency, with `gen_ai_system`, the requested and reported model, and `error` |
| `gen_ai_client_token_usage_total` | Spring AI's own token count. Tool-calling turns are counted twice here, because Spring AI repeats the accumulated usage across both calls of a turn; see [reliability.md](reliability.md#a-turn-is-not-a-model-call) |
| `db_vector_client_operation_seconds` | pgvector `add`, `delete`, `query` |
| `spring_ai_advisor_seconds`, `spring_ai_chat_client_seconds` | The advisor chain and the client call around it |

This repository's, where Spring AI's were wrong or absent:

| Metric | What it is |
| --- | --- |
| `chat_tokens_total{model,type}` | Tokens per turn after `TurnUsage` reconstructs the call boundary; the number to bill from |
| `chat_cost_usd_total{model}` | Priced from `app.cost.prices`, keyed on the model id the provider *reports* |
| `chat_unpriced_model_calls_total{model}` | Calls whose model had no price: tokens counted, cost silently zero. Alerted on above zero |
| `chat_tool_invocations_total{tool,outcome}` | `found`/`not_found`; `created`/`duplicate_suppressed`/`capped`/`unavailable` |
| `chat_stream_terminations_total{outcome}` | `completed`, `cancelled` (the customer left), `failed` (an error event after the 200) |
| `chat_lease_conflicts_total` | Turns refused because another turn held the conversation's lease: the direct count behind the 409s, incremented where the lease refuses |
| `chat_knowledge_unavailable_total` | Knowledge searches over the seam that failed, each a turn ended with a 503. Registered at zero in every topology, can only rise in the roles one. Alerted on above zero |
| `corpus_import_seconds{outcome}`, `corpus_documents` | How long a start spent under the import lock, `imported` or `already_present`; and the document count recorded for the bundled corpus version, 0 until it is imported, read from the database on each scrape |

And the platform's: `http_server_requests_seconds` for the public endpoints and the
internal ones, `http_client_requests_seconds` for the model provider and, in the roles
topology, the seams; JVM, Hikari and Tomcat from Actuator.

**The timers are histograms, not summaries.** Micrometer's default export is count, sum and
max, which is enough for a rate and an average and nothing else: `histogram_quantile` has no
buckets to work on and there is nowhere for an exemplar to hang a trace id. `application.yml`
turns buckets on for the four timers the dashboards take a percentile of and raises their
ceiling, since a turn takes seconds and a model call can take a minute. Every metric a dashboard
references is asserted to exist in `/actuator/prometheus` after a turn, so a panel cannot
quietly become "No data".

## Logs

Alloy discovers every container through the Docker socket, tails its stdout, and ships it to
Loki with two labels, the Compose service and the container name. Two, because labels are
what Loki indexes and one per trace id would be a cardinality bomb. The trace id is instead
parsed out of Spring Boot's correlation prefix — `[<traceId>-<spanId>]` after the thread name,
which Boot writes whenever Micrometer Tracing is on the classpath — into *structured
metadata*, indexed per line: cheap to store, filterable in LogQL, and what the trace-to-logs
link queries on. The first version of that regex was written from the documented pattern,
`[app,traceId,spanId]`, and matched nothing; the smoke test that follows a trace id from a
Prometheus exemplar into Loki is what said so.

```
{service="app"} | traceId = "26d2aadda372aef782e347e529aed5b4"
```

## The links between them

Provisioned in `observability/grafana/provisioning/datasources/datasources.yaml`, so a fresh
Grafana has them on first start:

- **Metric → trace.** A histogram bucket observation carries an exemplar, the id of the trace
  that was current when it was recorded; Micrometer attaches it, Prometheus keeps it
  (`--enable-feature=exemplar-storage`), and a point on a latency panel opens that turn in
  Tempo.
- **Trace → logs.** From a span, "Logs for this span" runs the LogQL above with the span's
  trace id.
- **Log → trace.** A derived field on the Loki datasource matches the correlation prefix and
  turns the trace id into a link.
- **Trace → service graph.** Tempo pushes span-derived metrics to Prometheus over remote write
  (`--web.enable-remote-write-receiver`), and the Tempo datasource's Service Graph tab draws
  the topology from them.

The demo page's "view this trace" link opens Grafana Explore with the trace id as a TraceQL
query, which is the entry point to all of the above for one turn.

## Dashboards and alerts, as code

Two dashboards in `observability/grafana/dashboards/`, provisioned read-only:

- **Customer service** — turns per second by status, turn latency percentiles with exemplars,
  where the time goes (model call versus retrieval), model errors, stream terminations, tokens
  and dollars by model, unpriced calls, budget and overlap refusals (the 409s beside the
  lease's own count), tool outcomes.
- **Customer service roles** — memory, threads, CPU and connection pool per role; the seams
  (calls and latency from chat to knowledge and ticket, ticket writes that fell back to
  `unavailable`, knowledge searches that failed, the corpus's document count and how long its
  import took); Tempo's service graph edges; the pipeline's own health.

Nine alert rules in `observability/prometheus/rules/`, each with a paragraph saying why it
exists: a target down, turn error rate, slow turns, unpriced model calls, cost burn rate,
stream failures, ticket writes that fell back to `unavailable`, the knowledge seam failing,
overlapping-turn refusals.
Prometheus rules rather than Grafana's, because they live in git, `promtool check rules` can
check them in CI, and the same file is what a `PrometheusRule` carries on a cluster.

On Kubernetes the same rules and dashboards are `k8s/observability`, a Kustomize overlay for
kube-prometheus-stack: a `ServiceMonitor`, a `PrometheusRule` and one ConfigMap per dashboard.
The rule and the ConfigMaps are copies rendered by `scripts/render-k8s-observability.sh`,
because Kustomize cannot reach this directory from there; `ObservabilityManifestsTest` fails if
a copy and its source differ. See [k8s/README.md](../k8s/README.md#observability).

## Pull or push

Prometheus pulling `/actuator/prometheus` is the default and needs nothing between the
application and the backend; Kubernetes expects it, and the scrape annotations are on the
pods. Push over OTLP is the other transport, and it is supported and exercised rather than
merely possible: `docker compose --profile collector up` starts an OpenTelemetry Collector,
and with `OTLP_TRACING_ENDPOINT` pointed at it and `OTLP_METRICS_EXPORT_ENABLED=true` the
application pushes both signals to one endpoint and the Collector fans them out to Tempo and
Prometheus. That is the shape a deployment with a different backend would use.

The names differ on the wire — Micrometer sends `chat.tokens` over OTLP and Prometheus's
receiver renders it `chat_tokens_total` — and the two registries' histograms are not the same
histogram: Prometheus buckets are fixed, OTLP's can be exponential, so a p95 read through
each path is close but not identical. The dashboards use one set of queries for both, and the
same assertion that checks every referenced metric exists runs against both exports.

## Customer messages are kept out of traces

Spring AI has switches for prompt and completion content — `log-prompt`, `log-completion`,
`log-query-response` — and all three default to off. The vector store's *query* text is not
among them: `db.vector.query.content` is added unconditionally, so the question a customer typed
leaves the process on every search. That was found by reading it back out of the trace
backend, not by reading the docs.

It is a reasonable default for a library and a poor one for a support system, where the query is
often the most sensitive thing in the request. A
[custom observation convention](../src/main/java/dev/merlionos/customerservice/observability/PrivacyPreservingVectorStoreObservationConvention.java)
drops it unless `app.observability.include-query-content` is deliberately switched on for
debugging. Everything that makes the span useful — top-k, threshold, similarity metric,
dimensions, timing — is kept. The same applies one level down now that logs are collected:
nothing in this codebase logs a customer message at INFO, and Alloy ships whatever is logged.

---

[← Back to the README](../README.md)
