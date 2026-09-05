package dev.merlionos.customerservice.tools;

import dev.merlionos.customerservice.chat.TurnEvent;
import dev.merlionos.customerservice.chat.TurnEventBus;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import dev.merlionos.customerservice.ticket.api.TicketRequest;
import dev.merlionos.customerservice.ticket.api.TicketResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.UUID;

/**
 * The tool adapter for raising a ticket. The invariants -- one ticket per distinct request
 * per conversation, at most three per conversation -- live in {@link TicketOperations};
 * this class reads the conversation from the trusted tool context, asks, and reports what
 * happened to the meter and to the turn's event stream.
 *
 * <p>The conversation id comes from the tool context and never from a model-authored
 * argument, because it is the scope of the deduplication and the cap. A model that could
 * choose the conversation could choose a fresh one every time.
 */
@Component
public class SupportTicketTools {

    /** Key under which {@code ChatService} puts the conversation id into the tool context. */
    public static final String CONVERSATION_ID_KEY = "conversationId";

    private static final String TOOL_NAME = "create_support_ticket";

    private final TicketOperations tickets;
    private final MeterRegistry meterRegistry;
    private final TurnEventBus turnEventBus;

    SupportTicketTools(TicketOperations tickets, MeterRegistry meterRegistry, TurnEventBus turnEventBus) {
        this.tickets = tickets;
        this.meterRegistry = meterRegistry;
        this.turnEventBus = turnEventBus;
        // Every outcome at zero from the start, so "unavailable" reads 0 on the dashboard and
        // the alert on it has a series to watch, rather than both appearing only after the
        // first failure.
        for (TicketResult.Status status : TicketResult.Status.values()) {
            meterRegistry.counter("chat.tool.invocations", "tool", TOOL_NAME, "outcome",
                    outcomeOf(new TicketResult(status, status == TicketResult.Status.CREATED, null, null)));
        }
    }

    @Tool(name = "create_support_ticket", description = """
            Raise a ticket for a human agent to follow up. Use this only when the customer's \
            problem cannot be resolved from the FAQ or an order lookup: they have asked for a \
            human, the situation needs an account change or a refund decision, or the answer \
            genuinely is not known. Do not use it to answer questions that documentation \
            already covers. Summarise the customer's problem in the summary; do not paste the \
            whole conversation.
            """,
            // What the model reads is a string, and the default converter writes
            // LocalDate as [2026,9,3]. See ReadableToolResultConverter.
            resultConverter = ReadableToolResultConverter.class)
    public TicketResult createSupportTicket(
            @ToolParam(description = "One or two sentences describing what the customer needs")
            String summary,
            @ToolParam(description = "One of: returns, shipping, payment, account, other")
            String category,
            @ToolParam(required = false, description = "The related order number, if there is one")
            String orderNumber,
            ToolContext toolContext) {

        String conversationId = conversationIdFrom(toolContext);
        // One id per invocation, generated here and not by the model: it is what lets a retry
        // over the seam be recognised as the same write, and a model could reuse or invent one.
        String operationId = UUID.randomUUID().toString();
        TicketResult result = tickets.create(
                new TicketRequest(operationId, conversationId, summary, category, orderNumber));
        report(toolContext, outcomeOf(result));
        return result;
    }

    /** The outcomes a result can encode, as the meter and the demo page name them. */
    static String outcomeOf(TicketResult result) {
        return switch (result.status()) {
            case CREATED -> "created";
            case EXISTING -> "duplicate_suppressed";
            case REFUSED -> "capped";
            case UNAVAILABLE -> "unavailable";
        };
    }

    /** Shared with {@link OrderTools}: every tool needs the conversation it is serving. */
    static String conversationIdFrom(ToolContext toolContext) {
        return required(toolContext, CONVERSATION_ID_KEY);
    }

    /**
     * The turn, not the conversation. Events are routed per turn so two overlapping turns on
     * one conversation cannot be delivered to each other's stream.
     */
    static String turnIdFrom(ToolContext toolContext) {
        return required(toolContext, TurnEventBus.TURN_ID_KEY);
    }

    private static String required(ToolContext toolContext, String key) {
        Assert.notNull(toolContext, "tool context is required to attribute a ticket");
        Object value = toolContext.getContext().get(key);
        Assert.isTrue(value instanceof String text && !text.isBlank(),
                "tool context is missing " + key);
        return (String) value;
    }

    private void report(ToolContext toolContext, String outcome) {
        meterRegistry.counter("chat.tool.invocations",
                "tool", TOOL_NAME, "outcome", outcome).increment();
        turnEventBus.publish(turnIdFrom(toolContext), new TurnEvent.ToolCall(TOOL_NAME, outcome));
    }
}
