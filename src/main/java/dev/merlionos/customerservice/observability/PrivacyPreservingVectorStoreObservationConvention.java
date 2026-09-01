package dev.merlionos.customerservice.observability;

import io.micrometer.common.KeyValues;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.stereotype.Component;

/**
 * Keeps the customer's own words out of exported traces by default.
 *
 * <p>Spring AI has switches for prompt and completion content -- {@code log-prompt},
 * {@code log-completion}, {@code log-query-response} -- and all three default to off. The
 * vector store's *query* text is not among them: {@code db.vector.query.content} is added
 * unconditionally by the default convention, so the question a customer typed leaves the
 * process on every search. Verified by reading it back out of Jaeger.
 *
 * <p>That is a reasonable default for a library and a poor one for a support system, where the
 * query is frequently the most sensitive thing in the request -- an order number, an address,
 * a complaint. Traces are retained, replicated, and read by people who have no business
 * reading customer messages, so the content is dropped unless someone deliberately turns it
 * on for debugging.
 *
 * <p>Everything else the convention records -- top-k, threshold, similarity metric, dimensions,
 * timing -- is kept, and that is what the span is actually useful for.
 */
@Component
class PrivacyPreservingVectorStoreObservationConvention extends DefaultVectorStoreObservationConvention {

    private final ObservabilityProperties properties;

    PrivacyPreservingVectorStoreObservationConvention(ObservabilityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected KeyValues queryContent(KeyValues keyValues, VectorStoreObservationContext context) {
        return properties.includeQueryContent()
                ? super.queryContent(keyValues, context)
                : keyValues;
    }
}
