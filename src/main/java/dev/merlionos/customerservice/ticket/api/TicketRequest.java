package dev.merlionos.customerservice.ticket.api;

/**
 * What the chat side knows when it asks for a ticket. The conversation id comes from the
 * trusted tool context, never from a model-authored argument; it is the deduplication scope
 * and, later, the wire-level idempotency scope.
 */
public record TicketRequest(String conversationId, String summary, String category, String orderNumber) {
}
