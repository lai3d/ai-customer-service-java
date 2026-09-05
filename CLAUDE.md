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
./mvnw verify                                   # full suite, no API key needed
./mvnw test -Dtest=FaqRetrievalIntegrationTest  # one class
./mvnw test -Dtest='ClassName#methodName'       # one method

docker compose up -d postgres                   # database only, for running from an IDE
set -a && source .env && set +a && ./mvnw spring-boot:run
docker compose up -d                            # Postgres and the app
COMPOSE_PROFILES=observability docker compose up -d   # plus Tempo, Prometheus, Loki, Alloy, Grafana

./mvnw test -Dexcluded.test.groups= -Dtest='VirtualThreadBenchmark*'   # opt-in benchmark

scripts/verify-services.sh                      # the split as four containers; --down to remove
k8s/kind/verify.sh [--roles [--fit]] [--keep]   # the manifests on a throwaway kind cluster; --fit for a laptop node
```

`./mvnw clean verify` after deleting or renaming a test resource — Maven leaves stale files in
`target/test-classes`, and a stale `application.yml` there silently shadows the real config.

The app serves a demo UI at `/`, actuator at `/actuator/{health,prometheus}`; Grafana is at `:3000`,
Prometheus at `:9090`. Dashboards, alert rules and every backend's config are in `observability/`;
`DashboardMetricsTest` fails if a dashboard references a metric the application does not emit.

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

### Deployment targets and the package map

One artifact runs as everything (`app.target=all`, the default) or as one role: `chat`,
`knowledge` or `ticket`. The design is `docs/adr/001-deployment-targets.md`; do not re-argue
it in code comments. What is built:

```
target/        DeploymentTarget, @ConditionalOnTarget, TargetEnvironmentPostProcessor
chat/ clients/ config/ cost/ orders/ provider/ tools/   the chat role  (ChatRoleConfiguration)
rag/           the knowledge role, package name kept        (KnowledgeRoleConfiguration)
ticket/        the ticket role                              (TicketRoleConfiguration)
rag/api/ ticket/api/   the contracts the chat side may depend on
internal/ observability/   shared by every role, scanned from the application class
```

A single role is a set of property overrides plus a bean condition. `TargetEnvironmentPostProcessor`
sets `spring.ai.model.chat=none`, `spring.ai.model.embedding=none`, `spring.ai.vectorstore.type=none`
and the readiness group per role before auto-configuration runs, because those are switches on
auto-configurations and no bean condition of ours can reach them; a `ticket` process therefore
starts with no LLM key and no ONNX session. `@ConditionalOnTarget(..., exclusive = true)` marks
what exists only when the roles are split: the `/internal/v1/**` controllers in `rag/` and
`ticket/`, the HTTP adapters and the `knowledge` readiness indicator in `clients/`, and the
token filter in `internal/`. An `all` process serves no internal endpoint and needs no token.
`TopologyParityTest` starts all three roles on real ports over one database and is the test
to extend when a seam changes. `docker-compose.services.yml` runs the split as containers
(`scripts/verify-services.sh` asserts it; the Services workflow runs that in CI), and
`k8s/roles` is the split on Kubernetes (`k8s/kind/verify.sh --roles`). `docker-compose.yml`
and `k8s/base` stay the single-process stack and the benchmark baseline.

`CustomerServiceApplication` is deliberately not `@SpringBootApplication`: each role
configuration scans its own packages and is gated on the target, so a `ticket` process never
discovers the chat controller. Adding a package means adding it to a role's
`@ComponentScan(basePackageClasses = ...)`, or it is silently absent.

**The `@Tool` classes stay in `tools/` on the chat side; only their implementations sit behind
`OrderLookup`, `TicketOperations` and `KnowledgeSearch`.** That is what keeps `TurnEventBus`,
the advisor order and the tool-context constraint untouched across topologies. Business
modules return values and know nothing about `ToolContext`, turn events or meters.
`ArchitectureTest` enforces the direction: the chat side may reach `ticket` and `rag` only
through their `api` packages, and those two may not reach the chat side at all. It was checked
to fail on a planted violation before it was trusted.

`app.target` is validated by the same `EnvironmentPostProcessor`, so a misspelt value, or a
`chat` process without `app.services.*.url` and `app.internal.token`, is a one-line failure
naming what is missing. Tests that start the application with a target must pass it as a
command-line argument (`.run("--app.target=...")`), not via `.properties()`: those are default
properties and `application.yml`'s own `app.target` overrides them.

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

Shared state lives in Postgres, never in a process: ticket dedupe and cap (`support_ticket`,
guarded by a `FOR UPDATE` on `conversation_ticket_guard`), spend (`conversation_budget`), one
turn per conversation (`conversation_lease`, overlap is `409`), and which corpus versions are
imported (`corpus_import`, which readiness reads). The classes whose whole job is those rows
are tested against a real Postgres with no Spring context (`MigratedPostgres`), and the
concurrency cases use two instances over one database, which is what two replicas are as far
as the database is concerned.

## Constraints that fail silently

- **Flyway owns the schema** (`db/migration`). Spring AI's `initialize-schema` for pgvector and
  JDBC chat memory must stay off; on, they race the migration. V1 recreates their tables
  `IF NOT EXISTS` and `baseline-on-migrate` adopts a database they already populated.
  Flyway's own Postgres advisory lock is what serialises two replicas starting against a cold
  database; `k8s/kind/verify.sh` asserts no replica lost the `CREATE EXTENSION` race.
- **Every change to a ticket's human side goes through one transaction shape** in
  `JdbcTicketWorkflow`: lock the row (`FOR UPDATE`), compare the version the caller read,
  decide the next state and owner from the current row, write the row and a `ticket_event`,
  read back. The row lock is what makes a claim atomic across replicas; the version check is
  what makes a stale page and a double-submitted form harmless (`TicketConflictException`,
  reload and retry) as opposed to a rule violation (`TicketRuleException`, reloading will not
  help). `ticket_event` is append-only and creation is not an event: the ticket's own
  `created_at` and `ticket_operation` already say how it came to exist. `JdbcTicketWorkflowTest`
  races ten claims over two instances.
- **A turn's operational record (`conversation_turn`) is written at the service boundary,
  and its first row may refuse the turn.** `TurnRecorder.start` runs before the model on
  both paths and throws if it cannot write; nothing after it throws, because by then the
  model call is in flight and bookkeeping must not fail the customer. A row still `running`
  past the turn lease is a process that died mid-turn; `TurnRecordSweeper` marks it
  `unknown`, never `completed`. The blocking path opens a `TurnEventBus` channel of its own
  so retrieval and tool events reach the record there too. Question and answer are
  snapshots, not references into chat memory, which is windowed.
- **Ticket writes over the seam carry an operation id**, generated per tool invocation in
  `SupportTicketTools`, never by the model. `JdbcTicketOperations` records every outcome
  against it in `ticket_operation` inside the ticket's transaction; the same id asked again
  is answered from the record, and reused with different input is a `409`. `HttpTicketOperations`
  tries twice, reads the record once, then returns `TicketResult.unavailable()` -- a value the
  model can act on, never a transport error.
- **A tool result is prompt, so the serialiser is part of the prompt.** Spring AI's default
  converter has no Jackson time module, and `Order`'s two `LocalDate`s were reaching the model
  as `[2026,9,3]` — a customer asking when a parcel arrives was answered from an array of
  integers. It *worked*, because the model inferred year-month-day, which is why nothing
  caught it: every test asserted on the Java object, and a round trip through the same
  serialiser cannot see it. `@Tool(resultConverter = ReadableToolResultConverter.class)` fixes
  it; `ToolResultIsPromptTest` asserts on the literal string **and** that the annotations still
  name the converter. Found by applying a .NET-implementation finding (its serialiser wrote an
  enum as `1` and its model refused to translate a coded status).
- **A `ConditionalOnTarget` must stay a plain `Condition`**, not a `ConfigurationCondition`: a
  phased condition is skipped in the other phase, and a scanned controller is registered in the
  registration phase, so a parse-phase condition on it would silently admit it everywhere.
- **`app.chat.turn-lease` must exceed `spring.http.client.read-timeout`**, or a slow but
  healthy turn loses its conversation to the next request. `ChatPropertiesTest` reads both
  defaults from `application.yml`.
- **`java.time.Instant` cannot be bound as a JDBC parameter** by the Postgres driver; pass
  `Timestamp.from(instant)`. The first import wrote its status row that way and failed every
  startup.
- **Readiness includes the `corpus` indicator.** A context with `app.rag.import-mode=off` and
  an empty database reports readiness (and `/actuator/health`) DOWN, correctly. Tests that
  assert health is UP need `import-mode=startup`.

- **Never set `temperature`, `top_p` or `top_k`.** Every provider's current model rejects the
  value Spring AI seeds: Claude Opus 5 returns HTTP 400 for any of them, GPT-5 accepts only its
  own default. Spring AI seeds one per provider (0.8 / 0.7 / 0.7) in a field initialiser that
  configuration cannot null out. `SeededSamplingParameterStripper` removes the seeded value for
  all three and leaves an explicitly configured one alone.
- **OpenAI reports no usage in a streamed response unless asked.** Without
  `spring.ai.openai.chat.options.stream-usage: true` the budget never triggers and the cost
  meters stay at zero. Anthropic sends it unasked, which is how this hid.
- **Cost metrics key on the model the provider reports, not the one requested.** Asking for
  `gpt-5` yields `model="gpt-5-2025-08-07"`; a price keyed on `gpt-5` silently never matches, so
  tokens are counted and cost stays at zero. `chat_unpriced_model_calls_total{model}` makes that
  visible — a flat cost meter is otherwise indistinguishable from a cheap month.
- **A turn is not a model call — and Spring AI keeps no seam between them.** The second call's
  text is a new assistant message, so concatenating raw persists "for you.Your order". The SSE
  stream carries a `tool` event, so the demo page and `recordAssistantReplyOnInterruption` break
  the paragraph there; the normal completion path is Spring AI's aggregation and cannot be
  reached. Do not repair it from the text — the obvious detector flags every Chinese sentence
  boundary. See `docs/reliability.md`.
- **A turn is not a model call.** Tool-calling turns bill for two, and Spring AI repeats the
  accumulated usage on every streamed chunk *across both calls*, leaving no call boundary in the
  stream. `TurnUsage` reconstructs it: group frames by input count, take each group's largest
  output. Neither "keep the last" nor "add them all" is correct — they fail in opposite
  directions. This is a workaround for Spring AI's abstraction, not a property of the protocols;
  on the wire Anthropic sends usage on two frames per call, OpenAI and xAI on one. Nor is it a
  cost of unified multi-provider abstractions in general — `Microsoft.Extensions.AI` does the
  same job and keeps one usage per call with per-call response ids
  (`dotnet-probe/FINDINGS.md` in the workspace). It is a Spring AI design choice.
- **`CREATE ... IF NOT EXISTS` is not concurrency-safe in Postgres.** It checks the catalogue
  and then inserts, with nothing holding the gap, so two replicas starting together collide on
  `pg_extension_name_index`. That is why every schema statement lives in a Flyway migration
  and Spring AI's initialisers stay off: Flyway holds a Postgres advisory lock for the whole
  migration. An application-level lock (`SchemaInitializationLock`) did this job between the
  race being found on a real cluster and Flyway arriving; it is gone. Nothing with a
  concurrency of one can see the race, which is why the kind harness exists.
- **A pooled `Connection.close()` ends no session.** It returns the connection with its
  advisory locks intact. A test here took a lock on a pooled connection, closed it, and blocked
  the next test for 25 minutes until Hikari retired it at `maxLifetime` — both tests green.
  Anything testing session lifetime needs `DriverManager`, not the pool.
- **CI dies at the sixteenth Spring test context that holds an ONNX session**, and it does
  not look like a test failure: the job step reads "The operation was canceled" with every
  test so far green, reproducibly at the same class, and `./mvnw verify` passes locally. Every
  distinct `@SpringBootTest` configuration (annotations, properties, imports) is one cached
  context, and an `all`-target context loads the embedding model. A new integration test must
  reuse an existing configuration exactly (a `@MockitoBean` makes a context unique too;
  `CustomerServiceApplicationTests` is the mock-free one with a real port) and set up its
  data through beans rather than through properties of its own. Found on PR #22.
- **Test config goes in `application-test.yml` with `@ActiveProfiles("test")`.** An
  `application.yml` on the test classpath replaces the main one wholesale rather than merging.
- **Add `@AutoConfigureObservability`** to any test asserting on metrics; `@SpringBootTest`
  disables metrics export by default.
- **Conversation ids are capped at 36 characters** — Spring AI's chat memory schema declares
  `varchar(36)`. Longer ones used to surface as a 500.
- **Adding a chat provider starter** makes `spring.ai.model.chat` mandatory and drags in
  speech/image/moderation auto-configurations that fail without their own keys; see
  `spring.ai.model.*` in `application.yml`.
- **xAI is registered as its own provider** (`spring.ai.model.chat=xai`, `spring.ai.xai.*`) by
  `XaiChatConfig`, which reuses `OpenAiChatModel` because xAI speaks that protocol. Do not
  "support Grok" by putting its key in `OPENAI_API_KEY` with a base-URL override.
- **`spring.lifecycle.timeout-per-shutdown-phase` (30s) must stay below the pod's
  `terminationGracePeriodSeconds` (45s)** in `k8s/base/deployment.yaml`.
- Spring's test context cache keeps multiple servers alive at once; benchmarks that count
  threads need `@DirtiesContext`.
- **Compose does not inject undeclared variables into a container.** Anything documented in
  `.env.example` must be listed in the app service's `environment:` or it silently does nothing;
  `ComposeEnvironmentTest` asserts that. Never dump `docker compose config` output verbatim — it
  interpolates real secrets from the shell.

## Where the reasoning lives

`docs/` holds one document per decision, each stating the evidence: `retrieval.md`,
`reliability.md`, `benchmark.md`, `tools.md`, `observability.md`, `providers.md`,
`demo-ui.md`, `deployment.md`, `operations-admin.md` (the record of what was built first, the
proposal it departs from second). The README's findings table links into them. When you change a
measured value, update the measurement, not just the number.

## Verified against the live API

Confirmed against `claude-opus-5`, `gpt-5`, `gemini-3.8-flash` and `grok-4.6`:

- Requests are accepted with no sampling parameters.
- Chinese questions retrieve Chinese passages and are answered in Chinese from the corpus.
- Tool calling round-trips on both providers; results reach the answer.
- Real usage reaches the budget, the meters and the spans.
- Traces arrive in Tempo with `gen_ai.usage.*` and per-tool spans; one 3517 ms turn was
  3498 ms of `chat claude-opus-5`.
- Grounding holds: asked something the corpus does not cover, the model says so.

**Known gap:** one of fourteen multi-intent questions still misses the passage that answers it;
see `docs/retrieval.md`.

### Staff login for the operations admin

`/admin/**` is the operations admin (`admin/`, on the chat side) behind Spring Security form
login with staff accounts in `staff_account` (bcrypt) and two roles, `admin` and `support`.
It is **staff** authentication for a page that shows customer conversations; it is not
customer authentication. The filter chain is bound to `/admin/**` with `securityMatcher`, so
the public chat endpoints, the demo page, the actuator and `/internal/**` never pass through
Spring Security; `AdminLoginTest` asserts that. Sessions are Spring Session JDBC rows
(`spring_session`, Flyway-owned, initialiser off) because the chat role runs as replicas.
CSRF tokens travel in a readable `XSRF-TOKEN` cookie and come back as `X-XSRF-TOKEN` or
`_csrf`. `knowledge` and `ticket` processes exclude the security and session
auto-configurations outright (`TargetEnvironmentPostProcessor`); left on, Boot's default
chain would put a generated password in front of `/internal/**`. The first admin is seeded
by `ADMIN_SEED_USERNAME`/`ADMIN_SEED_PASSWORD`, only into an empty table.

The ticket loop is `/admin/api/tickets` over `TicketWorkflow` (`ticket/api`): local
`JdbcTicketWorkflow` in `all`, `HttpTicketWorkflow` over `/internal/v1/ticket-workflow` in a
`chat` process; `TopologyParityTest` proves the two answer alike, refusals included. The
conversation view reads `spring_ai_chat_memory` directly and says what is not persisted
(retrieval evidence, tool results) instead of showing an empty panel. `admin_audit` records
two things `ticket_event` deliberately does not: opening a customer conversation (the one
page that shows customer text on purpose) and a refused action (a rule the workflow would not
bend, or a role the server would not honour). A lost race (`409`) is not a refusal and is not
recorded.

## Scope

Do not add customer authentication or multi-tenancy without asking; the bearer token on
`/internal/**` is service-to-service, the staff login on `/admin/**` is for staff, and those
two are the whole of what exists. Do not introduce LangChain4j, and do
not hand-roll vector retrieval — that belongs to `QuestionAnswerAdvisor`. Check Spring AI's
actual classes or configuration metadata rather than recalling its API; the naming changed
repeatedly before 1.0.
