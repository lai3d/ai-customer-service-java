package dev.merlionos.customerservice.rag.api;

import java.time.Instant;
import java.util.List;

/** One managed FAQ entry with its current revisions: the published text and the draft, if any, per language. */
public record KnowledgeEntry(String entryId, String category, boolean retired, Instant createdAt, String createdBy,
                             List<KnowledgeRevision> revisions) {
}
