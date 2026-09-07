package dev.merlionos.customerservice.channels.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param apiBaseUrl   where the Bot API is; {@code https://api.telegram.org} unless a test or a
 *                     demo stands one in
 * @param publicUrl    this deployment's public origin, for the webhook mode; blank means only
 *                     polling can be configured
 * @param pollTimeout  how long one getUpdates call waits for an update (long polling)
 */
@ConfigurationProperties("app.telegram")
public record TelegramProperties(String apiBaseUrl, String publicUrl, Duration pollTimeout) {

    public String apiBaseUrlOrDefault() {
        return apiBaseUrl == null || apiBaseUrl.isBlank() ? "https://api.telegram.org" : apiBaseUrl.replaceFirst("/+$", "");
    }

    public Duration pollTimeoutOrDefault() {
        return pollTimeout == null ? Duration.ofSeconds(25) : pollTimeout;
    }

    public boolean hasPublicUrl() {
        return publicUrl != null && !publicUrl.isBlank();
    }
}
