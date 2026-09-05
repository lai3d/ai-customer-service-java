package dev.merlionos.customerservice.ticket.api;

import java.time.Instant;

public record SupportTicket(
        String ticketNumber,
        String conversationId,
        String category,
        String summary,
        String orderNumber,
        Instant createdAt,
        boolean alreadyExisted) {
}
