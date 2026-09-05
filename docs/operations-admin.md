# Java operations admin proposal

Status: proposed, not implemented. This document defines product scope and implementation stages; it does not change the existing deployment decisions.

## Purpose and recommendation

The existing chat page and technical monitoring are sufficient for demonstrating AI customer service and comparing the Java and Go implementations. An operations admin interface becomes necessary when staff need to maintain knowledge, review answers, and handle customer issues continuously.

Build the first release around two complete workflows:

1. Find an incorrect or incomplete answer → flag it → revise the FAQ → validate retrieval → publish → review the result.
2. AI creates a ticket → a person claims or assigns it → records the resolution → closes it, with access to the original conversation.

Add the interface to the existing Spring Boot application and reuse its business services. Deliver support for the single-process deployment first; validate split deployment support in a separate stage. The admin interface does not require a new microservice.

Assume internal use by one organization. Staff authentication is new scope in this proposal; multi-tenancy and a complete customer identity system remain outside the first release. Shipping the admin interface does not establish production customer identity or conversation access controls on the public chat endpoint.

## Existing capabilities and gaps

The following reflects the current Java implementation. Stored data alone does not provide an operational workflow.

| Area | Existing capability | Required additions |
| --- | --- | --- |
| Chat | Blocking and SSE endpoints, JDBC chat memory, per-turn retrieval/tool/usage events | Conversation lists, complete operational records, answer feedback, historical retrieval evidence |
| Knowledge | Bundled bilingual FAQ, local embeddings, pgvector search, import version records and readiness | Online editing, drafts, publication jobs, version switching and rollback |
| Tickets | Database storage, idempotent creation, lookup by conversation, creation-operation recovery | Global paginated search, ownership, state transitions, handling history |
| Cost and diagnostics | Token budgets, Micrometer metrics, OTLP traces | Defined operational aggregates and, where needed, persistent per-turn usage and estimated cost |
| Access control | Internal service bearer token in split deployments | Staff login, business permissions, audit records |

Implementation references: [`ChatService`](../src/main/java/dev/merlionos/customerservice/chat/ChatService.java), [`TurnEvent`](../src/main/java/dev/merlionos/customerservice/chat/TurnEvent.java), [`CorpusImporter`](../src/main/java/dev/merlionos/customerservice/rag/CorpusImporter.java), [`TicketOperations`](../src/main/java/dev/merlionos/customerservice/ticket/api/TicketOperations.java), and the [shared-state migration](../src/main/resources/db/migration/V2__shared_state.sql).

Chat memory serves model context and is subject to windowing and cleanup. SSE events serve the current display. Neither guarantees a complete, permanent operational record. Likewise, `corpus_import` records are not knowledge releases that can be switched online.

## First-release scope

| Page | Operations | Acceptance focus |
| --- | --- | --- |
| Conversation list and detail | Filter by time, conversation ID and execution outcome; inspect messages, retrieval evidence, tool results and linked tickets; flag incorrect or incomplete answers | Distinguish failures from interruptions; preserve historical references when FAQ content changes |
| Answer feedback | Review pending reports, link FAQ revisions and publications, record handling conclusions | Move feedback from discovery to a verified fix or closure with an explanation |
| Knowledge | Edit FAQ drafts by language, inspect differences, preview retrieval, publish, deactivate and roll back | Drafts stay out of live retrieval; failed publication leaves the previous version serving |
| Ticket list and detail | Filter by state, owner and time; claim, assign, add internal notes and change state | Attribute every transition to an actor and time; duplicate submissions do not duplicate history |
| Login and audit | Sign in/out, assign predefined roles, inspect publication, ticket and permission changes | Enforce permissions on the server, beyond button visibility |

Suggested roles: administrators manage accounts, roles, publication and rollback; knowledge operators manage drafts, retrieval previews and feedback; support staff review conversations, flag answers and handle tickets. Grant conversation-content access explicitly. Permissions can be combined across roles; a role does not automatically grant access to all customer content.

The first release excludes live human chat takeover, agent scheduling, arbitrary document ingestion, a custom permission designer, visual workflows, online API-key editing, model routing and prompt configuration. A formal integration with an existing ticket system should replace the local handling workflow to avoid manually synchronizing two sets of ticket states.

Add an operational overview later, starting with conversation volume, execution failure rate, feedback volume, pending tickets and model usage. Show satisfaction, issue resolution and human takeover rates only after the corresponding business events and measurement definitions exist.

## Business rules

### Conversations and feedback

- Persist a stable `turnId` for each turn, with its `conversationId`, start/end times and execution outcome. Cover both blocking and SSE paths.
- Store customer-visible messages and the knowledge versions actually referenced, with content snapshots or immutable references. Show retrieval and tool execution evidence; do not collect or display private model reasoning.
- Distinguish completed, failed, interrupted and incompletely recorded turns. Cancellation, upstream failure and process failure must not appear as successful completion. Recovery marks unfinished records after process termination as interrupted or outcome unknown; it must not invent missing responses.
- Bind feedback to a specific turn and optionally to a knowledge revision and handling conclusion. Closing feedback means the report has been handled, not automatically that the customer's issue is resolved.
- Give chat memory and operational records separate retention policies. Determine retention periods, redacted fields and cleanup before implementation. Historical memory cannot be losslessly backfilled into complete operational records; show when reliable coverage begins.

### Knowledge editing and publication

Separate content revision status from publication job status. Content is draft, published or inactive; a job is queued, building, validating, succeeded or failed. Each publication creates an immutable snapshot identifying its revisions and actor.

1. Saving a draft does not affect live retrieval. Before publication, validate required fields, language and stable IDs, and show the change set.
2. Build embeddings for the candidate version and validate entry counts, completeness and agreed retrieval examples. Preserve an error summary on failure and allow explicit retries.
3. After validation, atomically update the active version. Live retrieval must explicitly filter by that version, without mixing candidate and previous vectors.
4. Pin each retrieval to a version and record it with the turn's references. Requests that started before a switch can still read the previous version. Cleanup must account for in-flight requests, historical references and the rollback retention period.
5. Roll back to a historical version whose complete index is still retained and record the action. Use expected-version checks so an older concurrent publication job cannot overwrite a newer release when it finishes later.

Retrieval previews reuse the live search service and embedding-prefix rules, changing only the version queried. A successful preview proves that expected content can be found; representative end-to-end answer checks are still needed to assess the resulting answer.

[ADR 001](adr/001-deployment-targets.md) defers atomic corpus switching until online updates are needed. This proposal's publication workflow triggers that condition and requires new implementation; connecting the startup importer to a Publish button is insufficient. Define migration from the bundled FAQ to the first managed version, change readiness to check the active version, and ensure application restarts cannot overwrite operator-maintained content.

### Ticket handling

Use the minimal state machine `OPEN → IN_PROGRESS → RESOLVED → CLOSED`. Allow `RESOLVED` and `CLOSED` tickets to reopen into `IN_PROGRESS` with a mandatory reason; resolution requires a handling conclusion. Record claiming and assignment separately from state changes. An assignee must be an active account with ticket-handling permission.

Extend the existing ticket business service while preserving the AI creation tool's idempotency and ticket-cap rules. Use version checks for concurrent admin updates; return `409` on conflict so the operator can refresh and retry. Deduplicate repeated submissions by operation ID. Commit operation history and state updates in the same transaction.

## Integration with the existing architecture

Use `/admin` for pages and `/api/admin/v1/**` for management endpoints. Keep pages and APIs on the same origin initially; choose a frontend framework during implementation based on maintenance cost. Browser pages must not carry the internal service token or call model providers directly.

Admin controllers handle identity, permissions, input validation and response mapping. Business logic stays with `chat`, `rag` and `ticket`. Deterministic actions such as editing knowledge and assigning tickets call those services directly rather than going through chat.

| Deployment | Proposed integration |
| --- | --- |
| `APP_TARGET=all` | Serve pages and management APIs, calling business services in-process; supported by the first release |
| Split `chat` / `knowledge` / `ticket` | Later, host pages and the management entry point in `chat`, using new internal management APIs; `knowledge` owns publication and `ticket` owns ticket changes |

Explicitly disable the admin entry point in split deployments for the first release. Avoid partially working pages whose writes have no local service. Later adaptation must deploy matching role versions and preserve service boundaries; `chat` must not directly read or write knowledge or ticket tables for admin operations.

Split management calls need both service identity and an auditable operator context. The internal token authenticates a service, not an operator's permissions. The entry point checks permissions and forwards trusted actor information; domain services audit the actor and validate permitted business actions. Define and test the propagation protocol during split deployment adaptation.

Keep Spring MVC, virtual threads, `ChatClient` and the existing advisor chain. Technical diagnostics stay in Prometheus/Jaeger. Admin pages may offer authorized diagnostic links without rebuilding technical monitoring.

## Data and API outline

These are proposed logical models and endpoints, not existing tables or compatibility commitments. Add new Flyway migrations without changing released migrations.

| Data | Purpose |
| --- | --- |
| Operational conversations, turns and messages | Query the business history, outcomes, referenced versions, trace IDs and record completeness |
| Per-model-call usage | Record provider, actual model ID, input/output tokens, usage completeness and pricing version; aggregate by turn |
| Answer feedback | Issue type, turn, handler, state, revision/publication links and conclusion |
| FAQ entries, revisions, publications and jobs | Stable identities, bilingual content, immutable history, active version and job outcomes |
| Ticket fields and operation history | Owner, business state, update time, concurrency version, internal notes and transitions |
| Staff identity mappings, roles and audit records | Identity source, permissions, actor, object, action, outcome and time |

Turn records must not depend entirely on the event bus feeding the browser. Write necessary state at service execution boundaries so terminal outcomes can still be recorded after a client disconnects. If the initial record cannot be written, fail before calling the model. If final recording fails, alert and retain recoverable state; a database transaction cannot guarantee atomicity with an external model call.

| Endpoint group, relative to `/api/admin/v1` | Proposed operations |
| --- | --- |
| `/conversations`, `/conversations/{id}` | Paginated search and detail |
| `/turns/{id}/feedback`, `/feedback/{id}` | Create feedback and update handling state/conclusion |
| `/knowledge/entries`, `/knowledge/entries/{id}` | Search, create, save revisions and draft deactivation |
| `/knowledge/search-preview` | Search a specified candidate version |
| `/knowledge/publications`, `/knowledge/publications/{id}` | Submit publication and inspect the job |
| `/knowledge/rollback` | Submit rollback with target and expected current versions |
| `/tickets`, `/tickets/{id}` | Paginated search and detail |
| `/tickets/{id}/assignment`, `/tickets/{id}/transitions`, `/tickets/{id}/notes` | Assign, transition and annotate |
| `/audit-events` | Search by actor, object and time |

Bound list page sizes and query ranges, with stable ordering. Mutations validate permissions and concurrency versions. Publication, rollback and ticket transitions use operation IDs to prevent duplicate execution. Long-running publication returns a job ID, preferably with `202`; the page queries its outcome instead of keeping an HTTP request open throughout embedding.

Label cost as an estimate. Missing provider usage or model pricing means unknown or incomplete, not zero cost; provider invoices determine actual settlement. Preserve current budget settlement semantics rather than implicitly introducing reservations through reporting.

## Login, permissions and audit

Prefer the organization's existing identity provider. Determine the identity source before implementation; a general identity platform is outside the first release. If using server-side sessions, configure secure cookies, expiry, logout invalidation and CSRF protection for mutations. Management APIs return `401` when unauthenticated and `403` when unauthorized. Revoked permissions must prevent actions from an already-open page.

Audit knowledge publication, rollback, ticket changes and permission changes with actor, object, action, time, outcome and necessary change summaries. Ordinary operators cannot modify audit records. Conversation-content access must also be traceable. Continue excluding customer messages, secrets and complete sensitive tool arguments from application logs, metric labels and trace attributes. Publication and permission changes must not silently succeed without reliable audit recording.

## Delivery and acceptance

| Stage | Deliverable | Completion criteria |
| --- | --- | --- |
| 1: Entry point and permissions | Admin shell, login, predefined roles, audit foundation, enabled only in `all` | Server rejects unauthenticated and unauthorized access; role changes and logout invalidation are verified |
| 2: Conversations and feedback | Persistent records, paginated search, detail, reporting and handling | Success, failure and interruption are inspectable for both chat APIs; historical references remain stable |
| 3: Knowledge publication | Drafts, candidate index, preview, atomic switch, rollback and initial migration | Failure preserves the previous version; concurrent publication cannot overwrite newer results; restarts preserve managed content |
| 4: Ticket workflow | Search, assignment, state machine, notes and concurrency control | AI creation through human closure and reopening is traceable; duplicates and conflicts are handled correctly |
| 5: Enhancements | Operational overview and split deployment support where needed | Metrics have explicit definitions; split mode passes the same business scenarios and cross-process failure tests |

Stages 1–4 together constitute the first operational release. Each can ship as a separate PR, but the page shell alone is not a complete admin interface. Split deployments must wait for the stage 5 adaptation before enabling it.

During implementation, add meaningful tests for permissions, state transitions, publication failures and concurrency, inspecting database outcomes. Run the repository's `./mvnw verify`; database tests use Testcontainers and require no live model API key. Remeasure performance if the chat execution path changes, and add multi-process verification for split deployment support.

UI acceptance must complete both workflows defined at the start and cover empty lists, insufficient permissions, save conflicts, publication failures and retries. Knowledge acceptance includes Chinese and English retrieval scenarios and representative answers, beyond a successful indexing job.

The Java/Go comparison requires identical bundled FAQ content and system prompts. This proposal extends Java management capabilities only. Keep operator-maintained data separate from comparison fixtures and do not change the bundled corpus on one side as part of admin development. Identity source, conversation retention and existing ticket-system integration must be settled before implementation; they do not block review of this proposal.
