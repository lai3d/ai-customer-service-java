package dev.merlionos.customerservice.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param token the shared secret every internal call carries as a bearer token. Required in
 *              every single-role process; unused in {@code all}, which serves no internal
 *              endpoint. Service-to-service only: customers never see these routes
 */
@ConfigurationProperties("app.internal")
public record InternalProperties(String token) {
}
