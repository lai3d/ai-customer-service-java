# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Toolchain

There is no system JDK on this machine — `/usr/bin/java` exists but fails with "Unable to locate
a Java Runtime". Export this before any Maven command or every build fails confusingly:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
```

Docker must be running: every integration test starts a real `pgvector/pgvector:pg17` via
Testcontainers.

## Commands

```bash
./mvnw verify                                   # full suite (~115 tests, no API key needed)
./mvnw test -Dtest=FaqRetrievalIntegrationTest  # one class
./mvnw test -Dtest='ClassName#methodName'       # one method

docker compose up -d postgres                   # database only, for running from an IDE
set -a && source .env && set +a && ./mvnw spring-boot:run
docker compose up -d                            # full stack: Postgres, Jaeger, the app

./mvnw test -Dexcluded.test.groups= -Dtest='VirtualThreadBenchmark*'   # opt-in benchmark
```

`./mvnw clean verify` after deleting or renaming a test resource — Maven leaves stale files in
`target/test-classes`, and a stale `application.yml` there silently shadows the real config.

The app serves a demo UI at `/`, actuator at `/actuator/{health,prometheus}`, Jaeger at `:16686`.

## Architecture

A Spring MVC application on virtual threads. `Flux` appears only as an SSE controller return
type; do not introduce WebFlux. All LLM interaction goes through `ChatClient` and its advisor
chain — never hand-build a prompt string.

One turn, in order:

```
ChatController → ChatService → ChatClient
                                  ├─ MessageChatMemoryAdvisor   (order MIN+1000)
                                  ├─ QuestionAnswerAdvisor      (order 0)
                                  ├─ RetrievalReportingAdvisor  (order 100)
                                  └─ chat model → @Tool calls
```

**Advisor order is a correctness constraint, not a style choice.** `QuestionAnswerAdvisor`
rewrites the user message to carry retrieved passages; `MessageChatMemoryAdvisor` stores
whatever user message it is handed. Reversed, every retrieved passage is written into the
customer's history and re-sent on every later turn, inflating prompts silently. Spring AI's
defaults happen to be right; `AdvisorChainOrderTest` fails if that changes.

### Streaming carries typed events, not tokens

`ChatService.stream` returns `Flux<TurnEvent>` — `retrieval`, `tool`, `message`, `usage`,
`error` — which the controller maps to named SSE events. Two things reach that stream from
places with no direct return path:

- **Retrieval** is published by `RetrievalReportingAdvisor` *before the model is called*, so it
  arrives while the model is still thinking and survives a failed model call.
- **Tool calls** publish through `TurnEventBus`, a sink keyed by **turn**, not by conversation.
  Spring AI runs tools inside the chat call on its own scheduler; there is no other way back to
  the controller. Keying by conversation let two overlapping turns orphan each other's stream —
  see `TurnEventBusConcurrencyTest`. Tools read both ids from Spring AI's `ToolContext`.

A tool that declares a `ToolContext` parameter **fails outright when the context is empty**, so
every path reaching the model must call `.toolContext(...)` with both the conversation id and
the turn id. `ChatServiceToolContextTest` pins both paths.

### Retrieval

`multilingual-e5-small` ONNX, in-process, 384 dimensions, wrapped by `PrefixingEmbeddingModel`
to apply the `query: ` / `passage: ` markers e5 is trained with. The corpus
(`src/main/resources/faq/faq.json`) is bilingual; each language becomes its own document, and
ingestion replaces rather than appends.

`app.rag.similarity-threshold` is a **floor for degenerate input, not a relevance filter** —
with e5 the relevant and off-topic score distributions are 0.006 apart. Relevance judgement
lives in the system prompt. If you change the embedding model, re-measure: the threshold, the
dimensions in `spring.ai.vectorstore.pgvector`, and the corpus embeddings all move together.

### Failure and cost

Tool failures are **values, not exceptions** (`OrderLookupResult`, `TicketResult`). Spring AI
feeds a thrown tool exception's message back to the model, so an exception puts an internal
string in front of a customer; `ToolExecutionExceptionProcessor` in `ChatClientConfig` scrubs
whatever still throws. `ConversationBudget` caps tokens per conversation and meters spend **by
model, never by conversation id** — per-conversation tags are unbounded cardinality.

## Constraints that fail silently

- **Never set `temperature`, `top_p` or `top_k`.** Claude Opus 5 and Sonnet 5 reject them with
  HTTP 400, and Spring AI seeds a default that configuration cannot null out —
  `AnthropicSamplingParameterStripper` removes it, `AnthropicChatOptionsTest` guards it.
- **Test config goes in `application-test.yml` with `@ActiveProfiles("test")`.** An
  `application.yml` on the test classpath replaces the main one wholesale rather than merging.
- **Add `@AutoConfigureObservability`** to any test asserting on metrics; `@SpringBootTest`
  disables metrics export by default.
- **Conversation ids are capped at 36 characters** — Spring AI's chat memory schema declares
  `varchar(36)`. Longer ones used to surface as a 500.
- **Adding a chat provider starter** makes `spring.ai.model.chat` mandatory and drags in
  speech/image/moderation auto-configurations that fail without their own keys; see
  `spring.ai.model.*` in `application.yml`.
- **`spring.lifecycle.timeout-per-shutdown-phase` (30s) must stay below the pod's
  `terminationGracePeriodSeconds` (45s)** in `k8s/deployment.yaml`.
- Spring's test context cache keeps multiple servers alive at once; benchmarks that count
  threads need `@DirtiesContext`.
- **Compose does not inject undeclared variables into a container.** Anything documented in
  `.env.example` must be listed in the app service's `environment:` or it silently does nothing;
  `ComposeEnvironmentTest` asserts that. Never dump `docker compose config` output verbatim — it
  interpolates real secrets from the shell.

## Where the reasoning lives

`docs/` holds one document per decision, each stating the evidence: `retrieval.md`,
`reliability.md`, `benchmark.md`, `tools.md`, `observability.md`, `providers.md`,
`demo-ui.md`, `deployment.md`. The README's findings table links into them. When you change a
measured value, update the measurement, not just the number.

## Not yet verified

**No real Anthropic API key has ever been used against this code.** Every model interaction in
the test suite is stubbed or returns 401. Retrieval quality, the event stream, tool logic,
budgets, the benchmark and tracing are all verified; a live model answering a question, calling
a tool, and reporting real token usage is not. Do not describe those as working.

## Scope

Do not add authentication or multi-tenancy without asking. Do not introduce LangChain4j, and do
not hand-roll vector retrieval — that belongs to `QuestionAnswerAdvisor`. Check Spring AI's
actual classes or configuration metadata rather than recalling its API; the naming changed
repeatedly before 1.0.
