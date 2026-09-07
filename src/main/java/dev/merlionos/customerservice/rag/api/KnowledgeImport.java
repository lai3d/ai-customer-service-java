package dev.merlionos.customerservice.rag.api;

import java.time.Instant;

/**
 * One import of a tenant's document: what was asked for, and what happened. {@code running}
 * while the knowledge role fetches, parses and writes drafts; {@code done} with the number of
 * entries written; {@code failed} with the reason and nothing written. Polled by the admin
 * the way a publication is.
 */
public record KnowledgeImport(long id, String tenantId, String sourceKind, String source, String state, Integer entries,
                              String error, String requestedBy, Instant requestedAt, Instant finishedAt) {

    public boolean finished() {
        return !"running".equals(state);
    }
}
