package dev.merlionos.customerservice.orders.shopify;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shopify's Admin REST API as far as the connector uses it: {@code orders.json?name=} and
 * {@code shop.json}, checking the token header, answering in Shopify's shapes. The three
 * orders cover the statuses the mapping has to get right.
 */
public final class FakeShopify implements AutoCloseable {

    public static final String TOKEN = "shpat_test_token_0123456789abcdef";
    public final HttpServer server;
    public final List<String> requests = new CopyOnWriteArrayList<>();

    static final String ORDER_1001 = """
            {"id": 1, "name": "#1001", "created_at": "2026-08-27T10:15:00+08:00", "cancelled_at": null,
             "financial_status": "paid", "fulfillment_status": "fulfilled",
             "line_items": [{"title": "Noise-cancelling headphones", "quantity": 1}],
             "fulfillments": [{"tracking_company": "SingPost", "tracking_number": "SP884213906SG",
                               "shipment_status": "in_transit", "estimated_delivery_at": "2026-09-03T00:00:00+08:00"}]}""";
    static final String ORDER_1002 = """
            {"id": 2, "name": "#1002", "created_at": "2026-08-31T09:00:00+08:00", "cancelled_at": null,
             "financial_status": "paid", "fulfillment_status": null,
             "line_items": [{"title": "Cotton t-shirt (M, navy)", "quantity": 2}], "fulfillments": []}""";
    static final String ORDER_1003 = """
            {"id": 3, "name": "#1003", "created_at": "2026-08-18T09:00:00+08:00", "cancelled_at": null,
             "financial_status": "paid", "fulfillment_status": "fulfilled",
             "line_items": [{"title": "Espresso machine", "quantity": 1}],
             "fulfillments": [{"tracking_company": "DHL", "tracking_number": "JD0002088776", "shipment_status": "delivered"}]}""";
    static final String ORDER_1004 = """
            {"id": 4, "name": "#1004", "created_at": "2026-08-29T09:00:00+08:00", "cancelled_at": "2026-08-30T09:00:00+08:00",
             "financial_status": "refunded", "fulfillment_status": null, "line_items": [{"title": "Mechanical keyboard", "quantity": 1}], "fulfillments": []}""";
    static final Map<String, String> ORDERS = Map.of("#1001", ORDER_1001, "#1002", ORDER_1002, "#1003", ORDER_1003, "#1004", ORDER_1004);

    public FakeShopify() throws IOException {
        this(0);
    }

    public FakeShopify(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(path + (query == null ? "" : "?" + query));
            String token = exchange.getRequestHeaders().getFirst("X-Shopify-Access-Token");
            if (!TOKEN.equals(token)) {
                respond(exchange, 401, "{\"errors\":\"[API] Invalid API key or access token\"}");
                return;
            }
            if (path.endsWith("/shop.json")) {
                respond(exchange, 200, "{\"shop\":{\"name\":\"Northwind Lamps\",\"myshopify_domain\":\"northwind-lamps.myshopify.com\"}}");
                return;
            }
            if (path.endsWith("/orders.json")) {
                String name = null;
                for (String pair : (query == null ? "" : query).split("&")) {
                    if (pair.startsWith("name=")) {
                        name = URLDecoder.decode(pair.substring(5), StandardCharsets.UTF_8);
                    }
                }
                String order = name == null ? null : ORDERS.get(name);
                respond(exchange, 200, "{\"orders\":[" + (order == null ? "" : order) + "]}");
                return;
            }
            respond(exchange, 404, "{\"errors\":\"Not Found\"}");
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
