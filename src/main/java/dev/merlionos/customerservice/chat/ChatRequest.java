package dev.merlionos.customerservice.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param conversationId identifies the conversation to continue. Omit to start a new one;
 *                       the assigned id comes back in the {@code X-Conversation-Id} header.
 * @param message        the customer's turn
 */
public record ChatRequest(
        /*
         * Bounded at 36 because Spring AI's JDBC chat memory schema declares
         * conversation_id as varchar(36) -- sized for a UUID, which is what this service
         * generates. Without the constraint a longer client-supplied id reaches Postgres and
         * comes back as a 500 from a DataIntegrityViolationException. Found by a test that
         * happened to use a descriptive id.
         */
        @Size(max = 36, message = "conversationId must be at most 36 characters")
        String conversationId,

        @NotBlank(message = "message must not be blank")
        @Size(max = 8000, message = "message must be at most 8000 characters")
        String message) {
}
