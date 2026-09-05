package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.KnowledgeSearch;
import dev.merlionos.customerservice.rag.api.Passage;
import dev.merlionos.customerservice.rag.api.SearchQuery;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * Search over the in-process vector store, phrased exactly as {@code QuestionAnswerAdvisor}
 * phrases it, so what the endpoint returns is what the advisor would have retrieved.
 */
public class LocalKnowledgeSearch implements KnowledgeSearch {

    private final VectorStore vectorStore;

    public LocalKnowledgeSearch(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<Passage> search(SearchQuery query) {
        SearchRequest request = SearchRequest.builder()
                .query(query.text())
                .topK(query.topK())
                .similarityThreshold(query.similarityThreshold())
                .build();

        return vectorStore.similaritySearch(request).stream()
                .map(document -> new Passage(document.getId(), document.getText(),
                        document.getScore(), document.getMetadata()))
                .toList();
    }
}
