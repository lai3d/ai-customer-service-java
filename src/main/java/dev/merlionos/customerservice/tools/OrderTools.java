package dev.merlionos.customerservice.tools;

import dev.merlionos.customerservice.chat.TurnEvent;
import dev.merlionos.customerservice.chat.TurnEventBus;
import dev.merlionos.customerservice.orders.OrderLookup;
import dev.merlionos.customerservice.orders.OrderLookupResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tool descriptions are prompt, not documentation. They are the only thing the model reads
 * when deciding whether to call this instead of answering from retrieved FAQ text, so they
 * say what the tool is for and, just as importantly, what it is not for.
 *
 * <p>This is the adapter: it reads the tool context, asks {@link OrderLookup}, and reports
 * the outcome to the meter and the turn's event stream. Where the answer comes from is the
 * lookup's business.
 */
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    private static final String TOOL_NAME = "lookup_order_status";

    private final OrderLookup orders;
    private final MeterRegistry meterRegistry;
    private final TurnEventBus turnEventBus;

    OrderTools(OrderLookup orders, MeterRegistry meterRegistry, TurnEventBus turnEventBus) {
        this.orders = orders;
        this.meterRegistry = meterRegistry;
        this.turnEventBus = turnEventBus;
        // Registered at zero so a dashboard shows 0 rather than "No data" before the first
        // call; a counter that only exists once something has happened looks like nothing is.
        for (String outcome : List.of("found", "not_found")) {
            meterRegistry.counter("chat.tool.invocations", "tool", TOOL_NAME, "outcome", outcome);
        }
    }

    @Tool(name = "lookup_order_status", description = """
            Look up the current delivery status of one order by its order number. Use this \
            whenever a customer asks where their order is, when it will arrive, or whether it \
            has shipped. Returns the status, estimated delivery date, and carrier tracking \
            details when they exist. Does not modify the order. If the order number cannot be \
            found the result says so, which means the customer should be asked to check it \
            rather than told the order does not exist.
            """)
    public OrderLookupResult lookupOrderStatus(
            @ToolParam(description = "The order number, for example ORD-10042") String orderNumber,
            ToolContext toolContext) {

        OrderLookupResult result = orders.lookup(orderNumber);
        String outcome = result.found() ? "found" : "not_found";
        report(toolContext, outcome);
        log.debug("Order lookup {} for {}", outcome, result.found() ? result.order().orderNumber() : orderNumber);
        return result;
    }

    /**
     * Counts the invocation and, when a stream is listening, tells it -- a tool call is
     * otherwise invisible to the client until the assistant happens to mention it.
     */
    private void report(ToolContext toolContext, String outcome) {
        meterRegistry.counter("chat.tool.invocations",
                "tool", TOOL_NAME, "outcome", outcome).increment();
        turnEventBus.publish(SupportTicketTools.turnIdFrom(toolContext),
                new TurnEvent.ToolCall(TOOL_NAME, outcome));
    }
}
