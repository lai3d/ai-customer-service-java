package dev.merlionos.customerservice.rag.api;

import java.time.Instant;

/** An entry's text in one language at one point in time. */
public record KnowledgeRevision(long id, String entryId, String language, String question, String answer,
                                String state, Instant createdAt, String createdBy, String note) {
}
