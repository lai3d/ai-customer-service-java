package dev.merlionos.customerservice.tools;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class OrderToolsTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OrderTools tools = new OrderTools(new MockOrderRepository(), meterRegistry);

    @Test
    @DisplayName("a known order comes back with tracking detail")
    void findsKnownOrder() {
        OrderLookupResult result = tools.lookupOrderStatus("ORD-10042");

        assertThat(result.found()).isTrue();
        assertThat(result.order().status()).isEqualTo(OrderStatus.IN_TRANSIT);
        assertThat(result.order().trackingNumber()).isEqualTo("SP884213906SG");
        assertThat(result.explanation()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ord-10042", "  ORD-10042  ", "Ord-10042"})
    @DisplayName("order numbers pasted out of emails still match")
    void lookupIsForgivingAboutCaseAndWhitespace(String orderNumber) {
        assertThat(tools.lookupOrderStatus(orderNumber).found()).isTrue();
    }

    @Test
    @DisplayName("an unknown order is a result, not an exception")
    void unknownOrderReturnsExplanation() {
        OrderLookupResult result = tools.lookupOrderStatus("ORD-99999");

        assertThat(result.found()).isFalse();
        assertThat(result.order()).isNull();
        assertThat(result.explanation()).contains("mistyped");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("a blank order number does not blow up mid-conversation")
    void blankOrderNumberIsHandled(String orderNumber) {
        assertThat(tools.lookupOrderStatus(orderNumber).found()).isFalse();
    }

    @Test
    @DisplayName("hits and misses are counted separately")
    void invocationsAreMetered() {
        tools.lookupOrderStatus("ORD-10042");
        tools.lookupOrderStatus("ORD-99999");

        assertThat(counter("found")).isEqualTo(1.0);
        assertThat(counter("not_found")).isEqualTo(1.0);
    }

    private double counter(String outcome) {
        return meterRegistry.get("chat.tool.invocations")
                .tag("tool", "lookup_order_status").tag("outcome", outcome).counter().count();
    }
}
