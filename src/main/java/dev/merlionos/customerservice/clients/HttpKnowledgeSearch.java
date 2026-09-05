package dev.merlionos.customerservice.clients;

import dev.merlionos.customerservice.rag.api.KnowledgeSearch;
import dev.merlionos.customerservice.rag.api.Passage;
import dev.merlionos.customerservice.rag.api.SearchQuery;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * The knowledge seam as a client: one POST, the same records on both ends.
 *
 * <p>A failure here is a turn the customer lost: the exception becomes a {@code 503} and the
 * handler logs it, but a log line is not something an alert can watch, so every failure is
 * also counted on {@code chat.knowledge.unavailable}. {@code ChatService} registers the same
 * counter at zero, because this class exists only in a {@code chat} process and an
 * {@code all} process, with no seam to fail, should still show 0 rather than nothing.
 */
public class HttpKnowledgeSearch implements KnowledgeSearch {

    public static final String UNAVAILABLE_COUNTER = "chat.knowledge.unavailable";

    private static final ParameterizedTypeReference<List<Passage>> PASSAGES = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;
    private final Counter unavailable;

    public HttpKnowledgeSearch(RestClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.unavailable = unavailableCounter(meterRegistry);
    }

    /** The one definition of the counter, so the client and the pre-registration agree. */
    public static Counter unavailableCounter(MeterRegistry meterRegistry) {
        return Counter.builder(UNAVAILABLE_COUNTER)
                .description("Knowledge searches over the seam that failed; each is a turn ended with a 503")
                .register(meterRegistry);
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
            unavailable.increment();
            throw new KnowledgeUnavailableException("Knowledge search failed: " + e.getMessage(), e);
        }
    }
}
