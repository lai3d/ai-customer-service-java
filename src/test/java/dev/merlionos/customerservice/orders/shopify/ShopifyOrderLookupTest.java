package dev.merlionos.customerservice.orders.shopify;

import dev.merlionos.customerservice.orders.ConnectorProperties;
import dev.merlionos.customerservice.orders.OrderConnector;
import dev.merlionos.customerservice.orders.OrderLookupResult;
import dev.merlionos.customerservice.orders.OrderStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The connector against a Shopify-shaped server: the request it makes, the mapping, and failure as a value. */
class ShopifyOrderLookupTest {

    static FakeShopify shopify;
    static ShopifyOrderLookup lookup;

    @BeforeAll
    static void start() throws Exception {
        shopify = new FakeShopify();
        lookup = new ShopifyOrderLookup(RestClient.builder(), new ConnectorProperties(null, Duration.ofSeconds(3), shopify.baseUrl(), null, true));
    }

    @AfterAll
    static void stop() {
        shopify.close();
    }

    private static OrderConnector connector(String token) {
        return new OrderConnector("acme", OrderConnector.SHOPIFY, "northwind-lamps.myshopify.com", null, token, "2025-07", null, Instant.now(), "root");
    }

    @Test
    @DisplayName("an order in transit: name, dates, carrier, tracking and items folded into the tool's own Order")
    void inTransit() {
        OrderLookupResult result = lookup.lookup(connector(FakeShopify.TOKEN), "#1001");

        assertThat(result.found()).isTrue();
        assertThat(result.outcome()).isEqualTo(OrderLookupResult.FOUND);
        assertThat(result.order().orderNumber()).isEqualTo("#1001");
        assertThat(result.order().status()).isEqualTo(OrderStatus.IN_TRANSIT);
        assertThat(result.order().placedOn()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(result.order().estimatedDelivery()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(result.order().carrier()).isEqualTo("SingPost");
        assertThat(result.order().trackingNumber()).isEqualTo("SP884213906SG");
        assertThat(result.order().summary()).isEqualTo("1 x Noise-cancelling headphones");
        assertThat(shopify.requests.getLast()).startsWith("/admin/api/2025-07/orders.json?").contains("name=%231001").contains("status=any");
    }

    @Test
    @DisplayName("a number without the hash is tried with one; preparing, delivered and cancelled map to their statuses")
    void numbersAndStatuses() {
        assertThat(lookup.lookup(connector(FakeShopify.TOKEN), "1002").order().status()).isEqualTo(OrderStatus.PREPARING);
        assertThat(lookup.lookup(connector(FakeShopify.TOKEN), " 1003 ").order().status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(lookup.lookup(connector(FakeShopify.TOKEN), "#1004").order().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(lookup.lookup(connector(FakeShopify.TOKEN), "1002").order().carrier()).isNull();
    }

    @Test
    @DisplayName("an unknown number is not found; a blank one is not a request")
    void notFound() {
        OrderLookupResult result = lookup.lookup(connector(FakeShopify.TOKEN), "#9999");
        assertThat(result.found()).isFalse();
        assertThat(result.outcome()).isEqualTo(OrderLookupResult.NOT_FOUND);
        assertThat(result.explanation()).contains("#1001");
        int before = shopify.requests.size();
        assertThat(lookup.lookup(connector(FakeShopify.TOKEN), "  ").outcome()).isEqualTo(OrderLookupResult.NOT_FOUND);
        assertThat(shopify.requests).hasSize(before);
    }

    @Test
    @DisplayName("a refused token and an unreachable store are 'unavailable', never 'not found'")
    void unavailable() throws Exception {
        OrderLookupResult refused = lookup.lookup(connector("shpat_wrong"), "#1001");
        assertThat(refused.outcome()).isEqualTo(OrderLookupResult.UNAVAILABLE);
        assertThat(refused.explanation()).contains("401").contains("Do not tell the customer the order does not exist");

        ShopifyOrderLookup nowhere = new ShopifyOrderLookup(RestClient.builder(),
                new ConnectorProperties(null, Duration.ofSeconds(1), "http://127.0.0.1:1", null, true));
        OrderLookupResult down = nowhere.lookup(connector(FakeShopify.TOKEN), "#1001");
        assertThat(down.outcome()).isEqualTo(OrderLookupResult.UNAVAILABLE);
        assertThat(down.explanation()).contains("could not be reached");

        assertThat(lookup.shopName(connector(FakeShopify.TOKEN))).isEqualTo("Northwind Lamps");
    }
}
