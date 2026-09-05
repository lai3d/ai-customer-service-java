package dev.merlionos.customerservice.ticket.api;

/**
 * What the chat side knows when it asks for a ticket.
 *
 * <p>Two identities, deliberately distinct. The conversation id is the business scope: it
 * comes from the trusted tool context, never from a model-authored argument, and is what the
 * deduplication and the cap are counted against. The operation id identifies <em>this
 * attempt</em> to write: the chat side generates it once per tool invocation and reuses it if
 * it has to retry, so a request that timed out after committing is recognised rather than
 * repeated. A conversation has many operations; an operation belongs to one request.
 */
public record TicketRequest(String operationId, String conversationId, String summary, String category,
                            String orderNumber) {
}
