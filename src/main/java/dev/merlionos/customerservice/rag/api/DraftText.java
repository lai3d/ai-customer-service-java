package dev.merlionos.customerservice.rag.api;

/** A draft on the wire: what {@link KnowledgeAdmin#saveDraft} takes, minus the actor, which is a header's worth of trust. */
public record DraftText(String question, String answer, String note, String actor) {
}
