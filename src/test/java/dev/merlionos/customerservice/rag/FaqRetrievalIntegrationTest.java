package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality, measured against the real ONNX embedding model and a real pgvector
 * instance. No API key is involved: everything up to the model call is testable, and this is
 * the part where a silent regression -- a changed corpus, a retuned threshold, a different
 * embedding model -- would otherwise show up only as vaguer answers in production.
 *
 * <p>Queries deliberately avoid the corpus wording. Matching a question to its own text
 * proves nothing about how the system behaves for a customer describing a problem in their
 * own words.
 */
@SpringBootTest(properties = "app.rag.ingest-on-startup=true")
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class FaqRetrievalIntegrationTest {

    @Autowired VectorStore vectorStore;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RagProperties ragProperties;
    @Autowired FaqIngestionService ingestionService;

    @ParameterizedTest(name = "\"{0}\" retrieves {1}")
    @CsvSource(delimiter = '|', value = {
            "I want to send something back, is it too late after three weeks? | returns-window",
            "my parcel showed up broken                                      | returns-damaged",
            "how much do I pay for delivery                                  | shipping-cost",
            "my card was rejected at checkout                                | payment-declined",
            "can I get a different size instead                              | returns-exchange",
            "when can I talk to a real person                                | support-hours",
    })
    @DisplayName("a paraphrased question retrieves the right FAQ entry first")
    void retrievesExpectedEntry(String query, String expectedEntryId) {
        List<Document> hits = search(query);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().getMetadata())
                .containsEntry(FaqDocumentReader.METADATA_ENTRY_ID, expectedEntryId.trim());
    }

    @Test
    @DisplayName("an off-topic question retrieves nothing, rather than the least-bad passage")
    void offTopicQuestionRetrievesNothing() {
        assertThat(search("what is the capital of France"))
                .as("the configured threshold must reject unrelated passages, so the assistant "
                        + "declines instead of answering from an irrelevant FAQ entry")
                .isEmpty();
    }

    @Test
    @DisplayName("re-ingesting replaces the corpus instead of duplicating it")
    void reingestionDoesNotDuplicate() {
        long before = countDocuments();

        ingestionService.ingest();
        ingestionService.ingest();

        assertThat(countDocuments()).isEqualTo(before);
    }

    private List<Document> search(String query) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(ragProperties.topK())
                .similarityThreshold(ragProperties.similarityThreshold())
                .build());
    }

    private long countDocuments() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Long.class);
    }
}
