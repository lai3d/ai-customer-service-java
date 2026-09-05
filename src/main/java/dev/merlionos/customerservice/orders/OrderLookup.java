package dev.merlionos.customerservice.orders;

/**
 * The seam between the {@code lookup_order_status} tool and whatever answers it.
 *
 * <p>Today the only implementation wraps {@link MockOrderRepository}; a real order system
 * plugs in behind this interface as an HTTP adapter without the tool, its description or its
 * event reporting changing. There is deliberately no separate mock order service: a service
 * whose only implementation is a fake proves nothing about the boundary.
 */
public interface OrderLookup {

    /** Never throws for a missing or malformed number; the result says so as a value. */
    OrderLookupResult lookup(String orderNumber);
}
