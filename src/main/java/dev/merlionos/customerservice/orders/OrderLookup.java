package dev.merlionos.customerservice.orders;

/**
 * Where an order's status comes from: the seam between the {@code lookup_order_status} tool
 * and whatever order system a tenant has. The tool passes the tenant from the request's
 * API key, never from a model argument, so a tenant can only ever read its own orders.
 */
public interface OrderLookup {

    OrderLookupResult lookup(String tenantId, String orderNumber);
}
