# One process or several: deployment targets

**Status: superseded.** Reconciled with the independently written Codex proposal in
[ADR 001](adr/001-deployment-targets.md), which records what was kept from each. Preserved
below as the original proposal; only this status note was added.

**Original status: proposal.** Nothing in this document is implemented. It exists to be compared against
an independently written design for the same change, and the two will be reconciled before
any code lands. Where a claim below was checked against Spring AI 1.1.8's actual classes or
configuration metadata it says so; where it was not, it says "to verify".

---

## Contents

- [The question, restated](#the-question-restated)
- [The shape: one artifact, a target flag](#the-shape-one-artifact-a-target-flag)
- [What stays inside the chat process, and why that makes this cheap](#what-stays-inside-the-chat-process-and-why-that-makes-this-cheap)
- [The three seams](#the-three-seams)
- [State that has to leave the process in both modes](#state-that-has-to-leave-the-process-in-both-modes)
- [Ingestion becomes a one-shot target](#ingestion-becomes-a-one-shot-target)
- [One database, one schema per module](#one-database-one-schema-per-module)
- [Failure over the network](#failure-over-the-network)
- [Observability across the seams](#observability-across-the-seams)
- [Deployment](#deployment)
- [Testing: the seam has to be real](#testing-the-seam-has-to-be-real)
- [What this does to the Go comparison](#what-this-does-to-the-go-comparison)
- [Alternatives considered](#alternatives-considered)
- [Plan](#plan)
- [Open questions](#open-questions)

---

## The question, restated

"Should this be microservices?" is two questions wearing one coat.

The first is **whether the process can run as more than one replica correctly**. Today it
cannot. `k8s/deployment.yaml` runs two replicas with no session affinity, and three pieces of
state are held per replica: the conversation token budget (`ConversationBudget`, a bounded LRU
map), the ticket deduplication table (`SupportTicketTools`, a `ConcurrentHashMap`), and the
mock order data. [Cost and failure](reliability.md) already says so in as many words: the cap
is "an upper bound of `replicas × 3` rather than 3", and spend is "per replica, reset on
restart". Whatever else is decided, this has to be fixed, and fixing it does not require a
second process.

The second is **which parts of a turn belong on the far side of a network boundary**. The
honest answer for a customer-service assistant is: the business systems. `lookup_order_status`
and `create_support_ticket` are placeholders for an order service and a ticketing system that
in any real deployment already exist, are owned by other teams, and are reached over HTTP. The
mock hides exactly the failure modes that matter there: a tool call that times out, a trace
that has to cross a service boundary, an idempotency key that has to hold across two
processes. Making them real services is not decomposition for its own sake; it is making the
mock stop lying about where the boundary is.

Everything else in a turn, retrieval, memory, the advisor chain, the model call, the tool
loop, the SSE stream, is one unit of work whose correctness constraints only exist inside one
`ChatClient`. Splitting it gains nothing and loses the constraints.

So the proposal is not "monolith or microservices". It is: **one artifact that can run as one
process or as several, chosen at start time**, the way Grafana Loki runs the same binary with
`-target=all` on a laptop and `-target=ingester` in a fleet. The single-process mode must
behave exactly as the service does today, so that every measurement in this repository, and
the comparison with the Go implementation, keeps meaning what it meant.

---

## The shape: one artifact, a target flag

One Maven module, one jar, one container image. An environment variable selects what the
process is:

```
APP_TARGET=all          # default; today's behaviour, byte for byte
APP_TARGET=chat
APP_TARGET=orders
APP_TARGET=tickets
APP_TARGET=embedding
APP_TARGET=ingest       # runs once and exits
```

| Target | What runs in the process | Owns | Exposes |
| --- | --- | --- | --- |
| `chat` | `ChatController`, `ChatService`, `ChatClient` and the advisor chain, the `@Tool` classes, `ConversationBudget`, `TurnEventBus`, chat memory | `spring_ai_chat_memory`, `conversation_budget` | `/api/v1/chat`, `/api/v1/chat/stream`, the demo page, actuator |
| `orders` | Order lookup over `MockOrderRepository` | nothing durable (mock) | `GET /internal/v1/orders/{orderNumber}`, actuator |
| `tickets` | Ticket creation with deduplication and the per-conversation cap | `support_ticket` | `POST /internal/v1/tickets`, actuator |
| `embedding` | The ONNX model behind `PrefixingEmbeddingModel` | nothing | `POST /v1/embeddings` (OpenAI-shaped), actuator |
| `ingest` | `FaqIngestionService` against the in-process model, then exit | `vector_store` rows | nothing |
| `all` | all of the above, wired in-process, ingest-on-boot as today | everything | everything |

The mechanism is a custom condition, `@ConditionalOnTarget("orders")`, evaluated against
`app.target`, applied to each module's `@Configuration`. It reads better than a
`@ConditionalOnExpression` on every class and it gives the target list one home. `all` matches
every module.

A handful of Spring AI auto-configuration switches must differ by target and cannot be
expressed as bean conditions because they gate auto-configurations, not our beans:

| Property | `all` / `embedding` / `ingest` | `chat` | `orders` / `tickets` |
| --- | --- | --- | --- |
| `spring.ai.model.chat` | provider | provider | `none` |
| `spring.ai.model.embedding` | `transformers` | `openai` (see [seam 3](#seam-3-embedding)) | `none` |
| `spring.ai.vectorstore.type` | `pgvector` | `pgvector` | `none` |
| `app.rag.ingest-on-startup` | `all`: `true`; `embedding`: `false`; `ingest`: `true` | `false` | `false` |

`spring.ai.vectorstore.type` is real: `PgVectorStoreAutoConfiguration` is
`@ConditionalOnProperty(name = SpringAIVectorStoreTypes.TYPE, havingValue = "pgvector")`
(checked in the 1.1.8 sources). `spring.ai.model.chat` and `spring.ai.model.embedding` are
already in `application.yml`.

These are set by an `EnvironmentPostProcessor` that reads `app.target` and adds a property
source above `application.yml`. The alternative, one Spring profile per target with a
`application-target-chat.yml` each, was rejected because it makes two knobs that can disagree:
`APP_TARGET=chat` with the wrong profile active is a process that reports healthy and does the
wrong thing, which is the failure mode this repository's [quick start](../README.md#quick-start)
already went out of its way to close for a missing API key. One variable, one post-processor,
and a boot-time check that refuses to start if the resulting configuration is inconsistent.

---

## What stays inside the chat process, and why that makes this cheap

Three things in this codebase are correctness constraints rather than structure, and all three
are defended by tests: advisor order, the tool context being non-empty on every path, and
`TurnEventBus` being keyed by turn. [CLAUDE.md](../CLAUDE.md) lists them. Any design that puts
a network between the parts that share one of these constraints has to re-express it across
that network, and none of them can be.

The observation that keeps this proposal small: **the `@Tool` classes stay in the chat
process. Only the tool's implementation moves.** `OrderTools` and `SupportTicketTools` keep
their annotations, their descriptions (which are prompt, not documentation), their
`ToolContext` parameter, their `chat.tool.invocations` meter and their `turnEventBus.publish`
call. What changes is that instead of calling `MockOrderRepository` directly they call an
interface, and in the `orders` target that interface is implemented by a controller in another
process. Consequently:

- `TurnEventBus` is untouched. Tools still run inside the model call in the chat process, and
  the `tool` SSE event still reaches the stream the way it does now.
- The advisor chain is untouched. `MessageChatMemoryAdvisor`, `QuestionAnswerAdvisor` and
  `RetrievalReportingAdvisor` all live in `chat` and their order is still one builder call.
- `ChatServiceToolContextTest`, `AdvisorChainOrderTest` and `TurnEventBusConcurrencyTest`
  continue to pin exactly what they pin today.

A design that moved the `@Tool` definitions to the tool services would need a way to get the
tool's *description* to the model (it is part of the prompt) and its *invocation* back to the
SSE stream, and would therefore need a message bus for what is, in the end, the string
`found`. This proposal does not need one.

---

## The three seams

Each seam is a Java interface with two implementations, selected by target. The in-process
implementation is what runs today; the remote one is a `RestClient` bound to a URL property.

### Seam 1: orders

```java
public interface OrderLookup {
    OrderLookupResult lookup(String orderNumber);
}
```

- `LocalOrderLookup` wraps `MockOrderRepository`. Active when the `orders` module is in this
  process.
- `HttpOrderLookup` calls `GET {app.services.orders.url}/internal/v1/orders/{orderNumber}`.
  Active otherwise; the URL is mandatory then, checked at boot.
- The `orders` target adds `OrderController`, which serialises `OrderLookupResult` as-is. A
  missing order is a `200` whose body says `found: false`, **not a `404`**. The point of
  [tool failures being values](tools.md) is that the model sees a sentence, not a stack trace,
  and a client that has to turn `404` back into a value has re-created the exception it was
  trying to avoid.

### Seam 2: tickets

```java
public interface TicketDesk {
    TicketResult create(String conversationId, String summary, String category);
}
```

- `LocalTicketDesk` is the current logic minus the map, over the `support_ticket` table (see
  [state](#state-that-has-to-leave-the-process-in-both-modes)).
- `HttpTicketDesk` calls `POST {app.services.tickets.url}/internal/v1/tickets` with the
  conversation id, summary and category. `TicketResult` already carries `created: false` plus an explanation for both the duplicate and the cap
  refusal as values, so the wire format is the record.
- The conversation id crosses the seam as a body field, not a header, because it is the
  idempotency key and the server's constraint is on it.

### Seam 3: embedding

This one needs no interface of ours. `EmbeddingModel` is Spring AI's, and the chat target's
`PgVectorStore` only needs *an* `EmbeddingModel` to embed the query
(`PgVectorStoreAutoConfiguration.vectorStore(JdbcTemplate, EmbeddingModel, ...)`, checked).

- The `embedding` target exposes `POST /v1/embeddings` in the OpenAI request and response
  shape, backed by the existing `PrefixingEmbeddingModel` over the ONNX model.
- The `chat` target sets `spring.ai.model.embedding=openai` and points Spring AI's own client
  at it. The properties exist in 1.1.8 (`spring-ai-autoconfigure-model-openai`'s configuration
  metadata): `spring.ai.openai.embedding.base-url`, `spring.ai.openai.embedding.api-key`,
  `spring.ai.openai.embedding.embeddings-path`, `spring.ai.openai.embedding.options.model`.
  No client code to write or test.
- **The `query:` prefix is applied on the server, always.** e5's asymmetric markers are part
  of the model contract, and the OpenAI embeddings request has no field to say which side a
  text is on. That is fine here because passages are never embedded over the wire: ingestion
  runs in the `ingest` and `all` targets against the in-process model, which applies
  `passage:` itself. The endpoint validates the `model` field against one accepted name
  (`multilingual-e5-small-query`) so that the assumption is written into the wire format
  rather than left implicit.
- *To verify:* whether `OpenAiEmbeddingAutoConfiguration` accepts a placeholder API key. The
  embedding service ignores the `Authorization` header, but the client may refuse a blank one.

The cost of this seam is a loopback-or-network HTTP call where today there is a 2 ms
in-process call, once per turn. [Benchmark](benchmark.md) and the Go comparison put the
in-process number on record; the services topology will be measured separately and the
number reported next to it, not instead of it.

---

## State that has to leave the process in both modes

Both of these change in `all` mode too. Keeping the in-memory version for `all` and the
database version for the services would be two implementations of the safety boundary, one
of which the default test suite never exercises. One implementation, in Postgres, for every
target.

**Ticket deduplication and the cap.** A `support_ticket` table with a unique constraint on
`(conversation_id, dedupe_key)`, where `dedupe_key` is the normalised summary the code
already computes. Creation is one statement that checks the count and inserts atomically, so
that the race [reliability.md](reliability.md#a-prompt-is-a-request-not-a-control) describes, two
concurrent calls each seeing two tickets and each adding a third, is closed by the database
rather than by a `compute` on one replica's map. The Codex review of 2026-09-04 flagged this
as its second P1 and suggested exactly this; the reply then was that these are mock tools
and the boundary is illustrative. Once the tool is a service that answer stops being
adequate, because the service's whole reason to exist is that boundary.

**Conversation budget.** A `conversation_budget` table keyed by conversation id, updated with
`UPDATE ... SET spent = spent + ? RETURNING spent` and read before the turn. The LRU bound
today is deliberate, an unbounded map keyed by conversation id being "a memory leak with a
long fuse"; the table needs the equivalent, a `last_seen` column and a periodic delete of
rows older than the memory window's practical lifetime. Cost metrics stay keyed by model and
never by conversation, as now.

Neither table needs `initialize-schema`-style magic. Two `CREATE TABLE IF NOT EXISTS`
statements on boot, in the same place the chat memory schema is initialised, are enough for a
schema this small; a migration tool would be a dependency in search of a second migration.

---

## Ingestion becomes a one-shot target

`FaqIngestionService` re-embeds the whole corpus on every start, and was recently made safe
for two replicas doing so at once (write first, delete stale by `corpus_version`). That
handles correctness. It does not handle the fact that every replica of an `embedding`
service would spend its first seconds re-embedding a corpus that is already there, on every
scale-out and every reschedule.

So ingestion is its own target. `APP_TARGET=ingest` starts, embeds the corpus with the
in-process model, reconciles `vector_store` and exits with `0`. In Kubernetes it is a `Job`;
in Compose it is a service with `restart: "no"` that `chat` and `embedding` do not depend on,
because the existing write-first ordering means a chat replica that starts before ingestion
finishes retrieves the previous corpus version rather than nothing.

In `all` mode nothing changes: the process ingests on boot exactly as it does today.

---

## One database, one schema per module

One Postgres instance, as now, with the tables that a module owns in that module's schema:
`chat` owns `spring_ai_chat_memory` and `conversation_budget`, `tickets` owns
`support_ticket`, `ingest` writes and `chat` reads `vector_store`.

The README already argues for one database: one thing to run, back up and reason about, and
transactional consistency between a ticket and the conversation that created it. A
database per service is the textbook microservice answer and would be the wrong one here for
the same reason a service mesh would be: it adds operational surface that nothing in this
system's failure modes asks for. Loki shares one object store across every target; the
analogy holds.

What one database does *not* give is isolation between targets' connection pools. Hikari's
`maximum-pool-size: 20` is per process; five targets are a hundred connections. The
per-target property set adjusts it, and this is written down in [deployment.md](deployment.md)
rather than discovered under load.

---

## Failure over the network

The rules [tools.md](tools.md) sets for tool failures extend across the seam unchanged: **the
model is never handed a transport error.**

- **Downstream returns a value.** `found: false`, `created: false` with its explanation, the cap refusal.
  These come back as `200` and deserialise to the same records the in-process path returns.
- **Downstream is unreachable, times out, or returns 5xx.** The `Http*` implementation returns
  `OrderLookupResult.unavailable(...)` / `TicketResult.unavailable(...)`, a new variant whose
  message tells the model to apologise and offer a human. It never throws, so
  `ToolExecutionExceptionProcessor` in `ChatClientConfig` stays the last line of defence rather
  than the first.
- **Tool timeouts are their own property.** `spring.http.client.read-timeout` is 120 s because
  a long answer from the model legitimately takes that long. An order lookup does not, and a
  tool that waits 120 s holds the model call, the SSE stream and the customer with it.
  `app.services.*.timeout`, default 5 s, applied to the tool clients only.
- **Embedding is unreachable.** `QuestionAnswerAdvisor` throws, the turn fails before the
  model is called, and the client sees `503` on the blocking endpoint or an `error` event on
  the stream. That is the right outcome: answering without retrieval means answering
  without grounding, and the system prompt's whole premise is grounding. It is also the
  outcome the retry configuration already bounds at three attempts.

---

## Observability across the seams

- **Traces.** The `Http*` clients are built from Spring's `RestClient.Builder` bean, which
  Micrometer Tracing instruments to propagate W3C `traceparent`. A tool span in Jaeger then
  has a child span in the `orders` service, and the demo page's trace link shows the hop. The
  clients must use the builder, not `RestClient.create()`; a test asserts that a downstream
  request carries the header, in the same spirit as `PrivacyPreservingVectorStoreObservationConventionTest`.
- **Metrics.** Every target exports `/actuator/prometheus`. A `target` tag joins
  `application` in `management.metrics.tags` so one dashboard can tell the processes apart.
  `chat.tool.invocations` is still counted in `chat`, at the `@Tool`, because that is where
  the *decision* to call a tool is observable. The downstream services count their own
  requests through the standard `http.server.requests`.
- **The customer's words.** They already stay out of traces on the retrieval span. The
  `tickets` seam carries the summary the *model* wrote, not the customer's message; the
  `orders` seam carries an order number. Neither is added to a span attribute, and
  `include-query-content` keeps governing the only place a query is recorded.

---

## Deployment

**One image.** `Dockerfile` is unchanged, the ONNX model stays baked in, and `APP_TARGET`
is the only difference between a `chat` pod and an `embedding` pod. Baking the model into
an image that will mostly run as `chat` costs 379 MB of registry space per tag; a second,
model-less image is a possible later optimisation and a deliberate non-goal now, because two
images are two things that can drift.

**Compose.** `docker-compose.yml` stays as it is: Postgres, Jaeger, `app` in `all` mode. A
second file, `docker-compose.services.yml`, replaces `app` with `chat`, `orders`, `tickets`,
`embedding` and a run-once `ingest`, each the same image with a different `APP_TARGET`, and
`chat` given the three service URLs. `ComposeEnvironmentTest` grows a second case that
asserts every documented variable is declared for every service in the second file, since
"Compose does not inject undeclared variables" bites five times over now.

**Kubernetes.** `k8s/` keeps its single-Deployment manifests for `all`. `k8s/services/` adds
one `Deployment` and `Service` per target and one `Job` for `ingest`, sharing the existing
`ConfigMap` and `Secret`. `chat` keeps the 45 s termination grace and the graceful-shutdown
setting; `orders`, `tickets` and `embedding` need much less and get 10 s. The `kind`
verification script runs both layouts, because the last time manifests were written without
being applied, two of them were wrong.

---

## Testing: the seam has to be real

This repository's cross-review found the same defect shape three times: a green test asserting
against a fixture built to satisfy the claim. A remote implementation tested only against a
mocked server would be the fourth, and it would be the most expensive one, because the whole
point of the services topology is what happens across a real socket.

So the topology test starts real processes, or the nearest thing to them:

1. `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `app.target=orders`, `tickets` and
   `embedding`, three contexts in the same JVM, each with its own port. Spring's test context
   cache makes them cheap to hold.
2. A fourth context with `app.target=chat` and `app.services.*.url` pointed at the three
   ports, against the same Testcontainers Postgres.
3. The existing chat integration scenario, run through the `chat` context's HTTP port: a
   question that retrieves, a question that looks up an order, a question that raises a
   ticket twice and gets one. Assertions read the database rows and the SSE events, not the
   client's return value, following the rule that came out of the review.
4. A fifth case that stops the `orders` context and asserts the next lookup produces the
   `unavailable` value and a completed turn, not a failed one.

Whether three contexts on random ports plus a fourth wired to them is actually workable in
one JVM with Spring's context cache is the largest technical risk in this document. If it is
not, the fallback is a Compose-based smoke test in CI, which is slower and further from the
code but still below the seam.

The existing suite runs against `all` and does not change. CI gains a second job for the
topology test so that a regression in either mode is a red badge.

---

## What this does to the Go comparison

Nothing, by construction. `APP_TARGET=all` is today's process: same threads, same benchmark,
same numbers in the README table. The rules that keep the two repositories comparable
(identical corpus, identical benchmark parameters, no shared code) are untouched.

Two things become worth saying explicitly:

- The 52 platform threads in the benchmark belong to `all`. In the services topology the
  chat process holds no ONNX session at all, and the carrier-pool finding moves to the
  `embedding` service, where it applies to a process doing nothing else. That is arguably the
  more interesting place to measure it.
- A `-target` flag is native to Go; Loki is where the pattern comes from. If the Go
  implementation grows the same targets, the comparison extends to the services topology with
  no change in method. That is a reason to prefer this over a separate "microservices"
  repository, which would have had no counterpart.

---

## Alternatives considered

**A separate repository or long-lived branch for the services version.** Rejected because it
forks the code, the corpus and the docs, has no Go counterpart, and turns every future fix
into two fixes. The earlier suggestion to do this was wrong for those reasons.

**Loki's own choice: one transport, always.** In Loki's single-binary mode the distributor
still talks to the ingester over gRPC on loopback; there is one code path and `all` exercises
the real seam. It is the more rigorous option. It was not chosen here because it changes the
`all` mode's behaviour and numbers, a loopback HTTP call per embedding and per tool call, and
the `all` mode's numbers are the baseline the whole comparison rests on. The topology test is
what covers the remote path instead. If the topology test proves unworkable, this decision
should be revisited, because then the remote path would have no real test at all.

**A gateway, service discovery, a message bus.** None of the seams needs any of them. The
service URLs are three properties; Kubernetes DNS is the discovery; the one thing that looked
like it needed a bus, the `tool` SSE event, does not once the `@Tool` stays home.

**Extracting `chat` into two services, orchestration and streaming.** The streaming path is
where partial-reply persistence, the turn-keyed bus and usage accounting meet. Separating
them is separating the parts that are most tightly coupled by correctness.

**A model-less image for the non-embedding targets.** Deferred, see [Deployment](#deployment).

---

## Plan

Each step is a pull request that leaves `all` mode behaving as before and the suite green.
Estimates are Claude session hours; wall time depends on how sessions are spaced.

| # | Step | Hours | Depends on |
| --- | --- | --- | --- |
| 1 | `app.target`, `@ConditionalOnTarget`, the `EnvironmentPostProcessor`, the boot-time consistency check. `OrderLookup` and `TicketDesk` extracted with only local implementations. No behaviour change. | 2–3 | |
| 2 | `support_ticket` and `conversation_budget` in Postgres, atomic creation, the cleanup of stale budget rows. Two replicas in `all` mode now share the cap and the budget. | 2 | 1 |
| 3 | `orders` and `tickets` targets: controllers, `Http*` clients with their own timeout, the `unavailable` variants, trace propagation asserted. | 3–4 | 1, 2 |
| 4 | `embedding` and `ingest` targets: the `/v1/embeddings` endpoint, the `chat` target on Spring AI's OpenAI embedding client, ingestion as a run-once. | 2–3 | 1 |
| 5 | The topology test, and the CI job. | 2–3 | 3, 4 |
| 6 | `docker-compose.services.yml`, `k8s/services/`, `kind` verification of both layouts, `ComposeEnvironmentTest` extended. | 2–3 | 3, 4 |
| 7 | Documentation: this file rewritten from proposal to record, [deployment.md](deployment.md), the README architecture diagram, [CLAUDE.md](../CLAUDE.md) constraints, the services benchmark next to the `all` one. | 1–2 | 5, 6 |

Roughly 14 to 20 session hours in total. Step 2 is independently worth doing even if the rest
is never built.

Step 4 should wait for the in-flight `claude/ddl-race` branch to land, since both touch what
happens to `vector_store` at startup.

---

## Open questions

For the reconciliation with the independently written design:

1. Is the `@Tool`-stays-in-`chat` decision shared? If the other design moves tool definitions
   into the tool services, it needs a path for descriptions to the prompt and for invocations
   back to the SSE stream, and the two designs differ fundamentally rather than in detail.
2. In-process wiring for `all` with a topology test, or Loki's loopback transport with one
   code path? This document chooses the former and says why; the other choice is defensible.
3. Does the other design keep one database? If it splits them, where does a ticket's link to
   its conversation live?
4. Is anything here a gateway, discovery layer or bus in disguise that this document has
   talked itself out of needing?
5. The topology test in one JVM: has either design a reason to believe it works, beyond
   Spring's context cache existing?
