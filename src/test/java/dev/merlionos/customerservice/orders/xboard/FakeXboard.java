package dev.merlionos.customerservice.orders.xboard;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An Xboard panel's user API as far as the connector uses it, answering in the panel's
 * shapes: bytes, cents and Unix seconds under a {@code data} envelope, {@code 403} without
 * the customer's bearer token, {@code guest/comm/config} open to anyone.
 */
public final class FakeXboard implements AutoCloseable {

    public static final String CUSTOMER_TOKEN = "1|xboardSanctumTokenForAlice0123456789";
    public static final String ADMIN_TOKEN = "9|xboardSanctumTokenForTheOperator0123";
    public static final String ADMIN_PATH = "a1b2c3d4";
    /** Tickets saved as the customer, newest first as the panel lists them. */
    public final List<Map<String, Object>> tickets = new CopyOnWriteArrayList<>();
    static final String ARTICLES = """
            {"data":[{"id":7,"title":"How to configure Clash","category":"Clients","show":true,"updated_at":1788739200},
                     {"id":8,"title":"Shadowrocket 使用教程","category":"客户端","show":true,"updated_at":1788739200},
                     {"id":9,"title":"Hidden draft","category":"Clients","show":false,"updated_at":1788739200}]}""";
    /** Alice as the admin API lists her: id 1, Telegram 424242 bound, balance already in units. */
    static final String ADMIN_USERS_ALICE = """
            {"data":[{"id":1,"email":"alice@example.com","telegram_id":424242,"plan_id":2,"expired_at":1792108800,"u":42949672960,
                      "d":66035122176,"transfer_enable":214748364800,"balance":12.5,"plan":{"id":2,"name":"Pro 200G"}}],"total":1}""";
    static final String ADMIN_ORDERS_ALICE = """
            {"data":[{"trade_no":"2026090712345678","status":3,"total_amount":1990,"created_at":1788739200,"user_id":1,"plan":{"name":"Pro 200G"}}],"total":1}""";
    static final Map<String, String> BODIES = Map.of(
            "7", "{\"data\":{\"id\":7,\"title\":\"How to configure Clash\",\"language\":\"en-US\",\"body\":\"<h2>Clash</h2><p>Download Clash Verge, open Profiles and paste your subscription URL from the panel. Click Update, then choose a proxy group.</p>\"}}",
            "8", "{\"data\":{\"id\":8,\"title\":\"Shadowrocket 使用教程\",\"language\":\"zh-CN\",\"body\":\"在 App Store 下载 Shadowrocket，复制面板里的订阅链接，打开应用后点击右上角加号添加订阅。\"}}",
            "9", "{\"data\":{\"id\":9,\"title\":\"Hidden draft\",\"body\":\"not published\"}}");
    /** expired_at 1792108800 = 2026-10-16 UTC; 200 GB allowance, 40 GB up, 61.5 GB down. */
    static final String SUBSCRIBE = """
            {"data":{"plan_id":2,"token":"subtoken","expired_at":1792108800,"u":42949672960,"d":66035122176,
             "transfer_enable":214748364800,"email":"alice@example.com","reset_day":9,"next_reset_at":1758931200,
             "plan":{"id":2,"name":"Pro 200G","transfer_enable":200},"subscribe_url":"https://panel.example.com/api/v1/client/subscribe?token=subtoken"}}""";
    static final String INFO = """
            {"data":{"email":"alice@example.com","transfer_enable":214748364800,"expired_at":1792108800,"balance":1250,
             "plan_id":2,"uuid":"u-1"}}""";
    static final String ORDERS = """
            {"data":[{"trade_no":"2026090712345678","status":3,"total_amount":1990,"created_at":1788739200,"plan":{"name":"Pro 200G"}},
                     {"trade_no":"2026080712345678","status":2,"total_amount":1990,"created_at":1786060800,"plan":{"name":"Pro 200G"}},
                     {"trade_no":"2026070712345678","status":0,"total_amount":990,"created_at":1783382400,"plan":{"name":"Lite 50G"}}]}""";
    public final HttpServer server;
    public final List<String> requests = new CopyOnWriteArrayList<>();

    public FakeXboard() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(path);
            String query = exchange.getRequestURI().getRawQuery();
            if (path.startsWith("/api/v2/" + ADMIN_PATH + "/user/fetch") || path.startsWith("/api/v2/" + ADMIN_PATH + "/order/fetch")) {
                if (!("Bearer " + ADMIN_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    respond(exchange, 403, "{\"message\":\"Unauthorized\"}");
                    return;
                }
                String decoded = java.net.URLDecoder.decode(query == null ? "" : query, StandardCharsets.UTF_8);
                boolean alice = decoded.contains("filter[0][id]=telegram_id&filter[0][value]=eq:424242")
                        || decoded.contains("filter[0][id]=id&filter[0][value]=eq:1")
                        || decoded.contains("filter[0][id]=user_id&filter[0][value]=eq:1");
                respond(exchange, 200, !alice ? "{\"data\":[],\"total\":0}" : path.contains("/order/") ? ADMIN_ORDERS_ALICE : ADMIN_USERS_ALICE);
                return;
            }
            if (path.startsWith("/api/v2/" + ADMIN_PATH + "/knowledge/fetch")) {
                if (!("Bearer " + ADMIN_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    respond(exchange, 403, "{\"message\":\"Unauthorized\"}");
                    return;
                }
                String id = query != null && query.startsWith("id=") ? query.substring(3) : null;
                respond(exchange, 200, id == null ? ARTICLES : BODIES.getOrDefault(id, "{\"data\":null}"));
                return;
            }
            if (path.endsWith("/api/v1/guest/comm/config")) {
                respond(exchange, 200, "{\"data\":{\"app_name\":\"Northwind Cloud\",\"is_email_verify\":1}}");
                return;
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + CUSTOMER_TOKEN).equals(auth)) {
                respond(exchange, 403, "{\"message\":\"未登录或登陆已过期\"}");
                return;
            }
            if (path.endsWith("/api/v1/user/ticket/save") && "POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                tickets.addFirst(Map.of("id", 100 + tickets.size(), "body", body, "status", 0));
                respond(exchange, 200, "{\"data\":true}");
            }
            else if (path.endsWith("/api/v1/user/ticket/fetch")) {
                StringBuilder json = new StringBuilder("{\"data\":[");
                for (int i = 0; i < tickets.size(); i++) {
                    json.append(i == 0 ? "" : ",").append("{\"id\":").append(tickets.get(i).get("id")).append(",\"status\":0,\"subject\":\"x\"}");
                }
                respond(exchange, 200, json.append("]}").toString());
            }
            else if (path.endsWith("/api/v1/user/getSubscribe")) {
                respond(exchange, 200, SUBSCRIBE);
            }
            else if (path.endsWith("/api/v1/user/info")) {
                respond(exchange, 200, INFO);
            }
            else if (path.endsWith("/api/v1/user/order/fetch")) {
                respond(exchange, 200, ORDERS);
            }
            else {
                respond(exchange, 404, "{\"message\":\"Not Found\"}");
            }
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
