package dev.merlionos.customerservice.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param includeQueryContent whether a customer's own words may be attached to spans exported
 *                            to the tracing backend
 */
@ConfigurationProperties("app.observability")
public record ObservabilityProperties(boolean includeQueryContent) {
}
