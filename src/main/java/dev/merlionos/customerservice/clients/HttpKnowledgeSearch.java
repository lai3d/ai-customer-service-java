package dev.merlionos.customerservice.clients;

import dev.merlionos.customerservice.rag.api.KnowledgeSearch;
import dev.merlionos.customerservice.rag.api.Passage;
import dev.merlionos.customerservice.rag.api.SearchQuery;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/** The knowledge seam as a client: one POST, the same records on both ends. */
public class HttpKnowledgeSearch implements KnowledgeSearch {

    private static final ParameterizedTypeReference<List<Passage>> PASSAGES = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;

    public HttpKnowledgeSearch(RestClient client) {
        this.client = client;
    }

    @Override
    public List<Passage> search(SearchQuery query) {
        try {
            List<Passage> passages = client.post().uri("/internal/v1/knowledge/search")
                    .body(query)
                    .retrieve()
                    .body(PASSAGES);
            return passages == null ? List.of() : passages;
        }
        catch (RestClientException e) {
            throw new KnowledgeUnavailableException("Knowledge search failed: " + e.getMessage(), e);
        }
    }
}
