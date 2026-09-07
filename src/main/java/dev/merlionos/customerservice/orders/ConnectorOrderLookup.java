package dev.merlionos.customerservice.orders;

import dev.merlionos.customerservice.orders.shopify.ShopifyOrderLookup;
import dev.merlionos.customerservice.tenancy.Tenant;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The {@link OrderLookup} the tool talks to: a tenant's connector when it has one, the
 * bundled mock for the default tenant when it has none (the demo, the tests, a laptop), and
 * for any other tenant without a connector an honest "no order system is connected".
 */
@Component
public class ConnectorOrderLookup implements OrderLookup {

    private final OrderConnectors connectors;
    private final ShopifyOrderLookup shopify;
    private final LocalOrderLookup mock;

    public ConnectorOrderLookup(OrderConnectors connectors, ShopifyOrderLookup shopify, MockOrderRepository mockOrders) {
        this.connectors = connectors;
        this.shopify = shopify;
        this.mock = new LocalOrderLookup(mockOrders);
    }

    @Override
    public OrderLookupResult lookup(String tenantId, String orderNumber) {
        Optional<OrderConnector> connector = connectors.of(tenantId);
        if (connector.isPresent()) {
            return shopify.lookup(connector.get(), orderNumber);
        }
        if (Tenant.DEFAULT.equals(tenantId)) {
            return mock.lookup(tenantId, orderNumber);
        }
        return OrderLookupResult.unavailable("This store has no order system connected yet, so order status cannot be "
                + "looked up here. Offer to raise a ticket for a person instead.");
    }
}
