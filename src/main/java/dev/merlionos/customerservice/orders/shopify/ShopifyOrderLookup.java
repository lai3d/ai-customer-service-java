package dev.merlionos.customerservice.orders.shopify;

import dev.merlionos.customerservice.orders.ConnectorProperties;
import dev.merlionos.customerservice.orders.Order;
import dev.merlionos.customerservice.orders.OrderConnector;
import dev.merlionos.customerservice.orders.OrderLookupResult;
import dev.merlionos.customerservice.orders.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Orders from a tenant's Shopify store, over the Admin REST API: one call,
 * {@code GET /admin/api/{version}/orders.json?name=<order name>&status=any}, with the custom
 * app's access token in {@code X-Shopify-Access-Token}. A customer writes "1001", "#1001" or
 * whatever the store's name format makes of it, so the number is tried as given and, if it
 * has no {@code #}, with one. What comes back is folded into the {@link Order} the tool has
 * always returned, so the tool, the prompt and the tests know nothing of Shopify.
 *
 * <p>Failure is a value: an unreachable or refusing store is {@link OrderLookupResult#unavailable},
 * never "not found", because the model tells the customer what the result says.
 *
 * <p>Not here: proving the customer owns the order. Anyone who knows a number can ask about
 * it, as with the bundled mock; matching the customer's email or a verified session is the
 * next step, and a decision for the pilot.
 */
@Component
public class ShopifyOrderLookup {

    private static final Logger log = LoggerFactory.getLogger(ShopifyOrderLookup.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    static final String TOKEN_HEADER = "X-Shopify-Access-Token";

    private final RestClient.Builder builder;
    private final ConnectorProperties properties;

    public ShopifyOrderLookup(RestClient.Builder builder, ConnectorProperties properties) {
        this.builder = builder;
        this.properties = properties;
    }

    public OrderLookupResult lookup(OrderConnector connector, String orderNumber) {
        String number = orderNumber == null ? "" : orderNumber.strip();
        if (number.isEmpty()) {
            return OrderLookupResult.notFound("No order number was given.");
        }
        try {
            // As given; then with the hash Shopify names orders with; then the digits alone with
            // the hash, since the model tends to dress a bare number in the ORD- of the tool's example.
            Optional<Map<String, Object>> order = find(connector, number);
            if (order.isEmpty() && !number.startsWith("#")) {
                order = find(connector, "#" + number);
            }
            String digits = number.replaceAll("\\D", "");
            if (order.isEmpty() && !digits.isEmpty() && !("#" + digits).equals(number) && !digits.equals(number)) {
                order = find(connector, "#" + digits);
            }
            return order.map(o -> OrderLookupResult.found(toOrder(o)))
                    .orElseGet(() -> OrderLookupResult.notFound("No order matches that number in the store. It may have been "
                            + "mistyped; order numbers look like #1001."));
        }
        catch (HttpClientErrorException e) {
            log.warn("Shopify refused a lookup for tenant {} ({}): {}", connector.tenantId(), connector.shopDomain(), e.getStatusCode());
            return OrderLookupResult.unavailable("The store's order system refused the request (" + e.getStatusCode().value()
                    + "); the connection needs attention from the store's staff. Do not tell the customer the order does not exist.");
        }
        catch (RuntimeException e) {
            log.warn("Shopify lookup failed for tenant {} ({}): {}", connector.tenantId(), connector.shopDomain(), e.toString());
            return OrderLookupResult.unavailable("The store's order system could not be reached right now; ask the customer to "
                    + "try again in a few minutes. Do not tell the customer the order does not exist.");
        }
    }

    /** The store's own name, for the admin's "test connection". */
    public String shopName(OrderConnector connector) {
        Map<String, Object> body = client(connector).get().uri("/admin/api/{v}/shop.json", connector.apiVersion()).retrieve().body(MAP);
        Object shop = body == null ? null : body.get("shop");
        return shop instanceof Map<?, ?> m && m.get("name") != null ? String.valueOf(m.get("name")) : connector.shopDomain();
    }

    private Optional<Map<String, Object>> find(OrderConnector connector, String name) {
        Map<String, Object> body = client(connector).get()
                .uri(uri -> uri.path("/admin/api/" + connector.apiVersion() + "/orders.json")
                        .queryParam("name", name).queryParam("status", "any").queryParam("limit", 5).build())
                .retrieve().body(MAP);
        Object orders = body == null ? null : body.get("orders");
        if (!(orders instanceof List<?> list)) {
            return Optional.empty();
        }
        // Shopify's name filter is a prefix match on some versions: keep the exact one.
        return list.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o)
                .filter(o -> name.equalsIgnoreCase(String.valueOf(o.get("name"))))
                .findFirst()
                .or(() -> list.size() == 1 && list.getFirst() instanceof Map<?, ?> only ? Optional.of((Map<String, Object>) only) : Optional.empty());
    }

    private RestClient client(OrderConnector connector) {
        String base = properties.shopifyBaseUrl() == null || properties.shopifyBaseUrl().isBlank()
                ? "https://" + connector.shopDomain() : properties.shopifyBaseUrl();
        return builder.clone()
                .baseUrl(base)
                .defaultHeader(TOKEN_HEADER, connector.accessToken())
                .defaultHeader("Accept", "application/json")
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(properties.timeoutOrDefault()).withReadTimeout(properties.timeoutOrDefault())))
                .build();
    }

    // --- Shopify's order into ours --------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Order toOrder(Map<String, Object> o) {
        List<Map<String, Object>> fulfillments = o.get("fulfillments") instanceof List<?> l
                ? l.stream().filter(Map.class::isInstance).map(f -> (Map<String, Object>) f).toList() : List.of();
        Map<String, Object> latest = fulfillments.isEmpty() ? null : fulfillments.getLast();
        String carrier = latest == null ? null : text(latest.get("tracking_company"));
        String tracking = latest == null ? null : text(latest.get("tracking_number"));
        LocalDate placed = date(o.get("created_at"));
        LocalDate estimated = latest == null ? null : date(latest.get("estimated_delivery_at"));
        List<Map<String, Object>> items = o.get("line_items") instanceof List<?> l
                ? l.stream().filter(Map.class::isInstance).map(i -> (Map<String, Object>) i).toList() : List.of();
        String summary = items.isEmpty() ? null : String.join(", ", items.stream()
                .map(i -> text(i.get("quantity")) + " x " + text(i.get("title"))).toList());
        return new Order(text(o.get("name")), status(o, latest), placed, estimated, carrier, tracking, summary);
    }

    static OrderStatus status(Map<String, Object> o, Map<String, Object> latestFulfillment) {
        if (o.get("cancelled_at") != null) {
            return OrderStatus.CANCELLED;
        }
        String financial = lower(o.get("financial_status"));
        if (financial.equals("refunded") || financial.equals("partially_refunded")) {
            return OrderStatus.RETURN_IN_PROGRESS;
        }
        String fulfillment = lower(o.get("fulfillment_status"));
        if (fulfillment.equals("fulfilled") || fulfillment.equals("partial")) {
            String shipment = latestFulfillment == null ? "" : lower(latestFulfillment.get("shipment_status"));
            return switch (shipment) {
                case "delivered" -> OrderStatus.DELIVERED;
                case "in_transit", "out_for_delivery", "attempted_delivery", "ready_for_pickup" -> OrderStatus.IN_TRANSIT;
                default -> OrderStatus.DISPATCHED;
            };
        }
        return OrderStatus.PREPARING;
    }

    private static String lower(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value)).toLocalDate();
        }
        catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
