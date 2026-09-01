package dev.merlionos.customerservice.tools;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The result handed back to the model.
 *
 * <p>A missing order is a value, not an exception. Spring AI's default behaviour on a thrown
 * tool exception is to feed the exception's message back to the model as the tool result --
 * so throwing here would put an internal error string in front of a customer and give the
 * model nothing it can reason about. A {@code found: false} result with a plain explanation
 * lets it say "I can't find that order number, could you check it?" instead.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderLookupResult(boolean found, Order order, String explanation) {

    static OrderLookupResult found(Order order) {
        return new OrderLookupResult(true, order, null);
    }

    static OrderLookupResult notFound(String explanation) {
        return new OrderLookupResult(false, null, explanation);
    }
}
