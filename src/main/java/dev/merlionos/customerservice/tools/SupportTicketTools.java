package dev.merlionos.customerservice.tools;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creating a ticket is a write, and writes need different care from lookups.
 *
 * <p>A model can call the same tool twice in one turn, and a retried request replays the whole
 * conversation. Without a guard, one frustrated customer becomes three tickets in the human
 * agents' queue. Tickets are therefore deduplicated per conversation: asking twice for the
 * same thing returns the ticket that already exists, flagged so the model can say "I've
 * already raised that for you" instead of inventing a second reference number.
 */
@Component
public class SupportTicketTools {

    /** Key under which {@code ChatService} puts the conversation id into the tool context. */
    public static final String CONVERSATION_ID_KEY = "conversationId";

    private static final Logger log = LoggerFactory.getLogger(SupportTicketTools.class);

    private final Map<String, SupportTicket> ticketsByDeduplicationKey = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(4700);
    private final MeterRegistry meterRegistry;

    SupportTicketTools(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Tool(name = "create_support_ticket", description = """
            Raise a ticket for a human agent to follow up. Use this only when the customer's \
            problem cannot be resolved from the FAQ or an order lookup: they have asked for a \
            human, the situation needs an account change or a refund decision, or the answer \
            genuinely is not known. Do not use it to answer questions that documentation \
            already covers. Summarise the customer's problem in the summary; do not paste the \
            whole conversation.
            """)
    public SupportTicket createSupportTicket(
            @ToolParam(description = "One or two sentences describing what the customer needs")
            String summary,
            @ToolParam(description = "One of: returns, shipping, payment, account, other")
            String category,
            @ToolParam(required = false, description = "The related order number, if there is one")
            String orderNumber,
            ToolContext toolContext) {

        String conversationId = conversationIdFrom(toolContext);
        String deduplicationKey = conversationId + "|" + normalise(summary);

        SupportTicket existing = ticketsByDeduplicationKey.get(deduplicationKey);
        if (existing != null) {
            count("duplicate_suppressed");
            log.info("Suppressed duplicate ticket for conversation {}; returning {}",
                    conversationId, existing.ticketNumber());
            return new SupportTicket(existing.ticketNumber(), existing.conversationId(),
                    existing.category(), existing.summary(), existing.orderNumber(),
                    existing.createdAt(), true);
        }

        SupportTicket ticket = new SupportTicket(
                "TKT-" + sequence.incrementAndGet(),
                conversationId,
                normaliseCategory(category),
                summary,
                orderNumber,
                Instant.now(),
                false);

        // putIfAbsent, not put: two tool calls for the same conversation can land concurrently.
        SupportTicket raced = ticketsByDeduplicationKey.putIfAbsent(deduplicationKey, ticket);
        if (raced != null) {
            count("duplicate_suppressed");
            return new SupportTicket(raced.ticketNumber(), raced.conversationId(), raced.category(),
                    raced.summary(), raced.orderNumber(), raced.createdAt(), true);
        }

        count("created");
        log.info("Created {} for conversation {} in category {}",
                ticket.ticketNumber(), conversationId, ticket.category());
        return ticket;
    }

    /** Exposed for tests and for a future admin endpoint; not a tool. */
    public List<SupportTicket> ticketsFor(String conversationId) {
        return ticketsByDeduplicationKey.values().stream()
                .filter(ticket -> ticket.conversationId().equals(conversationId))
                .toList();
    }

    private static String conversationIdFrom(ToolContext toolContext) {
        Assert.notNull(toolContext, "tool context is required to attribute a ticket");
        Object conversationId = toolContext.getContext().get(CONVERSATION_ID_KEY);
        Assert.isTrue(conversationId instanceof String value && !value.isBlank(),
                "tool context is missing " + CONVERSATION_ID_KEY);
        return (String) conversationId;
    }

    private static String normalise(String summary) {
        return summary == null ? "" : summary.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String normaliseCategory(String category) {
        String normalised = normalise(category);
        return List.of("returns", "shipping", "payment", "account").contains(normalised)
                ? normalised
                : "other";
    }

    private void count(String outcome) {
        meterRegistry.counter("chat.tool.invocations",
                "tool", "create_support_ticket", "outcome", outcome).increment();
    }
}
