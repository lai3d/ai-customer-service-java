package dev.merlionos.customerservice.rag.api;

import java.util.List;

/**
 * The seam between retrieval and whatever holds the corpus.
 *
 * <p>In one process the advisor chain queries the vector store directly and this interface
 * is what the knowledge role exposes; in the distributed topology a controller serves it and
 * a search-only {@code VectorStore} adapter on the chat side calls it, so the advisor chain
 * itself does not change. A failure here is a failure, not an empty result: the caller fails
 * the turn rather than answering without grounding.
 */
public interface KnowledgeSearch {

    List<Passage> search(SearchQuery query);
}
