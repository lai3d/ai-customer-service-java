# Codex Repository Review Report

- **Reviewer:** OpenAI Codex
- **Review origin:** Independent Codex review; findings and priorities represent Codex's analysis and have not yet been validated by Claude Code or Grok.
- **Reviewed commit:** `531b18f` (`main`)
- **Review date:** 2026-09-04
- **Scope:** Application code, tests, browser UI, Docker Compose, and Kubernetes manifests.

## Executive Summary

The repository is well documented and its normal verification suite is healthy, but the review found two high-priority concurrency/state-management issues and four medium-priority operational or lifecycle issues. The most urgent problem is that two simultaneous streaming turns using the same conversation ID can cross-wire events and leave one SSE response open forever.

Claude Code, Grok, and any other reviewer should independently validate each finding and severity. This report is intended as one input to a multi-reviewer comparison, not a shared or consensus conclusion. Add a regression test before changing behavior.

## Findings

### P1 — Concurrent turns can cross-wire and hang SSE streams

**Evidence:** `TurnEventBus.java:30-49`, `ChatService.java:107-113`.

`TurnEventBus.open()` stores one sink per conversation using unconditional `Map.put()`. If stream A and stream B overlap for the same conversation, B replaces A's sink. When A finishes, `close(conversationId)` removes and completes B's sink, while A's original sink remains open. Tool events can also be delivered to the wrong turn.

**Impact:** A response may heartbeat indefinitely, events may appear on another request, and concurrent memory writes may become incorrectly ordered.

**Suggested direction:** Give every turn its own channel/turn ID and close the exact channel handle that was opened. Alternatively, enforce one in-flight turn per conversation atomically and return a clear conflict response. Add a test with two overlapping streams sharing one conversation ID.

### P1 — Ticket deduplication and limits are neither distributed nor atomic

**Evidence:** `SupportTicketTools.java:52`, `SupportTicketTools.java:82-109`, `k8s/deployment.yaml:10`, `k8s/service.yaml:22-24`.

Ticket state is held in an instance-local `ConcurrentHashMap`, while the supplied deployment runs two replicas with no session affinity. A conversation routed to another replica loses its deduplication and three-ticket limit. Within one replica, distinct concurrent summaries can all pass `ticketsFor(...).size() < 3` before any inserts complete.

**Impact:** Duplicate tickets and more than three tickets per conversation are possible despite the documented safety boundary.

**Suggested direction:** Store idempotency keys and ticket counts in shared transactional storage. Enforce duplicate prevention with a unique constraint and perform capacity checking/insertion atomically. If this is intentionally mock-only behavior, make that limitation explicit wherever the cap is presented as a hard control.

### P2 — Docker Compose does not propagate documented configuration

**Evidence:** `docker-compose.yml:54-70`, `application.yml:237-242`, `.env.example:12-34`.

The app service passes only the Anthropic key and database variables. Compose reads `.env` for interpolation but does not automatically inject undeclared variables into the container. Therefore `CHAT_PROVIDER`, OpenAI/Gemini credentials, model overrides, budgets, timeouts, and tracing settings are ignored. In particular, tracing keeps its default `enabled=false` and the bundled Jaeger receives no spans.

**Verification:** `docker compose config --format json` showed both `OTLP_TRACING_EXPORT_ENABLED` and `OTLP_TRACING_ENDPOINT` as absent from `services.app.environment`.

**Suggested direction:** Explicitly pass supported variables (preferably selectively), and set the Compose tracing endpoint to `http://jaeger:4318/v1/traces`. Update stale comments/docs that still claim a missing Anthropic key allows a healthy startup; `ChatProviderCredentialsValidator` now prevents that.

### P2 — Cancelled or failed streams bypass token accounting

**Evidence:** `ChatService.java:116-138`, especially the completion-only `concatWith` at line 135.

`budget.record(...)` runs only after the model event stream completes normally. Cancellation and error skip the supplier, even if usage metadata was already captured. Repeated aborted requests can therefore evade the conversation budget and under-report global token/cost metrics.

**Suggested direction:** Record captured usage exactly once for every terminal signal. Where the provider supplies no final usage on cancellation, consider reservation/estimation or clearly document the accounting limitation. Add cancellation and error tests that assert budget state.

### P2 — FAQ replacement is visible and non-atomic

**Evidence:** `FaqIngestionService.java:63-69`, `k8s/deployment.yaml:10-20`.

Startup ingestion deletes all FAQ rows before embeddings are generated and replacements are inserted. Existing replicas share the same PostgreSQL vector store, so they can observe an empty corpus during a rollout. If embedding or insertion fails after deletion, the corpus remains empty even though older application replicas are still serving traffic.

**Suggested direction:** Stage/upsert the new version first and delete stale rows only after success, or wrap a prepared replacement in an appropriate transaction. Test failure between delete and add, plus concurrent reads during ingestion.

### P2 — Resetting the UI races with an active request

**Evidence:** `src/main/resources/static/index.html:247-265` and `342-350`.

“New conversation” clears `conversationId` but does not abort the current fetch. If reset happens before response headers arrive, the old request later writes its old conversation ID back into global state. The next visible message then silently continues the supposedly discarded conversation.

**Suggested direction:** Use an `AbortController` and a request-generation token so stale handlers cannot update global state or detached DOM. Disable or explicitly define reset behavior while a request is active, and add a browser-level regression test.

## Verification Performed

- `./mvnw verify`: **102 tests passed**, 0 failures, 0 errors, 0 skipped.
- `docker compose config --format json`: confirmed missing app-container OTLP and alternative-provider variables.
- Static tracing covered controller → service → advisor/tool event bus, budget accounting, FAQ ingestion, UI lifecycle, and deployment topology.
- No production source files were modified as part of the review.

## Recommended Order

1. Reproduce and fix same-conversation streaming concurrency.
2. Decide whether ticket guarantees must hold in the multi-replica deployment; if yes, persist them transactionally.
3. Correct Compose environment propagation and add a Compose configuration assertion.
4. Fix stream accounting, atomic ingestion, and the UI reset race with focused regression tests.
