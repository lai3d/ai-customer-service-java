# ADR 001: One artifact, four deployment targets

- **Status:** decided. The [addendum](#addendum-2026-09-05-codexs-revised-position-and-the-owners-rulings)
  records Codex's revised position after reading both proposals, the four points on which
  the two authors still differed, and the owner's rulings on them. The three items marked
  *owner's call* were confirmed on 2026-09-05.
- **Date:** 2026-09-05
- **Inputs:** two independently written proposals for the same change
  - [Codex: Dual Deployment Design](../CODEX_DUAL_DEPLOYMENT_DESIGN.md), merged as PR #2
  - [Claude: One process or several](../dual-target.md), merged as PR #3
  - [Codex: Dual Deployment Decision](../CODEX_DUAL_DEPLOYMENT_DECISION.md), Codex's revised
    position after reading both, merged as PR #5
- **Reconciled by:** Claude Code, after reading both. Where the two disagreed this document
  says which position was taken and why. Neither original is edited; both remain the
  record of what each author proposed before seeing the other.

---

## Contents

- [Context](#context)
- [Decision in one paragraph](#decision-in-one-paragraph)
- [Where the proposals agreed](#where-the-proposals-agreed)
- [Where they disagreed, and what was decided](#where-they-disagreed-and-what-was-decided)
- [Owner's calls](#owners-calls)
- [What changes in `all` mode](#what-changes-in-all-mode)
- [Deferred, deliberately](#deferred-deliberately)
- [Plan](#plan)
- [Answers to both cross-review agendas](#answers-to-both-cross-review-agendas)
- [Addendum, 2026-09-05: Codex's revised position and the owner's rulings](#addendum-2026-09-05-codexs-revised-position-and-the-owners-rulings)

---

## Context

The service is one Spring Boot process. Its Kubernetes manifest runs two replicas, and three
pieces of state are per replica: the conversation token budget, the ticket deduplication
table and the mock order data. The question "should this be microservices" was asked on
2026-09-05; both proposals answered that the real questions are whether the process can run
as several replicas correctly, and which parts of a turn belong behind a network boundary.
Both answered the second the same way: the business systems, not the turn.

The constraint every decision below respects: the `all` target is the baseline that the
[benchmark](../benchmark.md) and the [Go comparison](../../README.md#the-same-system-in-go)
rest on. Its turn pipeline, retrieval and benchmark methodology do not change. Two
correctness fixes for multi-replica operation do apply to it, and are listed under
[What changes in `all` mode](#what-changes-in-all-mode).

---

## Decision in one paragraph

One Maven module, one jar, one container image. `app.target` selects `all`, `chat`,
`knowledge` or `ticket`; `all` is the default and is today's process plus the two fixes
below. Role composition is explicit configuration imports gated by a `@ConditionalOnTarget`
condition, not package scanning, with the Spring AI auto-configuration switches set per target
before auto-configuration runs, and an ArchUnit test enforces that `chat` depends on
`knowledge` and `ticket` only through their interfaces. `chat` keeps the API, SSE,
`TurnEventBus`, advisor chain, `@Tool` adapters, memory and budget. `knowledge` owns
embedding, search, the corpus and its import. `ticket` owns ticket persistence,
deduplication and the cap. Order lookup stays a local mock behind an `OrderLookup`
interface. One Postgres, one schema per component, Flyway for migrations. Retrieval reaches
`knowledge` through a search-only `VectorStore` adapter so the advisor chain is untouched.
Tool failures over the network are values; a ticket write carries an operation id and is
recovered after an ambiguous timeout. Internal endpoints require a service token. Each
conversation admits one active turn.

---

## Where the proposals agreed

Adopted without discussion, since both authors reached them independently:

| Point | Both proposals |
| --- | --- |
| Packaging | One codebase, one executable, initially one image; `app.target` selects the role; `all` is the default and keeps the current workflow |
| Loki | Borrowed for packaging and composition only, not storage, replication or discovery |
| Seams | One interface per boundary, a local implementation or an HTTP adapter, exactly one active |
| Tool adapters | The `@Tool` classes stay in `chat`; business modules never publish `TurnEvent` or see `ToolContext`; the adapter turns results into SSE events |
| Streaming | SSE, `TurnEventBus`, partial-reply persistence, cancellation and usage aggregation stay together in `chat` |
| Infrastructure | No gateway, registry, message broker or service mesh |
| Database | One Postgres instance, one schema per component |
| Knowledge failure | A typed failure, never an empty retrieval; `chat` fails the turn rather than answer ungrounded |
| Deployment | The existing Compose file stays; a separate, explicitly selected distributed Compose file; Kubernetes overlays; the public Service points only at `chat`-capable targets |
| Image size | Accept the ML weights in the universal image; measure before making role-specific images |
| Order | Durable state and migrations land before any distributed target is exposed |

---

## Where they disagreed, and what was decided

### 1. The retrieval boundary: `knowledge`, not `embedding`

**Claude** proposed an `embedding` target exposing an OpenAI-shaped `/v1/embeddings`, with
`chat` keeping pgvector and embedding queries through Spring AI's OpenAI client. **Codex**
proposed a `knowledge` component owning embedding, search, corpus versions and import, with
`chat` calling `/internal/v1/knowledge/search`.

**Decided: Codex's boundary.** Claude's design had two processes writing and reading
`vector_store`, which blurs ownership, and needed a convention on the `model` field to carry
e5's `query:` prefix over a wire format that has no place for it. Inside `knowledge` the
query is embedded next to the model and the convention disappears.

### 2. How `chat` reaches `knowledge`: a search-only `VectorStore`, no spike

**Codex** left the choice between a narrow `VectorStore` adapter and a new advisor to a
spike. **Claude**'s original design did not face the question.

**Decided: the adapter, without a spike.** A `VectorStore` whose `similaritySearch` calls
the search endpoint and whose write methods throw is a few dozen lines, and it leaves
`QuestionAnswerAdvisor`, `RetrievalReportingAdvisor` and `AdvisorChainOrderTest` untouched.
A new advisor would re-implement `QuestionAnswerAdvisor`'s prompt template and have to
reproduce the `RETRIEVED_DOCUMENTS` context key that `RetrievalReportingAdvisor` reads.
In `all` the `VectorStore` bean is `PgVectorStore` exactly as today.

### 3. No order service

**Claude** proposed an `orders` target over the mock repository, to exercise network failure
modes on a read. **Codex**: do not create a mock order service until there is a real order
system.

**Decided: Codex.** A service whose only implementation is a mock is a fiction. The
`OrderLookup` interface is extracted so a real system plugs in as an HTTP adapter later, and
the network failure semantics are exercised on the ticket seam, which is the write and the
harder case.

### 4. Ticket idempotency: two keys, a guard row, a recovery endpoint

Both put tickets in Postgres with an atomic cap check. **Codex** added three things
**Claude** lacked: a transport idempotency key (an operation id, generated by `chat` outside
model-controlled arguments and reused on retry) distinct from the business deduplication key
(the normalised summary the code already computes); a per-conversation guard row locked in
the creating transaction, because a unique index cannot enforce a count; and
`GET /internal/v1/ticket-operations/{operationId}` to recover the result after an ambiguous
timeout, since cancelling an HTTP request does not stop a remote write.

**Decided: all three.** Claude's "one statement that checks and inserts atomically" was the
right goal and the guard row is the concrete way to do it. The `chat`-side sequence on the
write seam is: one attempt; on timeout one retry with the same operation id inside a total
deadline; then one recovery read; then, and only then, an `unavailable` value that tells the
model to apologise and offer a human. Reusing an operation id with different input is a
conflict, not a match.

### 5. Budget: durable settlement, no reservations

**Claude** proposed persisting today's post-hoc accounting: check remaining before the turn,
`UPDATE ... RETURNING` after. **Codex** proposed per-turn reservations, settlement, unresolved
reservations retained on disconnect, recovery rules and a provider-aware estimation policy.

**Decided: Claude's, with the limitation written down.** Three reasons. Reservations change
admission in `all` mode: a turn admitted today could be refused by a reservation. The
estimate a reservation is based on has no measurement behind it, and the usage-metadata
gaps [reliability.md](../reliability.md#a-turn-is-not-a-model-call) documents are not
closed by adding a second, estimated number. And the gap durability actually closes, replicas
not sharing spend and restarts resetting it, is closed by the table. The remaining exposure,
concurrent turns on one conversation overshooting, is closed more cheaply by decision 7.
Reservations are recorded under [Deferred](#deferred-deliberately) with the condition that
would justify them.

### 6. One Maven module with an ArchUnit rule, not six modules

**Codex** proposed `contracts`, `chat`, `knowledge`, `ticket`, `app` and `system-tests`
modules so that dependency direction is enforced at compile time. **Claude** proposed one
module with `@ConditionalOnTarget`.

**Decided: one module, Codex's composition rules, an ArchUnit test.** The codebase is 2200
lines; six modules move the Dockerfile, CI, IDE setup and test layout for a guarantee that
`com.tngtech.archunit` gives for one dependency and one test class: `chat` may depend on
`knowledge` and `ticket` only through their interfaces and DTOs. Codex's rules that survive
unchanged: explicit role configuration imports, no root package scanning, unknown or
incomplete targets fail before readiness, and a `ticket` process must boot without an LLM
key or native ML libraries. A later split into modules is mechanical once the ArchUnit rule
has held.

Codex's revised position corrected Claude's original per-target settings, and the
corrected table is the one that applies. Bean conditions alone do not switch off Spring AI
starters, native model initialisation or datasource setup; these are property-level switches
applied by an `EnvironmentPostProcessor` before auto-configuration runs, and role
configuration also covers resources, property binding, connection pool size, health
indicators and the custom beans (credential validator, xAI configuration, sampling-parameter
stripper, static resources), not only the switches.

| | `all` | `chat` | `knowledge` | `ticket` |
| --- | --- | --- | --- | --- |
| Chat provider, credential validator, provider customisations | yes | yes | no, starts without any LLM key | no |
| Embedding model | local ONNX | none | local ONNX | none |
| `VectorStore` | `PgVectorStore` | search-only HTTP adapter | `PgVectorStore` | none |
| Datasource | yes | yes: memory, budget, lease | yes: vector data, import status | yes: tickets, operations |
| Public chat routes and demo page | yes | yes | no | no |
| Internal endpoints served | none | none | search | tickets, ticket operations |
| Corpus import | `startup` | never | `off`, or `once` for the job | never |

### 7. One active turn per conversation

**Codex** proposed a durable lease admitting one turn per conversation, with fencing on
memory writes and an explicit conflict response on overlap, flagged as an API change.
**Claude** was silent.

**Decided: the lease and the `409`, without fencing in the first release.** *Owner's call,
see below.* `TurnEventBusConcurrencyTest` exists because overlapping turns happened; the
fix isolated the SSE channels and left the memory interleaving. A lease row with a TTL of the
HTTP read timeout plus a margin, taken at admission and released in the turn's `doFinally`,
closes that. Fencing memory writes means wrapping Spring AI's JDBC repository and is deferred:
a stale writer exists only after lease expiry, which means the turn already exceeded the read
timeout and has failed. The benchmark is unaffected; `LoadDriver` uses a fresh conversation
per request, for the reason its own comment gives.

### 8. Flyway, and Spring AI's schema initialisation switched off

**Codex** asked for a versioned migration mechanism, a serialised migration step before
serving replicas, and pgvector provisioned once. **Claude** proposed
`CREATE TABLE IF NOT EXISTS` on boot.

**Decided: Flyway.** With ticket, operation, budget, lease and corpus-version tables joining
the two Spring AI ones, boot-time DDL is no longer small, and Spring AI's
`initialize-schema` for pgvector and JDBC memory would compete with it. The in-flight
`claude/ddl-race` branch is about exactly that class of race on `CREATE EXTENSION`; phase 2
waits for it. Existing `spring_ai_chat_memory` and `vector_store` rows are inventoried by the
first migration, not recreated.

### 9. Corpus import: serialised, gated, one property as the trigger

Both remove re-ingestion from serving replicas. **Codex** originally required staging under
a new version and an atomic switch of an active-version pointer, because the current
write-then-retire scheme lets a reader see a mix of two versions, then withdrew that in its
revised position until live corpus updates are needed. **Claude** proposed an `ingest`
target as the trigger, and assumed a previous corpus is always there to serve during an
import, which is false on an empty database.

**Decided: a serialised importer, a first-import readiness gate, the atomic switch
deferred.** `app.knowledge.import` takes `startup` (default in `all`, so a laptop still
works with `docker compose up`), `once` (import, reconcile, exit with a meaningful status;
the Kubernetes `Job` and the distributed Compose file use it) or `off` (default for serving
`knowledge` replicas). The importer holds a database advisory lock and records a durable
import status; an unchanged corpus version is not re-embedded. `knowledge` readiness reports
ready only once that status shows a complete corpus, so a fresh distributed install serves
no retrieval until the importer has finished, and `chat` in the distributed topology
inherits that through the search seam. In `all`, import on startup completes before the
application is ready, as it does today. Changes to an existing corpus use a maintenance
window while the write-then-retire scheme remains; the atomic version switch is in
[Deferred](#deferred-deliberately) with the condition that reopens it.

### 10. Internal endpoints authenticate the caller

**Codex** required service authentication and restricted reachability for internal
endpoints. **Claude** was silent, and [CLAUDE.md](../../CLAUDE.md) says not to add
authentication without asking.

**Decided: a static service token from configuration, plus a Kubernetes NetworkPolicy.**
*Owner's call, see below.* This is service-to-service authentication, not customer
authentication, which stays out of scope. A ticket-creating endpoint reachable on a network
without a credential is a write anyone on that network can perform.

### 11. Testing: both kinds

**Claude** proposed a single-JVM topology test with per-target contexts on random ports.
**Codex** said single-JVM tests are insufficient for multi-replica concurrency.

**Decided: both, for different claims.** The single-JVM multi-context test proves contract
parity, the same scenario suite against local and HTTP adapters, and runs in `./mvnw verify`.
Multi-replica concurrency, process termination and duplicate settlement run against real
processes in the distributed Compose file, as a separate CI job. Assertions in both read the
database rows and the SSE events rather than the client's return value, which is the rule the
cross-review of the two repositories produced.

---

## Owner's calls

Three decisions above change behaviour or cross a stated scope line. They are decided as
written unless the owner overrides on this pull request.

1. **A second turn on a conversation that already has one in flight is refused with `409`,
   in every mode.** Today it is accepted and the histories interleave.
2. **Internal endpoints require a service token.** Service-to-service only; no customer
   authentication.
3. **The budget stays post-hoc.** No reservations; the exposure is stated in the docs.

---

## What changes in `all` mode

Two things, both correctness fixes the two-replica manifest already needed:

- Tickets and the conversation budget live in Postgres. Two replicas share the cap and the
  spend; a restart no longer resets either.
- One active turn per conversation; overlap gets `409`.

Everything else in `all` is today's process: the same beans, the same advisor chain, the
same in-process ONNX model, the same benchmark path. Database-backed tickets, budget and
lease add round trips to that path, so the benchmark is re-run after phase 2 in both modes
and against the Go implementation. The historical results stay in
[benchmark.md](../benchmark.md) with the commit and configuration they were measured at;
the numbers are not promised to be unchanged, only to be re-measured and reported next to
each other.

---

## Deferred, deliberately

| Item | Condition that would reopen it |
| --- | --- |
| Budget reservations | A measured estimation error small enough to admit on, or a customer whose budget must be exact |
| Fencing on memory writes | A lease expiry observed in production, or the read timeout being raised |
| Atomic corpus version switch (stage, validate, flip an active-version pointer) | A corpus update that must happen while `knowledge` is serving, without a maintenance window |
| Maven multi-module split | The ArchUnit rule failing to express a constraint, or a second deployable that needs a subset of the code |
| A model-less image for `chat` and `ticket` | Registry or pull cost measured and found to matter |
| A real order service | A real order system |
| Zero-downtime topology switching | A need to switch topologies without a maintenance window; the first switch uses one |
| Arbitrary target combinations (`chat,ticket`) | A deployment that needs one |

---

## Plan

Each phase is a pull request that leaves the existing suite green and `all` behaving as
before, other than the two changes above. Estimates are Claude session hours; wall time
depends on how sessions are spaced.

| # | Phase | Hours | Waits for |
| --- | --- | --- | --- |
| 1 | Boundaries: `OrderLookup`, `KnowledgeSearch`, `TicketOperations` with local implementations; `app.target` and the role configurations; the ArchUnit rule. Only `all` exposed. | 2–3 | |
| 2 | Shared correctness: Flyway; ticket, operation, budget and lease tables; the guard row; the `409`; the serialised importer, its status and readiness gate, and `app.knowledge.import`. Two-replica concurrency tests. Benchmark re-run. | 5–6 | 1, `claude/ddl-race` |
| 3 | Role composition: `knowledge` and `ticket` internal endpoints; the `chat`-side adapters, operation ids and recovery; the service token; the single-JVM parity test. | 4–5 | 2 |
| 4 | Deployment: distributed Compose, Kubernetes overlays and `Job`, probes, the multi-process concurrency job in CI, `kind` verification of both layouts. | 3–4 | 3 |
| 5 | Switching and rollback procedure, README architecture diagram, CLAUDE.md constraints, both proposals marked superseded, both benchmarks side by side. | 1–2 | 4 |

Sixteen to twenty-one session hours. Phase 2 is worth doing on its own.

---

## Answers to both cross-review agendas

**Claude's five questions.** (1) `@Tool` stays in `chat`: shared. (2) In-process wiring for
`all` with a real topology test, not loopback transport: kept, and Codex listed loopback as
the fallback if two adapters prove costly. (3) One database: shared; the README's line about
transactional consistency between a ticket and its conversation is corrected in phase 5,
because components do not share transactions. (4) Nothing in either design is a gateway or
bus in disguise. (5) Single-JVM feasibility: still the largest technical risk in phase 3, and
now only has to carry contract parity, not concurrency.

**Codex's seven questions.** (1) `knowledge` and `ticket` are the right boundaries;
ticket extraction is justified because the cap is presented as a safety boundary and today
is not one. (2) One universal artifact: yes, measure, revisit. (3) `VectorStore` adapter,
decision 2. (4) Overlapping turns rejected, decision 7, owner's call. (5) No reservation
policy is defensible without a measured estimate; decision 5. (6) Yes: phase 2 precedes
phase 3, and the first-import readiness gate is part of it. (7) Yes: one release per installation, a maintenance window for the first switch.

---

## Addendum, 2026-09-05: Codex's revised position and the owner's rulings

After PR #3 merged, Codex read both proposals and published a
[revised position](../CODEX_DUAL_DEPLOYMENT_DECISION.md) (PR #5), written against the
commit before this ADR existed. Claude read it against this ADR. Most of it agrees with
the decisions above: one module, `OrderLookup` without a mock order service, Flyway, a
service token on internal endpoints, same-release roles, a maintenance window for the first
topology switch, both kinds of tests. Four points remained different, and on two of them the
authors had crossed over, each adopting the other's original proposal.

| | Claude's original | Codex's original | ADR 001 as merged | Codex revised | Ruling |
| --- | --- | --- | --- | --- | --- |
| Retrieval boundary | `embedding` service; `chat` keeps pgvector | `knowledge` service | `knowledge` | `embedding`, "avoid replacing the existing retrieval integration" | **`knowledge`**, decision 1 stands |
| Corpus publication | run-once `ingest` target | atomic version switch | atomic switch | deferred until live updates are needed | **deferred**, decision 9 rewritten |
| Budget | durable post-hoc settlement | reservations and recovery | post-hoc | reservations, "cumulative spend alone does not protect concurrent admission" | **post-hoc**, decision 5 stands |
| Memory-write fencing | not addressed | lease plus fencing | lease, fencing deferred | lease plus fencing in the first release | **fencing deferred**, decision 7 stands |

**Why the retrieval boundary stays `knowledge`.** Both are workable, and the deciding
factor is which carries fewer unknowns. The search seam is our own DTOs and our own client.
The embedding seam depends on Spring AI's OpenAI embedding client accepting a placeholder
key and our server matching its response shape, both unverified, and on the prefix trap
below. With a search-only `VectorStore` adapter the existing retrieval integration is not
replaced either, which removes Codex's stated reason for switching.

**Why the atomic switch is deferred.** Codex withdrew its own requirement, and Claude's
adoption of it in the first version of this ADR overstated the benefit for a thirty-six
document corpus that changes with a release. The serialised importer, the durable import
status and the readiness gate are what a fresh install and a rolling deploy actually need.

**Why the budget stays post-hoc and fencing stays deferred.** No new argument arrived on
either. Codex's concern about concurrent admission on one conversation is met by the lease;
its concern about interrupted turns with missing usage is real and is recorded as the
limitation it already is in [reliability.md](../reliability.md), not fixed by adding an
unmeasured estimate. A stale writer exists only after lease expiry, which is after the turn
has already failed on the HTTP read timeout.

**Four corrections from the revised position, absorbed into the body above:**

- The per-target settings table under decision 6, and the note that bean conditions alone
  do not switch off starters, native initialisation or datasource setup.
- The empty-database case: no previous corpus exists on first install, so readiness has to
  observe import completion, decision 9.
- `PrefixingEmbeddingModel` applies the `query:` and `passage:` markers in its specialised
  `embed` methods and passes `call(EmbeddingRequest)` through untouched. The
  [README](../../README.md#the-same-system-in-go) already lists "which embedding overload
  applies the `query:` marker" as a constraint this codebase defends with a test; inside
  `knowledge` that test stays, and the search path must be shown to go through an overload
  that applies the marker exactly once.
- Benchmark results are preserved with their commit and configuration, and both modes and
  the Go comparison are re-measured, rather than promising unchanged numbers. Wording under
  [What changes in `all` mode](#what-changes-in-all-mode).

**Owner's rulings.** On 2026-09-05 the owner confirmed the three owner's calls as written
and the four rulings in the table above, in Claude's session, after reading both the ADR and
Codex's revised position. Merging PR #5 preserved Codex's record; it did not constitute
Codex's endorsement of these rulings, and Codex's document says so itself.
