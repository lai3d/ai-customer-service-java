# Dual Deployment Decision — Codex After Cross-Reading

- **Author:** Codex
- **Date:** 2026-09-05
- **Status:** Codex recommendation after reading both proposals; pending maintainer acceptance and Claude Code's response. Not implemented.
- **Baseline:** `04c3f0885da074e1e79606d356ef68f9a01f4858`.
- **Inputs:** [Codex proposal](CODEX_DUAL_DEPLOYMENT_DESIGN.md) ([PR #2](https://github.com/lai3d/ai-customer-service-java/pull/2)) and [Claude Code proposal](dual-target.md) ([PR #3](https://github.com/lai3d/ai-customer-service-java/pull/3)).

This records Codex's revised position, not a claim of joint agreement. It supersedes the
initial Codex recommendation where the decisions differ. Preserve both original proposals
as the record of what each author initially recommended.

## Decision

Use Claude's smaller deployment split, with stronger shared-state and migration guarantees.
Keep one Maven module, one executable JAR, and one image initially. Support four serving
targets, `all`, `chat`, `embedding`, and `tickets`, plus the run-once `ingest` target.
Default to `all`; bind the single selector `APP_TARGET` to `app.target`.

All-in-one uses local calls; distributed deployment uses HTTP adapters. Both execute the
same business implementations and use the same persistent state. PostgreSQL stays external
in either mode. Preserve Java 21, Spring MVC, virtual threads, and model calls through
`ChatClient`.

| Topic | Selected approach | Reason / revision |
| --- | --- | --- |
| Build | One Maven module with explicit package boundaries | Adopt Claude's lower-cost starting point; defer Codex's multi-module build. |
| Retrieval | Keep advisors and pgvector queries in chat; extract embedding inference | Avoid replacing the existing retrieval integration just to enable distributed deployment. |
| Tickets | Local/HTTP adapters over one durable implementation | Both proposals agree on the boundary; strengthen concurrency and retry semantics. |
| Orders | Extract `OrderLookup`; keep the mock local for now | An independently deployed mock is not required to prove dual-mode operation. Add an adapter for a real order system when available. |
| Budget | Shared reservations, settlement, and recovery | Durable cumulative spend alone does not protect concurrent admission or interrupted turns. |
| Ingestion | One importer at a time; first-import readiness gate | Adopt a run-once target; correct the assumption that a previous corpus always exists. |
| Schema evolution | Versioned, serialized migrations | Retain Codex's approach; startup `CREATE TABLE IF NOT EXISTS` is not an upgrade strategy. |
| Transport | Local in `all`, HTTP across targets | Preserve simple local operation; test both adapters and real separate processes. |

## Selected Topology and Its Limits

| Target | Runs | Data access |
| --- | --- | --- |
| `all` | Chat/API/UI, local embeddings and tickets; guarded bundled-corpus initialization | All component stores through their owning implementations |
| `chat` | API/UI, advisors, memory, model/tool loop, SSE, budget, local order adapter | Read/write chat state; read-only vector queries |
| `embedding` | Authenticated query-embedding endpoint and local ONNX model | No database required |
| `tickets` | Internal ticket API and business implementation | Read/write ticket state only |
| `ingest` | Local passage embedding and corpus reconciliation; exits after completion | Read/write vector data and import metadata |

The `@Tool` classes, tool descriptions, `ToolContext`, `TurnEventBus`, partial-reply
persistence, and usage aggregation remain in chat. Ticket implementation code returns
business results and does not depend on chat events. There is no token message broker,
additional gateway, service registry, or independently deployed billing component.

The extraction removes the need to initialize ONNX in chat. It does not remove ML bytes
from the shared image or make the database independently scalable per service. Chat and
ingest deliberately share the vector schema: this is a modular application with flexible
deployment, not fully autonomous services with private databases. Give chat read-only
vector privileges and ingest write privileges; disable automatic vector DDL in chat.

Move to a complete knowledge service when multiple applications need retrieval, knowledge
authorization needs an independent boundary, or corpus/schema releases must be independent
of chat. Move to Maven submodules when package-boundary checks or dependency ownership
become difficult to maintain. Neither change is required for the first distributed release.

## Role Composition and Embedding Contract

Use explicit role configurations and resolve the target before auto-configuration. Unknown
targets and conflicting overrides must fail startup. An `EnvironmentPostProcessor` and a
target condition are reasonable mechanisms, but bean conditions alone do not disable
Spring AI starters, native initialization, or database setup.

Correct the combined role settings in Claude's initial table:

- Only `all` and `chat` initialize a chat provider, its credential validator, and provider
  customizations. Other roles must start without any LLM API key.
- `all`, `embedding`, and `ingest` initialize local embeddings; `chat` initializes the
  remote embedding client. `tickets` initializes neither.
- `embedding` must start without a database. `tickets` must not discover chat memory,
  vector storage, ONNX, or public chat/UI routes.
- `ingest` has no serving web endpoint and exits with a meaningful status. In `all`, the
  shared importer is invoked without executing the run-once process-exit behavior.
- Role configuration must cover resources, property binding, connection pools, health
  checks, and custom beans as well as auto-configuration switches.

Adopt an OpenAI-shaped query-only `/v1/embeddings` endpoint if a focused compatibility test
confirms Spring AI 1.1.8's request/response behavior. Authenticate internal calls; a dummy
key whose value the server ignores is not the production design. Pin the accepted model
identifier and validate input shape, batch limits, vector dimensions, and output ordering.
Reuse the framework client, but still test our server and its integration with that client.

The embedding server adds `query:` exactly once. Local ingestion adds `passage:` exactly
once. Test both against the local implementation: the current
[PrefixingEmbeddingModel](../src/main/java/dev/merlionos/customerservice/rag/PrefixingEmbeddingModel.java)
adds prefixes in its specialized `embed` methods, while `call(EmbeddingRequest)` delegates
without adding them. Passing a request through `call` is not sufficient by itself.

Keep `QuestionAnswerAdvisor`, memory-before-retrieval ordering, and retrieval event timing.
Knowledge failure must produce an explicit unavailable/error outcome, not an apparently
successful empty retrieval. Verify blocking HTTP status and SSE error mapping rather than
assuming the current exception handler already maps every new transport failure correctly.

## Required Shared-State Guarantees

### Ticket capacity and idempotency

Persist business deduplication by conversation and normalized summary. Separately persist
an operation ID for retrying the same write, together with an input fingerprint and result.
Chat supplies trusted context and creates/reuses the operation ID outside model-controlled
arguments. Preserve the optional order reference in the new interface and wire contract.
A conversation ID scopes many operations; it is not a sufficient operation idempotency key.

Creation must serialize competing capacity checks for the same conversation. A suitable
implementation creates or obtains a persistent guard row, locks it, then checks capacity,
checks duplicates, and writes the ticket/operation result in one transaction. Keep a unique
business key as an additional constraint. A single count-and-insert SQL statement is not,
by its shape alone, proof of safe concurrent admission: establish the locking or isolation
mechanism and test two competing replicas. PostgreSQL documents how row locks serialize
competing writers in its [explicit locking reference](https://www.postgresql.org/docs/current/explicit-locking.html).

Return the recorded result for a repeated operation; reject changed inputs under the same
operation ID. A timeout can mean the ticket committed but its response was lost. Recover
the result or retry with the same ID before declaring failure. Never silently fall back
from the remote store to a local store. Do not claim ticket and chat-memory atomicity merely
because their transactions connect to one PostgreSQL instance.

### Budget and conversation admission

Use durable reservations keyed by turn, atomic quota admission, and idempotent settlement.
Limit model/tool-call work admitted under a reservation; define and test the provider-aware
allowance policy before advertising a spending bound. Store known usage separately from
estimated or reserved usage. The existing usage reconstruction is not an exact billing ledger.

Missing terminal usage after cancellation must not automatically release all quota as if
no work occurred. Mark unresolved turns, reconcile recoverable usage, and define bounded
recovery/manual-resolution behavior for stale reservations. Expiring a reservation blindly
reopens the cancellation bypass; retaining it indefinitely silently loses quota. The recovery
policy is an implementation prerequisite, not a reason to introduce a billing service.

For the first release, recommend one active turn per conversation across replicas, with a
durable lease and fencing/version checks on history writes. Reject overlap explicitly before
opening SSE. Different conversations remain concurrent. This changes API behavior in both
modes and must be documented and tested. Do not hold a database transaction or connection
lock throughout an LLM response. Expired owners must not overwrite a newer turn's history.

### Migrations and ingestion

Choose one versioned migration mechanism and serialize migrations before serving replicas
start. Provision pgvector and schemas under migration credentials, then run with scoped
runtime credentials. Baseline/migrate existing tables without losing conversation IDs,
history, or vectors. Do not run competing Spring AI schema initializers afterward.

Require a completed, validated corpus before initial chat readiness. In distributed Compose
or Kubernetes, gate first startup on the successful importer or equivalent durable import
status. An empty database has no previous corpus to serve. Embedding readiness itself does
not depend on corpus publication because it owns no corpus.

Initially use a serialized importer and a maintenance/drain window for changes to an existing
corpus, in both modes. All-in-one may initialize or reconcile the bundled corpus on startup,
but must use the same import lock/status and avoid unnecessary re-imports of an unchanged
version. After a crash or failed partial import, do not mark that corpus ready until a
successful reconciliation or restore. Test that startup readiness actually observes import
completion rather than merely observing application-context startup.

Defer full staging plus atomic active-version publication until live corpus updates are
required. The current write-then-retire algorithm is not an atomic version switch; this
decision does not claim online, uninterrupted updates while it remains in use.

## Delivery and Validation

Each phase should be split into scoped PRs that leave `all` runnable.

| Phase | Work | Required evidence |
| --- | --- | --- |
| 1. Local boundaries and shared state | Extract interfaces; add migration, durable tickets/budget, and conversation policy while retaining local adapters | Existing API/advisor/provider tests; populated-database migration; concurrent admission, retry, lease, and recovery tests |
| 2. Targets and transport | Add role composition, authenticated embedding/ticket APIs, HTTP adapters, import lifecycle | Role startup without unrelated keys/database/native models; local/HTTP contract parity; prefix and actual framework-client compatibility tests |
| 3. Deployment and switching | Add distributed Compose/Kubernetes layouts, probes, shutdown handling, operational instructions | Real independent processes, multi-replica tests, failures/restarts, clean first install, preserved state through topology switch and rollback |

Multiple Spring contexts with real sockets are useful integration tests, but separate-process
tests must also catch process-local state, configuration, and recovery defects. Cover
commit-then-response-loss for tickets, interrupted streams with missing usage, same-conversation
overlap, first import failure, and simultaneous fresh-database starts. Use deterministic model
doubles so the normal suite needs no paid provider keys.

Keep public chat routes, payloads, SSE names, prompt grounding, and advisor order stable except
for explicitly documented admission conflicts. Database-backed behavior changes latency in
`all`, so remeasure both modes and the Go comparison. Preserve historical benchmark results
with their commit/configuration rather than promising unchanged numbers.

Start with the same release version across roles and a tested brief-maintenance topology
switch: stop admission, drain turns, handle unresolved operations, start the destination
against the same compatible schemas, smoke-test, then redirect ingress. Rollback targets
must understand the new persistent data; today's in-memory implementation is not a safe
rollback target. Physical database separation and zero-downtime topology switching are deferred.

## Review and Acceptance

Claude Code's response should focus on the selected split, removal of the mock orders target,
corrected role settings, ticket locking/idempotency, budget recovery, and first-import gating.
The implementation PRs must settle the exact migration tool, reservation policy, lease/history
integration, internal authentication, and embedding client compatibility with evidence.

The original proposals remain linked above. Record maintainer acceptance and Claude's
agreements or objections explicitly in a follow-up; merging this authored recommendation
does not establish mutual endorsement. This documentation-only change executes no migration
and makes no claim that the proposed targets or acceptance tests already work.
