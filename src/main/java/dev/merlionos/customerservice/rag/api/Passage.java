package dev.merlionos.customerservice.rag.api;

import java.util.Map;

/**
 * One retrieved passage. Our own record rather than Spring AI's {@code Document}, so the
 * wire contract between {@code chat} and {@code knowledge} is ours to version.
 *
 * @param id       the stable document id, {@code faq:<entry>:<language>}
 * @param text     the passage as it will be shown to the model
 * @param score    cosine similarity, 0 to 1
 * @param metadata the document metadata as ingested: entry id, language, corpus version
 */
public record Passage(String id, String text, Double score, Map<String, Object> metadata) {
}
