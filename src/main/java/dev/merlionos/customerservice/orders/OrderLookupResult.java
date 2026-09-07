package dev.merlionos.customerservice.orders;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A tool result is prompt, so the three outcomes are spelled out for the model: found,
 * not found (ask the customer to check the number), and unavailable (the order system did
 * not answer; say so and offer to try again, never say the order does not exist).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderLookupResult(boolean found, String outcome, Order order, String explanation) {

    public static final String FOUND = "found";
    public static final String NOT_FOUND = "not_found";
    public static final String UNAVAILABLE = "unavailable";

    public static OrderLookupResult found(Order order) {
        return new OrderLookupResult(true, FOUND, order, null);
    }

    public static OrderLookupResult notFound(String explanation) {
        return new OrderLookupResult(false, NOT_FOUND, null, explanation);
    }

    public static OrderLookupResult unavailable(String explanation) {
        return new OrderLookupResult(false, UNAVAILABLE, null, explanation);
    }
}
