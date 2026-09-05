package dev.merlionos.customerservice.orders;

import org.springframework.stereotype.Component;

@Component
public class LocalOrderLookup implements OrderLookup {

    private final MockOrderRepository orders;

    public LocalOrderLookup(MockOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public OrderLookupResult lookup(String orderNumber) {
        return orders.findByOrderNumber(orderNumber)
                .map(OrderLookupResult::found)
                .orElseGet(() -> OrderLookupResult.notFound(
                        "No order matches that number. It may have been mistyped, or it may "
                                + "belong to a different account."));
    }
}
