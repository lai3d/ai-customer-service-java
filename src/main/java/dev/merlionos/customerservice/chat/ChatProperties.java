package dev.merlionos.customerservice.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param turnLease how long one turn may hold its conversation before another may take it.
 *                  Must exceed the HTTP read timeout; {@code ChatPropertiesTest} pins that
 */
@ConfigurationProperties("app.chat")
public record ChatProperties(Duration turnLease) {
}
