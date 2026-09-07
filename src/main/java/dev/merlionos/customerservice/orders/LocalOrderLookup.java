package dev.merlionos.customerservice.orders;

/** The bundled mock orders: what the default tenant answers from until a connector is configured. */
public class LocalOrderLookup implements OrderLookup {

    private final MockOrderRepository orders;

    public LocalOrderLookup(MockOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public OrderLookupResult lookup(String tenantId, String orderNumber) {
        return orders.findByOrderNumber(orderNumber)
                .map(OrderLookupResult::found)
                .orElseGet(() -> OrderLookupResult.notFound(
                        "No order matches that number. It may have been mistyped, or it may "
                                + "belong to a different account."));
    }
}
