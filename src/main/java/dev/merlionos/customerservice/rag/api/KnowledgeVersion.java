package dev.merlionos.customerservice.rag.api;

import java.time.Instant;

/** A published document set, and where it is in its life. Also the publication job's status: the row is the job. */
public record KnowledgeVersion(String version, String state, Integer documentCount, Instant createdAt, String createdBy,
                               Instant activatedAt, String note, String error) {
}
