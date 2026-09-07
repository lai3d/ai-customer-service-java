package dev.merlionos.customerservice.channels.telegram;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The Bot API, the five methods this channel uses. Every call is
 * {@code POST https://api.telegram.org/bot<token>/<method>} with a JSON body and answers
 * {@code {"ok":true,"result":...}}; a wrong token is {@code 401}, which the caller turns into
 * a sentence for the admin. The token is in the URL, as Telegram designed it, so it must
 * never be logged; nothing here logs a URL.
 */
@Component
public class TelegramApi {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    /** Telegram's limit on one message's text. */
    public static final int MESSAGE_LIMIT = 4096;

    private final RestClient.Builder builder;
    private final TelegramProperties properties;

    public TelegramApi(RestClient.Builder builder, TelegramProperties properties) {
        this.builder = builder;
        this.properties = properties;
    }

    /** The bot's own account: {@code username} is what the admin is shown. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMe(String token) {
        return (Map<String, Object>) result(client(token, Duration.ofSeconds(10)).post().uri("/getMe").retrieve().body(MAP));
    }

    /** Long polling: waits up to the configured timeout for updates after {@code offset}. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getUpdates(String token, long offset) {
        Duration wait = properties.pollTimeoutOrDefault();
        Object result = result(client(token, wait.plusSeconds(10)).post().uri("/getUpdates")
                .body(Map.of("offset", offset, "timeout", wait.toSeconds(), "allowed_updates", List.of("message")))
                .retrieve().body(MAP));
        return result instanceof List<?> l ? l.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).toList() : List.of();
    }

    public void sendMessage(String token, long chatId, String text) {
        result(client(token, Duration.ofSeconds(15)).post().uri("/sendMessage")
                .body(Map.of("chat_id", chatId, "text", text)).retrieve().body(MAP));
    }

    /** "typing…" in the chat while the model works; failure here is nobody's problem. */
    public void sendTyping(String token, long chatId) {
        try {
            client(token, Duration.ofSeconds(5)).post().uri("/sendChatAction")
                    .body(Map.of("chat_id", chatId, "action", "typing")).retrieve().body(MAP);
        }
        catch (RuntimeException ignored) {
            // A typing hint is not worth a warning.
        }
    }

    public void setWebhook(String token, String url, String secret) {
        result(client(token, Duration.ofSeconds(10)).post().uri("/setWebhook")
                .body(Map.of("url", url, "secret_token", secret, "allowed_updates", List.of("message"))).retrieve().body(MAP));
    }

    public void deleteWebhook(String token) {
        result(client(token, Duration.ofSeconds(10)).post().uri("/deleteWebhook").body(Map.of("drop_pending_updates", false)).retrieve().body(MAP));
    }

    private static Object result(Map<String, Object> body) {
        if (body == null || !Boolean.TRUE.equals(body.get("ok"))) {
            throw new IllegalStateException("Telegram answered: " + (body == null ? "nothing" : body.get("description")));
        }
        return body.get("result");
    }

    private RestClient client(String token, Duration timeout) {
        return builder.clone().baseUrl(properties.apiBaseUrlOrDefault() + "/bot" + token)
                .defaultHeader("Accept", "application/json")
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(Duration.ofSeconds(10)).withReadTimeout(timeout)))
                .build();
    }
}
