# Observability


Metrics and traces come from the same Micrometer instrumentation, and Spring AI already emits
OpenTelemetry's GenAI semantic conventions — `gen_ai.request.model`, `gen_ai.usage.input_tokens`,
`gen_ai.response.finish_reasons` — so nothing here invents a vocabulary.

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
   └─ chat claude-opus-5         gen_ai.usage.*, finish reasons
```

`docker compose up` starts Jaeger alongside the app and points the exporter at it; the UI is at
**http://localhost:16686**. Jaeger ingests OTLP directly, so no separate collector is needed
locally — a real deployment would put an OpenTelemetry Collector in front. Export is off by
default when you run the app on its own, so `./mvnw spring-boot:run` does not fill the log with
failed exports.

**Sampling is set to 1.0, not Spring Boot's default 0.1.** At the default rate nine out of ten
conversations produce no trace, which reads as "tracing is broken" rather than "tracing is
sampled". Lower it deliberately under real traffic.

### Customer messages are kept out of traces

Spring AI has switches for prompt and completion content — `log-prompt`, `log-completion`,
`log-query-response` — and all three default to off. The vector store's *query* text is not
among them: `db.vector.query.content` is added unconditionally, so the question a customer typed
leaves the process on every search. That was found by reading it back out of Jaeger, not by
reading the docs.

It is a reasonable default for a library and a poor one for a support system, where the query is
often the most sensitive thing in the request. A
[custom observation convention](../src/main/java/dev/merlionos/customerservice/observability/PrivacyPreservingVectorStoreObservationConvention.java)
drops it unless `app.observability.include-query-content` is deliberately switched on for
debugging. Everything that makes the span useful — top-k, threshold, similarity metric,
dimensions, timing — is kept.

---

[← Back to the README](../README.md)
