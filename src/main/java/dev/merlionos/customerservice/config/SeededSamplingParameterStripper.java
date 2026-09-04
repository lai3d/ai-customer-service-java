package dev.merlionos.customerservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Removes the sampling parameters Spring AI seeds when nobody asked for them.
 *
 * <p>Every provider's chat properties class sets a temperature in a field initialiser --
 * Anthropic 0.8, OpenAI 0.7, Google 0.7 -- and Spring's binder cannot write a null back over it:
 * both {@code temperature: null} and {@code temperature: ""} are treated as "absent" and leave
 * the seeded value in place. Post-processing the properties bean is the only hook that lands
 * before the chat model reads its options.
 *
 * <p>That seeded value breaks the current frontier models. Claude Opus 5 and Sonnet 5 removed
 * the sampling parameters outright and return HTTP 400 for any of them. GPT-5 returns
 * {@code "Unsupported value: 'temperature' does not support 0.7 with this model. Only the
 * default (1) value is supported."} Both were found by calling the live API, not by reading
 * documentation — and this class previously claimed, in a comment, that only Anthropic needed
 * it. Spring AI's request records are {@code @JsonInclude(NON_NULL)}, so nulling the field
 * keeps it out of the wire format entirely.
 *
 * <p>Only the <em>seeded</em> value is removed. If someone sets
 * {@code spring.ai.openai.chat.options.temperature} deliberately, that is a choice about a model
 * that accepts it, and it survives. The distinction matters: stripping unconditionally would
 * silently discard configuration, which is the same class of bug as the one being fixed.
 */
@Component
class SeededSamplingParameterStripper implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SeededSamplingParameterStripper.class);

    private final Environment environment;

    SeededSamplingParameterStripper(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        switch (bean) {
            case AnthropicChatProperties properties -> {
                var options = properties.getOptions();
                if (notConfigured("spring.ai.anthropic.chat.options.temperature")) {
                    options.setTemperature(null);
                }
                if (notConfigured("spring.ai.anthropic.chat.options.top-p")) {
                    options.setTopP(null);
                }
                if (notConfigured("spring.ai.anthropic.chat.options.top-k")) {
                    options.setTopK(null);
                }
            }
            case OpenAiChatProperties properties -> {
                var options = properties.getOptions();
                if (notConfigured("spring.ai.openai.chat.options.temperature")) {
                    options.setTemperature(null);
                }
                if (notConfigured("spring.ai.openai.chat.options.top-p")) {
                    options.setTopP(null);
                }
            }
            case GoogleGenAiChatProperties properties -> {
                var options = properties.getOptions();
                if (notConfigured("spring.ai.google.genai.chat.options.temperature")) {
                    options.setTemperature(null);
                }
                if (notConfigured("spring.ai.google.genai.chat.options.top-p")) {
                    options.setTopP(null);
                }
                if (notConfigured("spring.ai.google.genai.chat.options.top-k")) {
                    options.setTopK(null);
                }
            }
            default -> { /* not a chat properties bean */ }
        }
        return bean;
    }

    private boolean notConfigured(String property) {
        boolean absent = !environment.containsProperty(property);
        if (!absent) {
            log.info("{} is set explicitly and will be sent as configured", property);
        }
        return absent;
    }
}
