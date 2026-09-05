package dev.merlionos.customerservice.clients;

/**
 * The knowledge service could not answer. Deliberately not an empty result: answering with no
 * passages would be answering without grounding, and the system prompt's whole premise is
 * grounding. The turn fails, the client sees {@code 503} or an {@code error} event, and the
 * retry configuration already bounds how many times that is attempted.
 */
public class KnowledgeUnavailableException extends RuntimeException {

    public KnowledgeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
