package dev.merlionos.customerservice.clients;

import dev.merlionos.customerservice.rag.api.KnowledgeSearch;
import dev.merlionos.customerservice.rag.api.Passage;
import dev.merlionos.customerservice.rag.api.SearchQuery;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

/**
 * What makes the advisor chain identical in every topology: {@code QuestionAnswerAdvisor}
 * asks a {@link VectorStore} for passages, and in a {@code chat} process this is that store.
 * Search goes over the seam; the write methods throw, because a chat process owns no corpus
 * and a call to them is a wiring mistake worth failing loudly on.
 */
public class RemoteKnowledgeVectorStore implements VectorStore {

    private final KnowledgeSearch search;

    public RemoteKnowledgeVectorStore(KnowledgeSearch search) {
        this.search = search;
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        if (request.getFilterExpression() != null) {
            throw new UnsupportedOperationException("The knowledge seam carries no filter expression");
        }
        return search.search(new SearchQuery(request.getQuery(), request.getTopK(), request.getSimilarityThreshold()))
                .stream()
                .map(RemoteKnowledgeVectorStore::toDocument)
                .toList();
    }

    private static Document toDocument(Passage passage) {
        return Document.builder()
                .id(passage.id())
                .text(passage.text())
                .metadata(passage.metadata())
                .score(passage.score())
                .build();
    }

    @Override
    public void add(List<Document> documents) {
        throw new UnsupportedOperationException("A chat process does not write the corpus; the knowledge role does");
    }

    @Override
    public void delete(List<String> idList) {
        throw new UnsupportedOperationException("A chat process does not write the corpus; the knowledge role does");
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        throw new UnsupportedOperationException("A chat process does not write the corpus; the knowledge role does");
    }
}
