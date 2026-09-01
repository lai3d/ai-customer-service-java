package dev.merlionos.customerservice.cost;

public class ConversationBudgetExceededException extends RuntimeException {

    private final String conversationId;

    public ConversationBudgetExceededException(String conversationId, long spent, long budget) {
        super("Conversation %s has spent %d tokens against a budget of %d"
                .formatted(conversationId, spent, budget));
        this.conversationId = conversationId;
    }

    public String conversationId() {
        return this.conversationId;
    }
}
