package dev.merlionos.customerservice.rag.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param corpusLocation      where the FAQ corpus lives
 * @param importMode          what to do about the corpus at startup; see {@link ImportMode}
 * @param topK                how many passages to retrieve per question
 * @param similarityThreshold minimum similarity for a passage to be used, 0 to 1. Too low
 *                            and unrelated passages get presented to the model as fact; too
 *                            high and the assistant declines questions it could have answered
 * @param queryPrefix         prepended before embedding a search query. Retrieval-trained
 *                            models such as e5 require this; blank for models that do not
 * @param passagePrefix       prepended before embedding a document, same reasoning
 */
@ConfigurationProperties("app.rag")
public record RagProperties(
        String corpusLocation,
        ImportMode importMode,
        int topK,
        double similarityThreshold,
        String queryPrefix,
        String passagePrefix) {
}
