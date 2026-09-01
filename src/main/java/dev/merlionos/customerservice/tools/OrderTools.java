package dev.merlionos.customerservice.tools;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool descriptions are prompt, not documentation. They are the only thing the model reads
 * when deciding whether to call this instead of answering from retrieved FAQ text, so they
 * say what the tool is for and, just as importantly, what it is not for.
 */
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    private final MockOrderRepository orders;
    private final MeterRegistry meterRegistry;

    OrderTools(MockOrderRepository orders, MeterRegistry meterRegistry) {
        this.orders = orders;
        this.meterRegistry = meterRegistry;
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
            @ToolParam(description = "The order number, for example ORD-10042") String orderNumber) {

        return orders.findByOrderNumber(orderNumber)
                .map(order -> {
                    count("lookup_order_status", "found");
                    log.debug("Order lookup hit for {}", order.orderNumber());
                    return OrderLookupResult.found(order);
                })
                .orElseGet(() -> {
                    count("lookup_order_status", "not_found");
                    log.debug("Order lookup miss for {}", orderNumber);
                    return OrderLookupResult.notFound(
                            "No order matches that number. It may have been mistyped, or it may "
                                    + "belong to a different account.");
                });
    }

    private void count(String tool, String outcome) {
        meterRegistry.counter("chat.tool.invocations", "tool", tool, "outcome", outcome).increment();
    }
}
