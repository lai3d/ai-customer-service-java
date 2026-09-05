package dev.merlionos.customerservice.rag.api;

/** The active version, or a draft, changed under the caller. Reload and look again. A {@code 409}. */
public class KnowledgeConflictException extends RuntimeException {

    public KnowledgeConflictException(String what) {
        super(what);
    }
}
