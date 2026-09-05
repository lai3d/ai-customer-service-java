package dev.merlionos.customerservice.rag.api;

/**
 * A retrieval request as the chat side phrases it: the customer's words, how many passages,
 * and the similarity floor. The same three values {@code QuestionAnswerAdvisor} puts in its
 * {@code SearchRequest}, so the two paths cannot drift.
 *
 * @param version a knowledge version to search instead of the active one; null for the
 *                active one. Only the admin's preview sets it
 */
public record SearchQuery(String text, int topK, double similarityThreshold, String version) {

    /** Against the active version, which is what every customer turn asks. */
    public SearchQuery(String text, int topK, double similarityThreshold) {
        this(text, topK, similarityThreshold, null);
    }
}
