package dev.merlionos.customerservice.rag.api;

/**
 * A retrieval request as the chat side phrases it: the customer's words, how many passages,
 * and the similarity floor. The same three values {@code QuestionAnswerAdvisor} puts in its
 * {@code SearchRequest}, so the two paths cannot drift.
 */
public record SearchQuery(String text, int topK, double similarityThreshold) {
}
