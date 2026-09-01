package dev.merlionos.customerservice.config;

import org.springframework.ai.model.anthropic.autoconfigure.AnthropicConnectionProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Refuses to start without a usable Anthropic API key.
 *
 * <p>This is not belt-and-braces. {@code api-key: ${ANTHROPIC_API_KEY}} looks like it fails
 * fast, but Spring's configuration-property binder ignores an unresolvable placeholder rather
 * than raising -- unlike {@code @Value} -- so the property binds to the literal string
 * {@code "${ANTHROPIC_API_KEY}"}. The application then starts in about a second, both actuator
 * probes report UP, Kubernetes marks the pod ready, and it takes production traffic. Every
 * chat request fails with a 401 the customer sees as a broken assistant.
 *
 * <p>A deployment missing its credentials should crash-loop visibly instead. Verified against
 * a container with the variable unset entirely.
 */
@Component
class AnthropicApiKeyValidator implements InitializingBean {

    private static final String UNRESOLVED_PLACEHOLDER_PREFIX = "${";

    private final AnthropicConnectionProperties connectionProperties;

    AnthropicApiKeyValidator(AnthropicConnectionProperties connectionProperties) {
        this.connectionProperties = connectionProperties;
    }

    @Override
    public void afterPropertiesSet() {
        validate(connectionProperties.getApiKey());
    }

    /** Package-private so the failure modes can be exercised without booting a context. */
    static void validate(String apiKey) {
        if (!StringUtils.hasText(apiKey) || apiKey.strip().startsWith(UNRESOLVED_PLACEHOLDER_PREFIX)) {
            throw new IllegalStateException("""
                    No Anthropic API key is configured, so every chat request would fail with a \
                    401 while the application reported itself healthy.

                    Set the ANTHROPIC_API_KEY environment variable, or spring.ai.anthropic.api-key. \
                    Locally: copy .env.example to .env, fill in the key, and `set -a && source .env`. \
                    In Kubernetes it comes from the secret described in k8s/README.md.""");
        }
    }
}
