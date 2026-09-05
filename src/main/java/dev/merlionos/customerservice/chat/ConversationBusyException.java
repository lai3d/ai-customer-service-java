package dev.merlionos.customerservice.chat;

/** A turn is already in flight on this conversation. Mapped to {@code 409}. */
public class ConversationBusyException extends RuntimeException {

    private final String conversationId;

    public ConversationBusyException(String conversationId) {
        super("Conversation " + conversationId + " already has a turn in flight");
        this.conversationId = conversationId;
    }

    public String conversationId() {
        return conversationId;
    }
}
