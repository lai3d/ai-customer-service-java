package dev.merlionos.customerservice.channels.telegram;

import java.time.Instant;

/** A tenant's bot. {@code botToken} is the secret as the API needs it; {@link #masked()} is what the admin sees. */
public record TelegramBot(String tenantId, String botToken, String botUsername, String mode, String webhookSecret,
                          Instant configuredAt, String configuredBy) {

    public static final String POLLING = "polling";
    public static final String WEBHOOK = "webhook";

    public TelegramBot masked() {
        String tail = botToken.length() < 4 ? "" : botToken.substring(botToken.length() - 4);
        return new TelegramBot(tenantId, "****" + tail, botUsername, mode, null, configuredAt, configuredBy);
    }
}
