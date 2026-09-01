package dev.merlionos.customerservice.tools;

import java.time.LocalDate;

public record Order(
        String orderNumber,
        OrderStatus status,
        LocalDate placedOn,
        LocalDate estimatedDelivery,
        String carrier,
        String trackingNumber,
        String summary) {
}
