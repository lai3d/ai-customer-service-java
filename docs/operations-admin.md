# Operations admin

Status: built and merged, in two rounds. The first slice -- staff login and the ticket loop
-- landed as PRs #22, #24 and #26 on 2026-09-05; the rest of the proposal's first release --
the turn record, conversations, answer feedback, knowledge editing and publication, and the
overview -- as PRs #28, #30, #31, #33, #34 and #35 on 2026-09-06, with #32 fixing the test
infrastructure in between. This document is in three parts: the record of the first round,
the record of the second, and then the proposal as reviewed in PR #16, kept as written,
because the departures only mean something against the text they depart from.

## The record (2026-09-05)

### What was decided

The proposal's first release was five pages plus login and audit. The owner cut the first
slice to **one workflow, complete**: a ticket the AI created is claimed, handled and closed by
a person, with the originating conversation visible. Four decisions, all in the owner's
session on 2026-09-05:

1. **Scope: the ticket loop only.** List and detail with filters; claim, assign, note, change
   state; every transition attributed to an actor and a time; the conversation behind the
   ticket, as recorded today. No FAQ editing, no answer feedback, no live takeover, no
   operational overview in this slice.
2. **Staff login ships with it**, because the admin shows customer conversation content.
   Spring Security form login, bcrypt accounts in Postgres, two roles (`admin`, `support`),
   permissions enforced on the server. This crosses CLAUDE.md's "no customer authentication
   without asking" deliberately; it is *staff* authentication, and the public chat endpoints
   are untouched.
3. **The page is in the demo page's style**: one static file under `/admin`, `fetch` against
   `/admin/api/**`, no frontend build chain. Re-confirmed by the owner when asked on
   2026-09-05 whether to separate the frontend: the interface is already separated (the API
   only speaks JSON); the deployment is not, and stays that way.
4. **No new deployment role.** The admin lives in the chat role, in `all` and `chat`
   processes; its tables are Flyway migrations in the same schema.

Two more, taken after the Go implementation's live walk of its own admin
(`ai-customer-service-go`, `docs/operations.md` there): opening a customer conversation is
recorded as an action, and refused actions are recorded too.

### What was built

| Piece | Where | PR |
| --- | --- | --- |
| Staff accounts (`staff_account`, bcrypt with Spring Security's `{id}` prefix), form login on `/admin/**` only, sessions in Postgres (Spring Session JDBC, `V4`), CSRF via a readable `XSRF-TOKEN` cookie, `401`/`403` by path, the first admin seeded by `ADMIN_SEED_USERNAME`/`ADMIN_SEED_PASSWORD` into an empty table only | `admin/`, `V4__staff_accounts.sql` | [#22](https://github.com/lai3d/ai-customer-service-java/pull/22) |
| The workflow model: `support_ticket` gains `state`, `owner`, `updated_at`, `version`; `ticket_event` is the append-only history; `TicketWorkflow` in `ticket/api` is the seam; `JdbcTicketWorkflow` is one transaction shape for every change | `ticket/`, `V5__ticket_workflow.sql` | [#24](https://github.com/lai3d/ai-customer-service-java/pull/24) |
| `/admin/api/tickets`: queue, detail with history, seven actions, the conversation behind a ticket; the page; `TicketWorkflowController` and `HttpTicketWorkflow` as the seam for the split topology; `admin_audit` | `admin/`, `ticket/`, `clients/`, `V6__admin_audit.sql` | [#26](https://github.com/lai3d/ai-customer-service-java/pull/26) |

The state machine, as built:

```
open ──claim / assign──▶ claimed ──resolve──▶ resolved ──close──▶ closed
  ▲                        │  │                   │                  │
  └────────release─────────┘  └───────close───────┘                  │
  ▲                                                                  │
  └───────────────────────────reopen─────────────────────────────────┘  (also from resolved)
```

Claiming is first come, first served on an unowned open ticket, atomic across replicas by a
row lock. Release, resolve, close and reassign are the owner's, or an admin's. Reopening
clears the owner: a reopened ticket goes back to the queue. Every mutation carries the version
the page read; a stale one is `409` and writes nothing, which is also what makes a
double-submitted form land once. Resolving requires a conclusion, stored on the resolving
event and never on the ticket row.

What the conversation view shows is what is persisted: the messages in
`spring_ai_chat_memory`, with their types and times, and the tickets raised in that
conversation. It says, above the transcript, that retrieval evidence and tool results are not
persisted -- they exist only in the SSE stream of the turn that produced them -- and that
the messages are the model's windowed memory, not a complete operational record. That is the
proposal's stage 2, not this slice.

### Where it departs from the proposal

| The proposal said | What was built | Why |
| --- | --- | --- |
| `/admin` for pages, `/api/admin/v1/**` for endpoints | `/admin` and `/admin/api/**` | One path prefix, one security filter chain bound to it with `securityMatcher`; nothing else in the application passes through Spring Security, and `AdminLoginTest` asserts that |
| Prefer the organisation's identity provider; three roles | Local bcrypt accounts; `admin` and `support` | There is no identity provider to prefer; a third role (knowledge operator) has nothing to operate on in this slice |
| `OPEN → IN_PROGRESS → RESOLVED → CLOSED`; reopen into `IN_PROGRESS` with a mandatory reason | `open → claimed → resolved → closed`; reopen goes to `open`, unowned, reason optional as a note | A reopened ticket is nobody's until claimed again; sending it back to its last owner presumes that person is still the right one. The conclusion is mandatory on resolve; the reason on reopen is not, and a note carries it when there is one |
| Deduplicate admin mutations by operation id | Version checks only | The second copy of a double-submitted request carries the version the first one moved past and is a `409`; history gets one row. Operation ids stay where an ambiguous timeout is the problem, the tool path |
| Disable the admin entry point in split deployments for the first release; `chat` must not read or write ticket tables directly | The seam was built: `/internal/v1/ticket-workflow` in a `ticket` process, `HttpTicketWorkflow` in a `chat` process | A `chat` process with the admin and without a `JdbcTicketWorkflow` would not have started at all; the seam was the smaller change, and `TopologyParityTest` proves the two answer alike, refusals included. The actor crosses in the command body and is trusted because the bearer token authenticates the calling `chat` process; the `ticket` process does not re-check roles, which is the proposal's propagation protocol in its simplest form |
| Audit ticket changes and permission changes | `ticket_event` holds every change to a ticket; `admin_audit` holds conversation views and refused actions | A change to a ticket *is* its history; a second copy in an audit table would be a denormalisation. What `ticket_event` cannot hold is what did not change a ticket: a view, and a refusal |
| Show retrieval and tool execution evidence | Shown as not persisted | Nothing records them today; a panel that is empty implies they were recorded and lost |

### What was found

- **CI dies at the sixteenth Spring test context holding an ONNX session, and does not say
  so.** PR #22's job was killed twice at the same test class with every test green and the
  step reading "The operation was canceled"; `./mvnw clean verify` passed locally both times.
  Main's green run loads the embedding model 15 times across cached contexts; the new login
  test's own properties made a 16th. The test now shares `CustomerServiceApplicationTests`'
  configuration and sets up its data through beans; every later admin test does the same.
  CLAUDE.md records the ceiling. The count is a ceiling of the hosted runner's memory, not of
  anything in the code, and the same test suite with one more `@SpringBootTest` configuration
  will find it again.
- **`updated_at` from the column default was earlier than `created_at`.** `now()` is the
  transaction's start; the JVM's `Instant.now()` a few milliseconds later. The first assertion
  written against a fresh ticket found it. `JdbcTicketOperations` now writes both from the
  same clock.
- **Browsers compile the HTML `pattern` attribute with the `v` regex flag**, where a `-`
  inside a character class must be escaped. Found by driving the page in a real browser, not
  by any test; `[a-z0-9._-]` became `[a-z0-9._\-]`.
- **A resolution stored on the ticket row survives a reopen.** The Go side found this in its
  live walk: reopening a resolved ticket resubmitted the old conclusion. The Java model had no
  conclusion at all, the same defect one step earlier. The conclusion now lives on the
  resolving `ticket_event`; nothing on the row remembers it, so reopen has nothing to carry,
  and every conclusion a ticket ever had stays in its history. The Go side kept its column and
  recorded the difference rather than converging.
- **An audit trail of what succeeded is missing the rows an investigation opens it for.**
  Also the Go side's: a viewer's write returned a correct `403` and left no trace, because the
  deny path returned before anything recorded it. `admin_audit` records refusals by rule
  (`422`, from the workflow) and by role (`403`, from the access-denied handler, with the
  method and path). A lost race (`409`) is not a refusal and is not recorded.
- **Spring Security chooses the "not signed in" response by `Accept` header, and a `fetch()`
  without one is sent to the login page.** The admin's entry point is chosen by path instead:
  `/admin/api/**` answers `401`, everything else redirects. The test client had no `Accept`
  header, which is how this surfaced before a browser did.
- **Signing in rotates the CSRF token.** The old cookie is cleared on authentication and a new
  one is issued on the next request that reads it. A browser's page load does that read; the
  test's browser helper had to be taught to.
- **Markdown shown to an operator as literal asterisks.** The Go side's first live-walk
  finding, in its conversation view; the Java view was built after and renders assistant text
  through the demo page's subset (bold, inline code, hyphen lists), DOM nodes only, no links,
  so an operator reads what the customer saw.

### What is not built

- Disabling an account, changing a role, resetting a password. `enabled` is stored and
  honoured at login; nothing sets it yet.
- A conversation list, answer feedback, knowledge editing and publication, the operational
  overview: stages 2, 3 and 5 of the proposal below, each its own PR series. Knowledge editing
  in particular changes `faq.json`, the one fixture that keeps the Java and Go retrieval
  numbers comparable; both sides have agreed to leave it untouched until that stage is
  designed.
- Filtering the queue by created time on the page; the API takes `from` and `to`.
- The resolve dialog is a browser `prompt()`, single-line.
- Session timeout is idle-based, 30 minutes by default (`ADMIN_SESSION_TIMEOUT`); there is no
  absolute lifetime and no concurrent-session limit.

### Operating it

`ADMIN_SEED_USERNAME` and `ADMIN_SEED_PASSWORD` create the first admin at startup, only
when `staff_account` is empty; they never overwrite or reset an account and are safe to leave
set; one without the other refuses to start. Read by `all` and `chat` processes. After that
admin has signed in, accounts are created in the page. On Kubernetes the two go in the same
Secret as the API key; see [Deployment](deployment.md#kubernetes). If the first admin's
password is lost, delete its row and restart with the seed set, or insert a row by hand with
a bcrypt hash carrying the `{bcrypt}` prefix.

## The record, second round (2026-09-06)

### What was decided

On 2026-09-06 the owner asked for every remaining slice ("后续切片都做了吧"). Two premises
were stated and not objected to: the bundled corpus stays untouched, since it is the fixture
the Java and Go retrieval numbers are compared on, so managed knowledge lives in new tables
and the bundled file is only ever adopted as a version; and each PR merges when its CI is
green without a separate ask. Asked whether the front end should now be separated, the owner
kept the static page over JSON: the interface is separated, the deployment is not.

### What was built

| Piece | Where | PR |
| --- | --- | --- |
| The turn record: `conversation_turn`, `turn_retrieval`, `turn_tool_call`, written at the service boundary on both chat paths; the first row before the model and able to refuse the turn; outcomes `completed`, `failed`, `interrupted`, `unknown`; a sweeper for what a dead process left `running` | `chat/TurnRecorder`, `chat/TurnRecordSweeper`, `V7` | [#28](https://github.com/lai3d/ai-customer-service-java/pull/28) |
| Conversations in the admin: a list from the record with turn counts and outcomes, filters by id, outcome and time; a conversation as its turns with retrieval, tools, cost, trace, failure; views recorded | `chat/TurnRecords`, `admin/AdminConversationController` | [#30](https://github.com/lai3d/ai-customer-service-java/pull/30) |
| Answer feedback: a flag on a recorded turn, handled with a required conclusion or dismissed, once, versioned; on the conversation detail and in a list | `admin/AnswerFeedback`, `V8` | [#31](https://github.com/lai3d/ai-customer-service-java/pull/31) |
| One Postgres container per test JVM, a database per context | `PostgresTestcontainer` | [#32](https://github.com/lai3d/ai-customer-service-java/pull/32) |
| The knowledge model: entries, per-language revisions (`draft`, `published`, `superseded`), versions as immutable snapshots with a `corpus_version` in the vector store, one active row switched under a lock with an expected-version check, rollback, retention of the newest three, the bundled corpus adopted without re-embedding, retrieval confined to the active version through a `VectorStore` decorator, readiness on the active version | `rag/api/KnowledgeAdmin`, `rag/JdbcKnowledgeAdmin`, `rag/ActiveVersionVectorStore`, `rag/KnowledgeBootstrap`, `V9` | [#33](https://github.com/lai3d/ai-customer-service-java/pull/33) |
| Knowledge in the admin: drafts for staff, retire, publish and rollback for admins, publication as a `202` job polled through the version row, preview against any retained version, the `/internal/v1/knowledge-admin` seam for the split, publications and rollbacks in `admin_audit`, a handled flag naming the revision that fixed it | `admin/AdminKnowledgeController`, `rag/KnowledgeAdminController`, `clients/HttpKnowledgeAdmin`, `V10` | [#34](https://github.com/lai3d/ai-customer-service-java/pull/34) |
| The overview: turns by outcome with a failure rate that leaves interruptions out, tokens with unmetered turns counted apart, tickets by state with minutes to first claim and to resolution, flags, the knowledge base's state, what staff did; every number with its definition | `admin/AdminOverview` | [#35](https://github.com/lai3d/ai-customer-service-java/pull/35) |

Every piece works in both topologies: what a `chat` process cannot do locally it does over
the seams (`/internal/v1/ticket-workflow`, `/internal/v1/knowledge-admin`, and the
existing search seam, which now takes a version), and `TopologyParityTest` drives each of
them from the chat process against the other processes' rows.

### Where it departs from the proposal

| The proposal said | What was built | Why |
| --- | --- | --- |
| Persist a stable turn id with outcome, both chat paths; distinguish completed, failed, interrupted and incompletely recorded | Done as written, with `unknown` as the name for "incompletely recorded": a row still `running` past the turn lease, marked by the sweeper at startup and every minute | Nothing knows how such a turn ended, and a name that says so is better than a guess |
| Store customer-visible messages and the knowledge versions referenced, with snapshots | Question and answer are snapshots on the turn; retrieval rows carry entry, language and score. The corpus version is in the passages' metadata but not yet on the retrieval row | One column, when a turn needs to be tied to a version; the rows already say what was found |
| Retention policies for memory and records, decided before implementation | Not decided. `conversation_turn` is customer text and is subject to whatever retention memory gets; the record says so in its migration | A retention decision is the owner's; the tables are built so it can be one statement |
| Bind feedback to a turn and optionally to a knowledge revision and conclusion | Done, with the revision link arriving one PR after the flag, once revisions existed | Order of construction |
| Publication as a job with `202`, the page polling | Done; the version row is the job, there is no separate job table | One row already had every state a job needs |
| Pin each retrieval to a version and record it with the turn; requests started before a switch keep reading the previous version | Retrieval filters by the active version at query time, and a switch is one row; a request that started before the switch reads the version it started on because its search already ran. Deleting a retired version's documents waits for retention, which never touches the active or the newest three | Same guarantee, no reservation bookkeeping |
| Initial migration from the bundled FAQ to the first managed version; restarts must not overwrite operator-maintained content | The bundled corpus is adopted as a version without re-embedding; a newer bundled corpus on upgrade becomes `ready`, not active, and never overwrites managed text | `faq.json` stays the comparison fixture |
| Three roles | Still two; drafts for everyone signed in, retire, publish and rollback for admins | Nothing in this slice needed a third |
| An overview starting with volume, failure rate, feedback volume, pending tickets and model usage; satisfaction, resolution and takeover rates only after the events exist | As written, and cost is left to the meters | The proposal's own rule, kept |

### What was found

- **The CI ceiling was Postgres containers, not the model.** #30's job was killed the way
  #22's was, at the same class, with every test green. Each cached Spring test context
  started its own Postgres container and kept it for the JVM's life, so a run held about
  fifteen servers beside fifteen embedding models. One container per JVM with a database per
  context (#32) took the suite from over two minutes to under a minute locally and removed
  the ceiling; the next wall was Postgres's default of 100 clients, since every cached pool
  stays open, so the container allows 500 and pools idle at one. CLAUDE.md's first version
  of this finding blamed the ONNX session; it is corrected.
- **CI's test order is not this machine's.** A `DELETE FROM conversation_turn` in one test's
  setup passed locally and tripped a foreign key in CI because the feedback test had run first
  there and left flags pointing at turns. Shared-context tests delete in dependency order now.
- **A test that publishes must not share a database with tests that measure retrieval.** The
  first run of the knowledge test, in the retrieval tests' context, changed their row counts
  and their document ids. It has its own context, deliberately, which the container fix made
  affordable.
- **`doFinally` runs after the terminal signal has been handed downstream.** Finishing the turn
  record there let a client that blocked for the end of the stream read the row before it was
  written; the test found it as a race on its second run. The record is finished on
  `doOnComplete`, `doOnError` and `doOnCancel`, which run before the signal is propagated.
- **Spring AI reports a failed model stream as "Stream processing failed"** and keeps the
  provider's reason one level down. The recorded failure carries both.
- **`findFirst` refuses a null.** The one row of `knowledge_active` has a null version until
  something is active; reading it with a stream broke every context with no active version.
  Filtered rather than found.
- **A decorator bean makes an auto-configuration back off.** Wrapping the pgvector store to
  confine searches to the active version could not be a `VectorStore` bean of its own, since
  `PgVectorStoreAutoConfiguration` would then have had nothing to wrap; a
  `BeanPostProcessor` wraps the auto-configured bean in place.
- **A publication started off the request thread can be polled before its row exists.** The
  first API test waited for "no version building" and returned at once. The wait is for the
  publication's own row to appear and settle.

### What is not built

- Retention for `conversation_turn` and chat memory: a decision, then one statement.
- The corpus version on a turn's retrieval rows.
- Disabling an account, changing a role, resetting a password.
- The Go side's `docs/operations.md` records the deliberate differences between the two
  implementations; that document, not this one, is where the comparison lives.

### Operating it

Nothing new in the environment. A `knowledge` process now also serves
`/internal/v1/knowledge-admin` and a `ticket` process `/internal/v1/ticket-workflow`, both
behind the same bearer token and reachable only from `chat` pods under the roles
NetworkPolicy. Publishing embeds every managed entry in every language on the knowledge
role; for the bundled corpus that is 36 documents and about a second. Retained versions
keep their documents in `vector_store`; retention removes them after the newest three.

---

# The proposal, as reviewed (PR #16)

Status when written: proposed, not implemented. This section defined product scope and implementation stages; it did not change the existing deployment decisions. It is kept as written; the record above says what was built and what departs from it.

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
