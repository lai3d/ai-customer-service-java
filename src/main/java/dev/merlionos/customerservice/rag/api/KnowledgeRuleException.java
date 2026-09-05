package dev.merlionos.customerservice.rag.api;

/** The knowledge base will not do this; reloading will not help. A {@code 422}. */
public class KnowledgeRuleException extends RuntimeException {

    public KnowledgeRuleException(String what) {
        super(what);
    }
}
