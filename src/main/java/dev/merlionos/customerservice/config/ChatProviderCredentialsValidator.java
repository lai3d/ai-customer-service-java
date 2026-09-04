package dev.merlionos.customerservice.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Refuses to start when the selected chat provider has no usable API key.
 *
 * <p>This is not belt-and-braces. {@code api-key: ${ANTHROPIC_API_KEY}} looks like it fails
 * fast, but Spring's configuration-property binder ignores an unresolvable placeholder rather
 * than raising -- unlike {@code @Value} -- so the property binds to the literal string
 * {@code "${ANTHROPIC_API_KEY}"}. The application then starts in about a second, both actuator
 * probes report UP, Kubernetes marks the pod ready, and it takes production traffic. Every
 * chat request fails with a 401 the customer sees as a broken assistant. Verified against a
 * container with the variable unset entirely.
 *
 * <p>Reading the key through {@link Environment} rather than each provider's properties bean
 * is deliberate: only the selected provider's auto-configuration runs, so injecting
 * {@code AnthropicConnectionProperties} would break the context the moment someone set
 * {@code CHAT_PROVIDER=openai}.
 */
@Component
class ChatProviderCredentialsValidator implements InitializingBean {

    static final String PROVIDER_PROPERTY = "spring.ai.model.chat";

    private static final Map<String, String> API_KEY_PROPERTY = Map.of(
            "anthropic", "spring.ai.anthropic.api-key",
            "openai", "spring.ai.openai.api-key",
            "google-genai", "spring.ai.google.genai.api-key",
            "xai", "spring.ai.xai.api-key");

    private static final Map<String, String> ENVIRONMENT_VARIABLE = Map.of(
            "anthropic", "ANTHROPIC_API_KEY",
            "openai", "OPENAI_API_KEY",
            "google-genai", "GEMINI_API_KEY",
            "xai", "XAI_API_KEY");

    private final Environment environment;

    ChatProviderCredentialsValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String provider = environment.getProperty(PROVIDER_PROPERTY, "anthropic");
        String keyProperty = API_KEY_PROPERTY.get(provider);

        if (keyProperty == null) {
            // A provider we have not wired credentials guidance for. Its own auto-configuration
            // still validates; there is nothing useful to add here.
            return;
        }
        validate(provider, readRaw(keyProperty), ENVIRONMENT_VARIABLE.get(provider));
    }

    /** An unresolvable placeholder makes {@code getProperty} throw; that is a missing key. */
    private String readRaw(String property) {
        try {
            return environment.getProperty(property);
        }
        catch (IllegalArgumentException unresolvablePlaceholder) {
            return null;
        }
    }

    /** Package-private so the failure modes can be exercised without booting a context. */
    static void validate(String provider, String apiKey, String environmentVariable) {
        if (!StringUtils.hasText(apiKey) || apiKey.strip().startsWith("${")) {
            throw new IllegalStateException("""
                    No API key is configured for chat provider '%s', so every chat request \
                    would fail with a 401 while the application reported itself healthy.

                    Set the %s environment variable. Locally: copy .env.example to .env, fill \
                    it in, and `set -a && source .env`. In Kubernetes it comes from the secret \
                    described in k8s/README.md.""".formatted(provider, environmentVariable));
        }
    }
}
