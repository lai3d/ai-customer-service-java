package dev.merlionos.customerservice.rag.api;

import java.util.List;

/**
 * @param sources every distinct import source among the tenant's entries, whatever the page,
 *                so a page can offer them without a second call
 */
public record EntryPage(List<KnowledgeEntry> entries, long total, int page, int size, List<String> sources) {
}
