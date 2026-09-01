package dev.merlionos.customerservice.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param conversationId identifies the conversation to continue. Omit to start a new one;
 *                       the assigned id comes back in the {@code X-Conversation-Id} header.
 * @param message        the customer's turn
 */
public record ChatRequest(
        String conversationId,

        @NotBlank(message = "message must not be blank")
        @Size(max = 8000, message = "message must be at most 8000 characters")
        String message) {
}
