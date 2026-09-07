# ADR 002: Tenants, and a tenant's own knowledge

- **Status:** accepted 2026-09-07; being built. Step 2 of the plan (the tenant model) is
  merged; steps 3 to 5 follow. Where the build departed from the text: existing conversations
  kept their id as both external and internal rather than being rewritten, so the budget and
  lease tables needed no tenant column; staff scoping and the `platform` role moved from step
  2 to step 3, since every admin is a platform admin until knowledge is per tenant. The first engineering step of
  [BUSINESS-PLAN.md](../../../BUSINESS-PLAN.md) in the workspace root: a customer integrates
  by bringing their own knowledge, and that requires the system to know whose knowledge, whose
  conversations and whose tickets it is holding.
- **Supersedes:** the scope line in CLAUDE.md that kept multi-tenancy out, on the owner's ask.
- **Builds on:** [ADR 001](001-deployment-targets.md) (the roles and their seams), the
  knowledge-version design and the staff login recorded in `docs/operations-admin.md`.

---

## Contents

- [Context](#context)
- [Decision in one paragraph](#decision-in-one-paragraph)
- [Tenant identity on the public API](#tenant-identity-on-the-public-api)
- [A conversation belongs to a tenant, and its id is ours](#a-conversation-belongs-to-a-tenant-and-its-id-is-ours)
- [Scoping every table](#scoping-every-table)
- [Knowledge per tenant](#knowledge-per-tenant)
- [Staff per tenant, and one role above them](#staff-per-tenant-and-one-role-above-them)
- [Ingesting a tenant's documents](#ingesting-a-tenants-documents)
- [Cost, budgets and metrics](#cost-budgets-and-metrics)
- [The seams](#the-seams)
- [Where the single-tenant assumptions are](#where-the-single-tenant-assumptions-are)
- [What does not change](#what-does-not-change)
- [Deferred, deliberately](#deferred-deliberately)
- [Plan](#plan)
- [Open questions for review](#open-questions-for-review)

---

## Context

Everything on `main` is single-tenant by construction: one `knowledge_active` row with
`id = 1`, staff accounts with no owner, a public chat endpoint that accepts any
conversation id a client chooses and echoes it back, a bundled corpus that is *the* corpus.
That was right for an engine being compared across three languages. It is wrong for a
product whose first customer brings a second corpus, because the second customer's
conversations, tickets and passages must be invisible to the first, and today nothing
enforces that -- there is no notion of "first" and "second" to enforce it on.

The one place this is a security matter rather than a modelling one: conversation ids are
client-chosen and looked up by value alone. With two customers behind one deployment, a
client that guesses another's conversation id reads its history and appends to it. Tenancy
is therefore not a column added for reporting; it is the key every lookup has to include.

## Decision in one paragraph

A `tenant` table and a `tenant_id` on every row that belongs to a customer. A tenant is
identified on the public API by an API key, resolved once per request by a filter into a
request-scoped tenant context that every service reads and no request body carries.
Conversation ids become internal UUIDs, mapped from `(tenant_id, external_id)` in a
`conversation` table, so a client-chosen id is scoped to its tenant and every other table
references an id the client never chose. Knowledge entries, revisions, versions and the
active pointer carry the tenant; `knowledge_active` becomes one row per tenant; vector-store
documents carry the tenant in metadata and retrieval filters on tenant AND version. Staff
accounts belong to a tenant, and a tenant-less `platform` role creates tenants, issues keys
and seeds a tenant's first admin. Ingestion adds documents to a tenant's knowledge as draft
entries through the existing publication flow, from a URL or a PDF, using Spring AI's
document readers. The bundled corpus becomes the `default` tenant's first version and the
default tenant is what a fresh install has, so nothing that works today stops working.

## Tenant identity on the public API

- `tenant` (`tenant_id` text primary key, `name`, `created_at`, `enabled`) and
  `tenant_api_key` (`key_id` text primary key, `tenant_id`, `key_hash` sha-256, `label`,
  `created_at`, `revoked_at`). A key is shown once at issue time and stored hashed; the
  first 8 characters are its `key_id`, so lookup is by prefix and compare is of the hash.
- `Authorization: Bearer <key>` on `/api/v1/**`. A plain servlet filter, outside Spring
  Security's admin chain -- `AdminLoginTest.publicEndpointsAreNotBehindTheLogin` asserts the
  public side sees no session and no CSRF, and that stays true -- resolves it to a
  `TenantContext` (request-scoped; a `ThreadLocal` bound for the request and cleared after,
  because the SSE stream runs on Reactor threads and the tool calls on Spring AI's scheduler
  -- the tenant travels in the tool context and the advisor context the way the turn id
  already does, not through the thread). No key, a revoked key, a disabled tenant: `401`,
  before any model call, with a `ProblemDetail`.
- The demo page asks for a key once and keeps it in `localStorage`; the Compose stacks, the
  smoke script and the kind harness send the default tenant's key, seeded from
  `DEFAULT_TENANT_API_KEY` into an empty `tenant_api_key` table the way the first admin is
  seeded today. Without that variable a fresh install has a default tenant and no way to
  talk to it, and says so at startup.
- Internal endpoints (`/internal/**`) keep the service token; the tenant is a field in
  their request bodies, set by the chat side from its context, never trusted from a client.

## A conversation belongs to a tenant, and its id is ours

- `conversation` (`id` uuid primary key, `tenant_id`, `external_id` text, `created_at`,
  unique `(tenant_id, external_id)`). The public API keeps its contract: `conversationId`
  in the request is the client's id, optional, echoed in `X-Conversation-Id`. The service
  resolves `(tenant, external)` to the internal id, creating the row on first use.
- Every table keyed by conversation -- `spring_ai_chat_memory`, `conversation_turn`,
  `conversation_budget`, `conversation_lease`, `conversation_ticket_guard`,
  `support_ticket`, `ticket_operation`, `answer_feedback` -- keys on the internal id. Spring
  AI's memory table cannot take a tenant column its repository does not know about; the
  internal id is what makes that unnecessary, and it fits the `varchar(36)` the schema
  declares.
- The migration creates the `default` tenant, adds `tenant_id` columns defaulting to it,
  and rewrites existing conversation ids to internal UUIDs with a `conversation` row for
  each, so history, tickets and feedback survive. Then the column defaults are dropped: a
  row without a tenant is a bug, not a default.

## Scoping every table

| Table | Change |
| --- | --- |
| `conversation` | new; the mapping above |
| `conversation_turn`, `turn_retrieval`, `turn_tool_call`, `answer_feedback` | `tenant_id` on the turn and the feedback; the children follow the turn |
| `support_ticket`, `conversation_ticket_guard`, `ticket_operation`, `ticket_event` | `tenant_id` on the ticket, the guard and the operation; the ticket number sequence stays global (a number is not a secret, and a per-tenant sequence buys nothing) |
| `conversation_budget`, `conversation_lease` | keyed on the internal id, so scoped already; `tenant_id` added for reporting |
| `knowledge_entry`, `knowledge_revision`, `knowledge_version`, `knowledge_version_document` | `tenant_id` on the entry and the version; the entry id becomes unique per tenant, not global |
| `knowledge_active` | primary key becomes `tenant_id`; the `id = 1` row becomes the `default` tenant's |
| `staff_account`, `admin_audit`, `spring_session` | `tenant_id` on the account and the audit row; sessions carry the tenant as a principal attribute |
| `corpus_import` | unchanged: it records the bundled corpus, which is the default tenant's |
| `vector_store` | no column; `tenant` in the JSON metadata, filtered like `corpus_version` is |

Every query that reads or writes one of these carries the tenant. The rule is enforced the
way the repository enforces its other rules: a test per module that writes as tenant A and
reads as tenant B and finds nothing, and an ArchUnit-style check that no repository method
takes a conversation id without a tenant (a `TenantScoped` marker on the query classes,
and a test that greps the SQL in them for the tenant predicate).

## Knowledge per tenant

- `ActiveVersionVectorStore` filters `tenant = ? AND corpus_version = ?`; `SearchQuery`
  gains `tenantId`; `ActiveKnowledgeVersion` caches one version per tenant.
- Publication, rollback, retention and preview in `JdbcKnowledgeAdmin` take the tenant from
  the staff caller's context: a publication snapshots one tenant's entries, retention keeps
  the newest three ready versions *per tenant* (today it is three over all versions, which
  with tenants would retire another tenant's), and `activate` keeps its `FOR UPDATE` on the
  tenant's own `knowledge_active` row. Entry ids are per tenant (`faq:returns-window` can
  exist for two tenants); document ids in the store become `tenant:entry:language`.
- `KnowledgeBootstrap` adopts the bundled corpus as the `default` tenant's first version and
  nobody else's. A new tenant starts with no knowledge, no active version, and readiness
  for the *deployment* stays "the default tenant has an active version": a tenant with
  nothing published gets grounded refusals, not a not-ready pod.

## Staff per tenant, and one role above them

- `staff_account.tenant_id`; a staff account sees and changes only its tenant's
  conversations, tickets, feedback, knowledge and staff. The admin API resolves the tenant
  from the authenticated principal, never from a parameter. Usernames stay globally unique
  and stay the primary key and the session principal: Spring Session's `principal_name`
  index, `StaffSessionPolicy`, the session-ending on account changes and the audit rows all
  key on the username, and a compound principal would touch every one of them for the sake
  of two tenants both wanting `alice`. Email-shaped usernames make the global uniqueness a
  non-issue in practice. The last-enabled-admin rule and its `FOR UPDATE` become per tenant.
- A third role, `platform`, has no tenant. It creates tenants, issues and revokes API keys,
  and seeds a tenant's first `admin`. It sees no customer content: the tenant pages are for
  the tenant's staff. The seeded first account (`ADMIN_SEED_*`) becomes a `platform` account
  plus the default tenant's `admin`, which is what the single-tenant install effectively
  had.
- The admin UI (the other session's React app) needs the tenant name on the account page and
  the platform pages for tenants and keys; the tenant-scoped pages do not change, because
  the API scopes by the caller.

## Ingesting a tenant's documents

- `POST /admin/api/knowledge/ingest` with either a URL or a multipart PDF. Spring AI's
  readers do the extraction (`spring-ai-jsoup-document-reader` for HTML,
  `spring-ai-pdf-document-reader` for PDF) and `TokenTextSplitter` the chunking, so no new
  dependency beyond the two readers.
- Each chunk becomes a draft `knowledge_entry` of category `document` in the tenant's
  knowledge: question = the chunk's heading or the document title plus an index, answer =
  the chunk text, language detected by script (CJK or not) and overridable. The existing
  flow then applies: drafts are previewed, published as a version, embedded under it,
  rolled back like any other. Nothing about retrieval changes; a document chunk is a
  passage the way an FAQ answer is.
- Source is recorded on the entry (`source_url` or the file name, fetched-at), and an
  ingest of the same source again replaces its chunks as new drafts rather than adding
  beside them, so a re-crawl is a publication, not a duplication.
- Fetching a URL is a server-side request to an address a tenant typed: the fetcher allows
  `http` and `https` only, refuses private and loopback ranges (an SSRF guard), follows at
  most three redirects, caps the body at a few megabytes, and times out. Written down as
  the constraint it is, with a test that feeds it `http://169.254.169.254/`.

## Cost, budgets and metrics

- `chat.tokens` and `chat.cost.usd` gain a `tenant` label. Cardinality is bounded by the
  number of tenants, which is bounded by the business; the label is dropped above a
  configured count (`app.tenancy.metrics-label-limit`, 200) rather than allowed to grow.
- The per-conversation token budget stays per conversation. A per-tenant budget is deferred
  with billing (below); the counters it would need exist after this change.
- `conversation_turn` already records cost per turn; per-tenant cost over a period is a
  `GROUP BY tenant_id` on it, which the admin overview shows to a tenant's staff for their
  tenant and to `platform` for all.

## The seams

- `SearchQuery` carries `tenantId`; the knowledge endpoint filters on it. `TicketRequest`
  carries `tenantId`; the ticket endpoint scopes on it. The chat side fills both from the
  request's tenant context. `TopologyParityTest` gains a case: tenant B's chat process
  cannot retrieve tenant A's passage or read tenant A's ticket through the seam.
- The `all` topology is unchanged in shape; the tenant travels the same way through local
  calls.

## Where the single-tenant assumptions are

An inventory from the session that wrote the knowledge model and the staff login, checked
against the code, so the implementation PRs have a list rather than a search:

| Where | Assumption | What changes |
| --- | --- | --- |
| `ActiveKnowledgeVersion` | one cached value, `WHERE id = 1` | a cache keyed by tenant; the seam carries the tenant so a `chat` process's cache is keyed the same way |
| `ActiveVersionVectorStore`, `SearchQuery`, `LocalKnowledgeSearch`, `HttpKnowledgeSearch`, `KnowledgeController` | the filter is `corpus_version == active`; the query has no tenant | `tenant == ? AND corpus_version == ?`; the preview path (a `version` given) still filters on tenant |
| `JdbcKnowledgeAdmin` | `activate` locks `id = 1`; retention is over all versions; `publish` snapshots all entries | all three per tenant |
| `KnowledgeBootstrap` | the bundled corpus is *the* corpus; adoption when `knowledge_active.version IS NULL` | the default tenant's corpus; adoption when its row has no version; the "newer bundled version becomes ready, not active" path stays per that tenant |
| `CorpusReadinessIndicator` | readiness = the one active version has documents | readiness = the default tenant's; a tenant without an active version gets grounded refusals, not a not-ready pod |
| `AdminOverview` | `knowledge_active WHERE id = 1` | the caller's tenant |
| `TurnRecorder`, `turn_retrieval.corpus_version` | version per retrieval row | unchanged; the tenant is on the turn |
| `hnsw.iterative_scan = strict_order` | covers the version filter's selectivity | matters more with a tenant filter ANDed in; `KnowledgeAdminIntegrationTest.hnswStillReturnsKAfterChurn` re-run with two tenants' documents in one index |
| `staff_account`, `StaffSessionPolicy`, `AdminStaffController.endSessionsOf`, `admin_audit` | username is the primary key and the session principal | unchanged, see above; `tenant_id` added, the last-admin lock per tenant |
| `StaffSeeder` | one admin into an empty table | a `platform` account plus the default tenant's admin |
| `AdminSecurityConfiguration` | one chain on `/admin/api/**`, no tenant | unchanged; the tenant is a principal attribute |
| conversation-keyed tables, `varchar(36)` | client-chosen ids, global | the `conversation` mapping; a tenant prefix inside 36 characters would not fit a UUID, which is why it is a table |
| `ConversationRetentionSweeper`, `ConversationBudget.sweep` | global by age | unchanged; one retention policy, a column if a tenant ever needs its own |
| `TurnEventBus`, `ChatProperties` | keyed by turn, no per-tenant budget | unchanged |
| the Go and .NET parity fixtures | the bundled corpus is the corpus | they run as the default tenant, which is the bundled corpus |

## What does not change

The advisor chain, `TurnEventBus`, the tool contract, the lease, the importer, the
observability stack, the benchmark path (which runs as the default tenant with a fresh
conversation per request, as before, plus one key lookup). `docker compose up` still works:
the default tenant exists, the demo page asks for the seeded key.

## Deferred, deliberately

| Item | Condition that would reopen it |
| --- | --- |
| Per-tenant budgets and billing | The first paying customer; the counters exist after this change |
| A tenant's own model provider or key | A customer who brings their own OpenAI or Anthropic account |
| Tenant-specific system prompt or tone | A customer who asks; today the prompt is one and grounded |
| Database or schema per tenant | A regulatory requirement, or a tenant large enough to need its own pgvector index; `vector_store` is one table and one HNSW graph |
| More document sources (Notion, Feishu, help-center APIs) | The first pilot that uses one; URL and PDF cover the pilot's first week |
| Channels beyond the public API (web widget, WhatsApp, WeChat) | Step 2 of the plan |

## Plan

Each a pull request that leaves the suite green and the default tenant behaving as the
single tenant did. Estimates are Claude session hours.

| # | Step | Hours |
| --- | --- | --- |
| 1 | This ADR | 0.5 |
| 2 | The tenant model: tables, the migration that adopts existing rows into `default`, the API-key filter and tenant context, the `conversation` mapping, every query scoped, isolation tests per module, the `platform` role and its endpoints, the smoke scripts and the demo page sending a key | 5–7 |
| 3 | Knowledge per tenant: filters, per-tenant active pointer, bootstrap for `default` only, the seams carrying the tenant, the parity test case | 2 |
| 4 | Ingestion: URL and PDF into draft entries, the SSRF guard, re-ingest as replacement, tests with a local HTTP server and a small PDF | 3–4 |
| 5 | Docs: this ADR marked built, CLAUDE.md's scope line and a tenancy section, `docs/operations-admin.md` record, `BUSINESS-PLAN.md` step 1 done | 1 |

## Open questions for review

For the operations-admin session, which owns the knowledge model and the admin UI, and for
the owner:

1. Is `conversation` as a mapping table with internal UUIDs acceptable, given that the
   admin's conversation views and the retention sweeper key on conversation ids today?
2. `knowledge_active` keyed by tenant: does the version design assume a single active row
   anywhere beyond `id = 1` and `KnowledgeBootstrap`?
3. The `platform` role as tenant-less versus "an admin of the default tenant can do
   platform things": the former is cleaner, the latter is less code. This ADR proposes the
   former.
4. Document chunks as draft entries of category `document`: does anything in the admin UI
   assume every entry is a question-and-answer pair?
