package dev.merlionos.customerservice.rag.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param corpusLocation      where the FAQ corpus lives
 * @param ingestOnStartup     whether to load the corpus into the vector store at boot
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
        boolean ingestOnStartup,
        int topK,
        double similarityThreshold,
        String queryPrefix,
        String passagePrefix) {
}
