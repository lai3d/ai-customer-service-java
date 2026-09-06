package dev.merlionos.customerservice.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param turnLease       how long one turn may hold its conversation before another may take it.
 *                        Must exceed the HTTP read timeout; {@code ChatPropertiesTest} pins that
 * @param recordRetention how long a conversation's text is kept: its turn records and its chat
 *                        memory, both deleted by {@code ConversationRetentionSweeper} once older.
 *                        Must be positive; the sweeper refuses to start otherwise
 */
@ConfigurationProperties("app.chat")
public record ChatProperties(Duration turnLease, Duration recordRetention) {
}
