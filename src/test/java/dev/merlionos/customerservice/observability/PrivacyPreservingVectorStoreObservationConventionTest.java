package dev.merlionos.customerservice.observability;

import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The customer's question must not reach the tracing backend unless someone deliberately asks
 * for it. Spring AI's default convention attaches it to every vector-store span with no
 * property to switch it off, so this behaviour is ours to keep working.
 */
class PrivacyPreservingVectorStoreObservationConventionTest {

    private static final String CUSTOMER_QUESTION = "我的订单 ORD-10042 到哪了";

    private static VectorStoreObservationContext context() {
        return VectorStoreObservationContext.builder("pg_vector", "query")
                .queryRequest(SearchRequest.builder().query(CUSTOMER_QUESTION).topK(4).build())
                .collectionName("vector_store")
                .dimensions(384)
                .similarityMetric("cosine")
                .build();
    }

    private static KeyValues keyValues(boolean includeQueryContent) {
        return new PrivacyPreservingVectorStoreObservationConvention(
                new ObservabilityProperties(includeQueryContent))
                .getHighCardinalityKeyValues(context());
    }

    @Test
    @DisplayName("by default the question a customer typed never leaves the process")
    void omitsQueryContentByDefault() {
        assertThat(keyValues(false).stream().map(kv -> kv.getKey()))
                .doesNotContain("db.vector.query.content");
        assertThat(keyValues(false).toString()).doesNotContain(CUSTOMER_QUESTION);
    }

    @Test
    @DisplayName("the rest of the span is untouched -- it is what makes the trace useful")
    void keepsDiagnosticAttributes() {
        assertThat(keyValues(false).stream().map(kv -> kv.getKey()))
                .contains("db.vector.query.top_k", "db.collection.name", "db.vector.dimension_count");
    }

    @Test
    @DisplayName("it can be switched on deliberately for debugging retrieval")
    void includesQueryContentWhenAskedTo() {
        assertThat(keyValues(true).toString()).contains(CUSTOMER_QUESTION);
    }
}
