package dev.merlionos.customerservice.orders;

import dev.merlionos.customerservice.orders.xboard.XboardTickets;
import dev.merlionos.customerservice.ticket.api.TicketResult;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Whether a tenant's tickets belong in its panel rather than in our tables, and the raising
 * of one there. A panel ticket needs the customer's own token; without one (a visitor who
 * is not signed in) the tool keeps a ticket of our own, which the tenant's staff see in the
 * operations admin.
 */
@Component
public class PanelTickets {

    private final OrderConnectors connectors;
    private final XboardTickets xboard;

    public PanelTickets(OrderConnectors connectors, XboardTickets xboard) {
        this.connectors = connectors;
        this.xboard = xboard;
    }

    /** The panel connector, when the tenant has one and the customer is signed in to it. */
    public Optional<OrderConnector> panelFor(String tenantId, String customerToken) {
        if (customerToken == null || customerToken.isBlank()) {
            return Optional.empty();
        }
        return connectors.of(tenantId).filter(c -> OrderConnector.XBOARD.equals(c.kind()));
    }

    public TicketResult create(OrderConnector panel, String customerToken, String conversationId, String summary, String category,
                               String orderNumber) {
        return xboard.create(panel, customerToken, conversationId, summary, category, orderNumber);
    }
}
