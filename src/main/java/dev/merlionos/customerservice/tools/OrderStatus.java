package dev.merlionos.customerservice.tools;

/**
 * Order lifecycle states. These names are the ones the FAQ corpus refers to -- the
 * address-change entry tells customers the address is fixed once an order is
 * {@code DISPATCHED} -- so retrieved policy and tool results describe the same world.
 */
public enum OrderStatus {
    PREPARING,
    DISPATCHED,
    IN_TRANSIT,
    DELIVERED,
    RETURN_IN_PROGRESS,
    CANCELLED
}
