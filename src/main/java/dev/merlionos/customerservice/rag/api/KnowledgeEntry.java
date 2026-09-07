package dev.merlionos.customerservice.rag.api;

import java.time.Instant;
import java.util.List;

/** One managed FAQ entry with its current revisions: the published text and the draft, if any, per language. */
/**
 * @param sourceKind {@code url} or {@code pdf} for an entry an import wrote, null for one a
 *                   person typed or the bundled corpus
 * @param source     the URL or the file name it came from, null likewise
 */
public record KnowledgeEntry(String entryId, String category, boolean retired, Instant createdAt, String createdBy,
                             List<KnowledgeRevision> revisions, String sourceKind, String source) {
}
