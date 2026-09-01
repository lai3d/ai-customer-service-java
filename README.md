# AI Customer Service System — Java / Spring AI

[![CI](https://github.com/lai3d/ai-customer-service-java/actions/workflows/ci.yml/badge.svg)](https://github.com/lai3d/ai-customer-service-java/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

The Java implementation of an AI customer service backend, built on **Spring Boot 3.5**,
**Spring AI 1.1**, and **Anthropic Claude**, with retrieval-augmented answers over an FAQ corpus, tool calling for real
business actions, SSE streaming, and first-class observability.

This is not a notebook demo. It runs on virtual threads, persists conversation memory and vectors in
the same Postgres instance, exports Prometheus metrics for every model call, and ships with a
Dockerfile and Kubernetes manifests.

> **Status:** Phase 1, under active development. See [Roadmap](#roadmap).

---

## Architecture

```mermaid
flowchart LR
    Client["Client"]
    Ctl["ChatController<br/>SSE · conversation ids"]
    Svc["ChatService<br/>partial-reply persistence"]
    CC["ChatClient"]

    subgraph Chain["Advisor chain"]
        direction TB
        Mem["MessageChatMemoryAdvisor"]
        QA["QuestionAnswerAdvisor"]
        Mem --> QA
    end

    Claude["Chat model<br/>Claude · GPT · Gemini · Grok"]
    Tools["@Tool<br/>lookup_order_status<br/>create_support_ticket"]

    subgraph PG["Postgres · one instance"]
        direction TB
        CM[("spring_ai_chat_memory")]
        VS[("vector_store")]
    end

    Ingest["FaqIngestionService<br/>replace-on-boot"]
    Corpus[/"faq.json<br/>18 entries"/]
    Embed["ONNX multilingual-e5-small<br/>in-process · 384-dim · en + zh"]
    Prom["/actuator/prometheus<br/>model calls · stream outcomes"]
    Jaeger["Jaeger<br/>OTLP spans"]

    Client -->|"POST /api/v1/chat<br/>POST /api/v1/chat/stream"| Ctl
    Ctl --> Svc
    Svc --> CC
    CC --> Chain
    Chain --> Claude
    Claude -.->|"tool_use"| Tools
    Tools -.-> Claude

    Mem --> CM
    QA --> VS
    Corpus --> Ingest
    Ingest --> Embed
    Embed --> VS
    Svc -.->|"partial reply<br/>on disconnect"| CM
    Svc -.-> Prom
    CC -.-> Prom
    CC -.->|"OTLP"| Jaeger

```


### A streaming turn

The interesting part is what happens when a client disconnects mid-answer. Spring AI writes
the user message to memory up front but writes the assistant message from an aggregator that
only hooks `doOnComplete` — so an interrupted stream would leave an orphaned user message
behind, and the next turn would send two consecutive user messages to the model.

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant Ctl as ChatController
    participant S as ChatService
    participant A as MessageChatMemoryAdvisor
    participant M as ChatMemory
    participant L as Claude

    C->>Ctl: POST /api/v1/chat/stream
    Ctl->>S: stream(conversationId, message)
    S->>A: subscribe

    A->>M: get(conversationId)
    M-->>A: prior messages
    A->>M: add(user message)
    A->>L: streaming request

    loop per token
        L-->>A: token
        A-->>S: token
        S->>S: buffer token
        S-->>Ctl: token
        Ctl-->>C: event: message
    end

    alt stream completes
        A->>M: add(assistant message)
        S->>S: count outcome=completed
    else client disconnects
        Note over A,M: aggregator hooks doOnComplete only,<br/>so nothing is written here
        S->>M: add(buffered partial reply)
        S->>S: count outcome=cancelled
        Note over C,Ctl: nothing can be sent —<br/>the client is already gone
    else upstream fails
        S->>M: add(buffered partial reply)
        S->>S: count outcome=failed
        Ctl-->>C: event: error
    end
```

**Why these pieces:**

| Decision | Reason |
| --- | --- |
| Virtual threads, no WebFlux | LLM calls are I/O-bound and long-lived. Loom gives the concurrency without forcing a reactive programming model on the whole codebase. `Flux` appears only as an SSE controller return type. |
| Advisor chain, never hand-built prompts | Memory and retrieval are cross-cutting concerns. Composing them as advisors keeps them testable and independently switchable. |
| pgvector in the business database | One database to run, back up, and reason about. Transactional consistency between a ticket and the conversation that created it comes for free. |
| Local ONNX embeddings | Anthropic offers no embedding API. An in-process ONNX model (`multilingual-e5-small`, 384-dim) means the RAG path needs no second vendor, no second API key, and costs nothing per query — and it handles English and Chinese. |
| Micrometer on every model call | Token spend and latency are the two numbers that decide whether an LLM feature survives contact with production. |

---

## Tech Stack

| Layer | Choice |
| --- | --- |
| Runtime | JDK 21, virtual threads (`spring.threads.virtual.enabled=true`) |
| Framework | Spring Boot 3.5.16, Spring MVC |
| AI | Spring AI 1.1.8 — `ChatClient` + advisor chain |
| Chat model | Anthropic Claude (`claude-opus-5` by default) |
| Embeddings | Spring AI Transformers — `multilingual-e5-small` ONNX, in-process |
| Vector store | pgvector |
| Memory | Spring AI JDBC chat memory repository |
| Observability | Actuator + Micrometer → Prometheus; Micrometer Tracing → OTLP → Jaeger |
| Build | Maven (wrapper included) |
| Tests | JUnit 5 + Testcontainers |

Spring AI 2.0 exists but targets Spring Boot 4.x. This project stays on the
Spring Boot 3.5 / Spring AI 1.1 line, which is the combination Spring AI 1.1.8 is built and tested
against.

---

## Quick Start

### Everything in containers

**Prerequisites:** Docker. Nothing else — no JDK, no Maven.

```bash
cp .env.example .env
$EDITOR .env               # set ANTHROPIC_API_KEY

docker compose up -d       # Postgres, then the app once Postgres is healthy
curl -s localhost:8080/actuator/health | jq
open http://localhost:8080         # the demo UI
open http://localhost:16686        # Jaeger: traces for every chat turn
```

The image bakes in the embedding model, so a cold start downloads nothing at runtime and
reaches ready in a few seconds. See [`docs/deployment.md`](docs/deployment.md) for the image
layout, the Kubernetes manifests in [`k8s/`](k8s/README.md), and why the model is baked in
rather than mounted.

### Running the app from your IDE

**Prerequisites:** JDK 21, Docker. Maven is not required — use the bundled wrapper.

```bash
docker compose up -d postgres      # just the database
set -a && source .env && set +a
./mvnw spring-boot:run
```

Verify:

```bash
curl -s localhost:8080/actuator/health | jq
curl -s localhost:8080/actuator/prometheus | grep -E '^gen_ai|^chat_'
```

Run the tests — Testcontainers starts its own Postgres, and nothing reaches the Anthropic API,
so no key is needed:

```bash
./mvnw verify
```

Starting without `ANTHROPIC_API_KEY` fails immediately and says so. That is deliberate: Spring's
binder ignores an unresolvable placeholder, so without an explicit check the application would
start, report itself healthy, be marked ready by Kubernetes, and then fail every customer
request with a 401.

---

## API

Both endpoints take the same body. Omit `conversationId` to start a new conversation; the
assigned id comes back in the `X-Conversation-Id` header of every response.

```bash
# Blocking: one JSON response
curl -sS localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "Where is my order?"}' | jq

# Streaming: server-sent events
curl -N localhost:8080/api/v1/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"conversationId": "abc-123", "message": "And the second one?"}'
```

The stream emits two event types. Tokens arrive as `event: message`; a failure after the
response has been committed arrives as a terminal `event: error`, so a client never has to
guess whether an apology came from the model or from the transport.

```
event:message
data:Your order

event:message
data: shipped on Monday.
```

| Failure | Response |
| --- | --- |
| Blank or oversized message | `400` before any model call |
| Rate limited or provider overloaded | `503` with a `ProblemDetail` body — retry is worthwhile |
| Bad credentials, rejected request | `502` with a `ProblemDetail` body — retry is not |
| Failure after streaming began | `200`, terminated by an `error` event |

---

## Retrieval

The FAQ corpus lives in [`src/main/resources/faq/faq.json`](src/main/resources/faq/faq.json) —
18 entries across returns, shipping, payment, account, and support, each written in English and
Chinese. Every language becomes its own document, so 36 in total. **It is sample data.** Replace
it before this answers anything real.

Ingestion runs at startup and *replaces* what it wrote last time rather than appending.
Duplicates do not merely waste space: they crowd out distinct passages in the top-k window, so
the model sees one answer four times instead of four different ones.

No text splitter, deliberately. An FAQ entry is already the unit a customer's question should
match, and splitting one would separate a question from its answer. Long-form policy documents
would need one.

### Choosing an embedding model, by measurement

Three models were tried. The first two were rejected on data, and both rejections are more
interesting than the final choice.

**`all-MiniLM-L6-v2`** — the original. Clean separation on English: correct answers scored 0.34
to 0.63 against paraphrased questions, unrelated questions peaked at 0.11. It is also
English-only, so a Chinese corpus was never going to work.

**`paraphrase-multilingual-MiniLM-L12-v2`** — rejected. Chinese retrieval mostly worked, but a
colloquial damage report (*包裹到的时候是坏的*) scored **0.21** against its own answer while an
unrelated question scored **0.14**. No threshold exists that keeps one and rejects the other,
and the failure lands on exactly the customer you least want to fail. The cause is the model
class: `paraphrase-*` models are trained for *symmetric* similarity — is sentence A like
sentence B — while retrieval is *asymmetric*: does this short colloquial query match this long
written passage. It also regressed English, demoting `returns-window` to third place on a
question the previous model got right.

**`multilingual-e5-small`** — chosen. The e5 family is retrieval-trained. Same 384 dimensions,
so the pgvector column did not change. **20 of 20 paraphrased questions, ten English and ten
Chinese, now retrieve the correct entry first**, and the damage report went from 0.21 to 0.89.

e5 requires asymmetric input markers — `query: ` before a search query, `passage: ` before an
indexed document. These are part of the model contract, not decoration, and applying them to
only one side is worse than applying neither.
[`PrefixingEmbeddingModel`](src/main/java/dev/merlionos/customerservice/rag/PrefixingEmbeddingModel.java)
wraps the embedding model, because the vector store already separates the two cases for us: it
embeds through `embed(List<Document>, …)` when writing and `embed(String)` when searching.
Nothing above that class knows the convention exists.

### The threshold stopped working, and that is the finding

With the English-only model, `similarity-threshold` was a genuine relevance filter sitting in
open space between two well-separated populations. e5 compresses cosine similarity into a
narrow high band, and across 30 queries the two populations nearly touch:

| | n | min | max |
| --- | --- | --- | --- |
| Relevant questions (en + zh) | 20 | **0.8378** | 0.9337 |
| Off-topic questions (en + zh) | 10 | 0.6977 | **0.8318** |

A margin of **0.006** is noise, not signal. Tuning the threshold to 0.835 would fit these 30
queries and break on the 31st.

So relevance filtering moved out of the retriever and into the prompt. The threshold is now a
floor for degenerate input; the system prompt tells the model that reference material is
selected by similarity, that some of it will be unrelated, and to say so rather than stretch an
unrelated passage to fit. Ranking is what the retriever is good at, and it is good at it: 20 of
20.

This is worth stating plainly because the opposite is a common failure — porting a threshold
across an embedding-model change and never noticing it stopped meaning anything.

### Cross-lingual retrieval

Because both languages are indexed, a Chinese question matches a Chinese passage; same-language
matches score high enough that all eighteen Chinese passages outrank every English one. To
verify that cross-lingual retrieval works *at all* — which is what matters for an entry nobody
has translated yet — the test isolates the English half with a metadata filter and asks in
Chinese. Four for four.

`FaqRetrievalIntegrationTest` runs all of the above on every build, against real pgvector and
the real ONNX model, with no API key. A retrieval regression is a red build, not vaguer answers
in production.

---

## Tools

The model can call two tools. Both are mock implementations: the point of Phase 1 is the
calling contract, not an order system.

| Tool | What it does |
| --- | --- |
| `lookup_order_status` | Reads one order by number. Case- and whitespace-tolerant, because customers paste order numbers out of emails. |
| `create_support_ticket` | Raises a ticket for a human agent, attributed to the conversation it came from. |

**Tool descriptions are prompt, not documentation.** They are the entire basis on which the
model decides whether to call a tool instead of answering from retrieved FAQ text, so they say
what each tool is *not* for as well as what it is for. `ToolDefinitionTest` asserts on the
generated JSON schema — names, descriptions, and which parameters are required — because a
rename or a dropped description changes model behaviour without changing anything else a test
would notice.

### Three decisions worth calling out

**A missing order is a value, not an exception.** Spring AI's default behaviour on a thrown
tool exception is to feed the exception's *message* back to the model as the tool result. Throw
on a not-found order and you have put an internal error string in front of a customer, and
given the model nothing to reason about. `lookup_order_status` returns `found: false` with a
plain explanation, so the assistant can ask the customer to check the number.

**Anything that does still throw is scrubbed.** A `ToolExecutionExceptionProcessor` bean
replaces the exception message with a fixed instruction and logs the real one. Otherwise a
connection string or an internal id in some future exception becomes something the assistant
can repeat to a customer.

**Ticket creation is idempotent per conversation.** A model can call the same tool twice in one
turn, and a retried request replays the conversation. Without a guard, one frustrated customer
becomes three tickets in the human agents' queue. Asking twice returns the existing ticket
flagged `alreadyExisted`, so the assistant says "I've already raised that" rather than inventing
a second reference number.

### Conversation identity

`create_support_ticket` takes a Spring AI `ToolContext` parameter — excluded from the JSON
schema, so the model never sees it — through which the service passes the conversation id. That
is what links a ticket back to the conversation that produced it.

It also creates an implicit contract with teeth: Spring AI rejects a call to a
`ToolContext`-taking tool when the context is empty, *before* the tool body runs. A code path
that reaches the model without setting it breaks ticket creation, and breaks it only once a
conversation has escalated far enough for the model to try. `ChatServiceToolContextTest` checks
both entry points instead of waiting for that.

---

## Observability

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
[custom observation convention](src/main/java/dev/merlionos/customerservice/observability/PrivacyPreservingVectorStoreObservationConvention.java)
drops it unless `app.observability.include-query-content` is deliberately switched on for
debugging. Everything that makes the span useful — top-k, threshold, similarity metric,
dimensions, timing — is kept.

---

## Swapping the chat provider

The provider is configuration, not code. Everything around the model — the advisor chain,
conversation memory, retrieval, both tools, SSE streaming, the metrics and spans — is written
against Spring AI's `ChatModel` interface, so nothing in `src/main/java` changes.

```bash
CHAT_PROVIDER=anthropic                      # default
CHAT_PROVIDER=openai       OPENAI_API_KEY=…
CHAT_PROVIDER=google-genai GEMINI_API_KEY=…

# Grok: xAI has no Spring AI starter, but its API is OpenAI-compatible
CHAT_PROVIDER=openai OPENAI_API_KEY=<xAI key> \
  OPENAI_BASE_URL=https://api.x.ai OPENAI_CHAT_MODEL=<grok model>
```

`ChatProviderSwitchingTest` boots the real context under each of the three providers and checks
that exactly one chat model is built and the rest of the wiring is unchanged — because
"provider-agnostic" is the kind of claim a README makes and nobody verifies.

### Two things that bite when you add the second starter

**`spring.ai.model.chat` stops being optional.** Every provider's auto-configuration is
`@ConditionalOnProperty(… matchIfMissing = true)`, so with the property unset they *all* build a
`ChatModel`, and `ChatClientAutoConfiguration` injects `ChatModel` directly with no
`@ConditionalOnSingleCandidate`. The result is `NoUniqueBeanDefinitionException` at startup —
loud and immediate, which is the good version of this problem.

**A provider starter brings every model type that provider supports.** The OpenAI starter adds
speech, transcription, image, and moderation auto-configurations, each of which builds by
default and throws for want of an API key. `spring.ai.model.audio.speech: none` and its
siblings are not tidying — without them the application does not start.

### What this does not claim

Nothing here calls three APIs and compares them. The abstraction covers the request shape;
tool-call reliability, streaming chunk granularity, and how each provider treats a system prompt
differ in ways only live traffic reveals. A cross-provider contract test would need three sets
of credentials and would cost money on every run, so it does not belong in CI. Grok in
particular rides on a compatibility layer maintained by xAI, not on first-class support.

Claude remains the default. The sampling-parameter workaround in
[`AnthropicSamplingParameterStripper`](src/main/java/dev/merlionos/customerservice/config/AnthropicSamplingParameterStripper.java)
is the only provider-specific code, and it stays inert under the others.

---

## The demo UI

`docker compose up` serves a single-page demo at **http://localhost:8080** — one HTML file, no
build step, no `npm install`.

It is deliberately not a chat widget. A widget's job is to make the AI feel seamless and
invisible; this repository's substance *is* the invisible part. So the page is a glass box:
conversation on the left, and on the right, for every turn, the passages retrieval found with
their scores, the tools the model called, the tokens spent, and a link to that turn's trace in
Jaeger.

That required real work in the backend, not just a page. The stream now carries typed events —
`retrieval`, `tool`, `message`, `usage`, `error` — instead of bare tokens:

```
event:retrieval
data:{"passages":[{"entryId":"shipping-cost","language":"zh","score":0.934}, …]}

event:tool
data:{"tool":"lookup_order_status","outcome":"found"}

event:message
data:{"text":"运费"}

event:usage
data:{"inputTokens":1204,"outputTokens":87,"millis":2140,"traceId":"a3f…"}
```

A production widget would read `message` and `error` and ignore the rest.

### Two things the UI forced the backend to get right

**Retrieval is reported before the model is called.** Reading the retrieved documents off the
streamed response was the obvious approach and it was wrong twice: the passages only appeared
once the first token did, so nothing could be shown while the model was still thinking; and when
the model call failed, no response was ever emitted and the retrieval looked like it had never
happened — which is exactly when someone debugging a bad answer most needs to see it.
[`RetrievalReportingAdvisor`](src/main/java/dev/merlionos/customerservice/chat/RetrievalReportingAdvisor.java)
sits after `QuestionAnswerAdvisor` and publishes what it just retrieved, before the model call.

**Tool calls needed a way out of the model call.** Spring AI executes tools inside the chat call
on its own scheduler, with no return path to the controller other than the model's eventual
answer, so a tool invocation is invisible until the assistant happens to mention it.
[`TurnEventBus`](src/main/java/dev/merlionos/customerservice/chat/TurnEventBus.java) keys a sink
by conversation id — which tools already receive through `ToolContext`.

### Honesty in the visualisation

Score bars are normalised within each result set, not drawn from the raw score. e5 compresses
cosine similarity into a narrow high band, so absolute widths made every bar look full and
conveyed nothing; relative widths show ranking, which is the part that is reliable — the same
finding that moved relevance filtering out of the threshold and into the prompt.

The retrieval card shows no duration, because retrieval happens inside `QuestionAnswerAdvisor`,
upstream of anything that could time it honestly. The first version read `0ms`. The real timing
is on the `pg_vector query` span in the trace.

---

## Roadmap

Phase 1 is built one item at a time, each landing as a reviewable change.

- [x] **0 · Foundation** — project skeleton, Postgres + pgvector via Compose, actuator/Prometheus, CI
- [x] **1 · Conversational core** — single- and multi-turn chat over SSE, conversation memory
- [x] **2 · RAG** — FAQ ingestion pipeline and grounded answers, with retrieval quality under test
- [x] **3 · Tool calling** — order status lookup and support ticket creation
- [x] **4 · Deployment** — Dockerfile, one-command Docker Compose stack, Kubernetes manifests
- [x] **5 · Bilingual retrieval** — Chinese corpus, multilingual embeddings, cross-lingual tests
- [x] **6 · Tracing** — OpenTelemetry spans over OTLP to Jaeger, with customer messages excluded
- [x] **7 · Multi-provider** — Anthropic, OpenAI, Gemini, and OpenAI-compatible APIs by configuration
- [x] **8 · Demo UI** — a glass-box page showing retrieval, tool calls and token cost per turn

Deliberately out of scope for Phase 1: authentication, multi-tenancy, and MCP.

---

## Project Layout

```
├── Dockerfile               # 3 stages; embedding model baked in, no runtime downloads
├── docker-compose.yml       # full stack, or `up -d postgres` for IDE development
├── docker/postgres/init/    # extensions created before the app connects
├── docs/deployment.md       # image layout, Kubernetes, environment
├── k8s/                     # Namespace, ConfigMap, Deployment, Service, Secret template
├── src/main/java/dev/merlionos/customerservice/
│   ├── CustomerServiceApplication.java
│   └── config/              # explicit overrides of Spring AI defaults
├── src/main/resources/
└── src/test/java/           # Testcontainers-backed integration tests
```

This repository is one of a planned pair — a Go implementation of the same system lives in
a separate repository. Nothing is shared between them by design; each is idiomatic for its
own ecosystem.

---

## License

[Apache License 2.0](LICENSE)
