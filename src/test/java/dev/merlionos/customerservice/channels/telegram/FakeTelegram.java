package dev.merlionos.customerservice.channels.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The Bot API as far as the channel uses it: one bot token is valid, getUpdates long-polls a
 * queue the test fills, sendMessage records what the customer would have seen, setWebhook
 * and deleteWebhook record their calls.
 */
public final class FakeTelegram implements AutoCloseable {

    public static final String TOKEN = "123456789:AAHfakeBotTokenForTheTests0123456789";
    private static final ObjectMapper JSON = new ObjectMapper();
    public final HttpServer server;
    public final LinkedBlockingQueue<Map<String, Object>> updates = new LinkedBlockingQueue<>();
    public final List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> webhooks = new CopyOnWriteArrayList<>();
    public volatile int webhookDeletes;
    private long nextUpdateId = 1;

    public FakeTelegram() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/bot" + TOKEN + "/")) {
                respond(exchange, 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
                return;
            }
            String method = path.substring(("/bot" + TOKEN + "/").length());
            byte[] raw = exchange.getRequestBody().readAllBytes();
            Map<String, Object> body = JSON.readValue(raw.length == 0 ? "{}" : new String(raw, StandardCharsets.UTF_8), Map.class);
            switch (method) {
                case "getMe" -> respond(exchange, 200, "{\"ok\":true,\"result\":{\"id\":123456789,\"is_bot\":true,\"username\":\"northwind_support_bot\"}}");
                case "getUpdates" -> {
                    Map<String, Object> update;
                    try {
                        update = updates.poll(2, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e) {
                        update = null;
                    }
                    respond(exchange, 200, "{\"ok\":true,\"result\":" + (update == null ? "[]" : "[" + JSON.writeValueAsString(update) + "]") + "}");
                }
                case "sendMessage" -> {
                    sent.add(body);
                    respond(exchange, 200, "{\"ok\":true,\"result\":{\"message_id\":" + sent.size() + "}}");
                }
                case "sendChatAction" -> respond(exchange, 200, "{\"ok\":true,\"result\":true}");
                case "setWebhook" -> {
                    webhooks.add(body);
                    respond(exchange, 200, "{\"ok\":true,\"result\":true}");
                }
                case "deleteWebhook" -> {
                    webhookDeletes++;
                    respond(exchange, 200, "{\"ok\":true,\"result\":true}");
                }
                default -> respond(exchange, 404, "{\"ok\":false,\"description\":\"Not Found\"}");
            }
        });
        server.start();
    }

    /** A text message from a private chat, queued for the next getUpdates. */
    public Map<String, Object> textMessage(long chatId, String text, String languageCode) {
        Map<String, Object> update = Map.of("update_id", nextUpdateId++, "message", Map.of(
                "message_id", nextUpdateId, "date", 1788739200,
                "from", Map.of("id", chatId, "first_name", "Alice", "language_code", languageCode),
                "chat", Map.of("id", chatId, "type", "private"), "text", text));
        updates.add(update);
        return update;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The texts sent to one chat, in order. */
    public List<String> textsTo(long chatId) {
        return sent.stream().filter(m -> ((Number) m.get("chat_id")).longValue() == chatId).map(m -> String.valueOf(m.get("text"))).toList();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
