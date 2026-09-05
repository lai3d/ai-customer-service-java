package dev.merlionos.customerservice.orders;

import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stand-in for the order system. Phase 1 keeps this in memory on purpose: the point of the
 * exercise is the tool-calling contract, and a fake that answers instantly makes the model's
 * behaviour -- when it decides to look an order up, and what it does with the answer -- the
 * only variable.
 */
@Repository
public class MockOrderRepository {

    private final Map<String, Order> ordersByNumber = List.of(
            new Order("ORD-10042", OrderStatus.IN_TRANSIT,
                    LocalDate.of(2026, 8, 27), LocalDate.of(2026, 9, 3),
                    "SingPost", "SP884213906SG", "1 x Noise-cancelling headphones"),
            new Order("ORD-10043", OrderStatus.PREPARING,
                    LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 5),
                    null, null, "2 x Cotton t-shirt (M, navy)"),
            new Order("ORD-10044", OrderStatus.DELIVERED,
                    LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 22),
                    "DHL", "JD0002088776", "1 x Espresso machine"),
            new Order("ORD-10045", OrderStatus.RETURN_IN_PROGRESS,
                    LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 14),
                    "DHL", "JD0002071140", "1 x Desk lamp"),
            new Order("ORD-10046", OrderStatus.CANCELLED,
                    LocalDate.of(2026, 8, 29), null,
                    null, null, "1 x Mechanical keyboard"))
            .stream()
            .collect(Collectors.toUnmodifiableMap(Order::orderNumber, Function.identity()));

    /**
     * Lookup is case-insensitive and tolerates surrounding whitespace. Customers paste order
     * numbers out of emails, and a model relaying "ord-10042" should not be told the order
     * does not exist.
     */
    public Optional<Order> findByOrderNumber(String orderNumber) {
        if (orderNumber == null || orderNumber.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ordersByNumber.get(orderNumber.strip().toUpperCase(Locale.ROOT)));
    }
}
