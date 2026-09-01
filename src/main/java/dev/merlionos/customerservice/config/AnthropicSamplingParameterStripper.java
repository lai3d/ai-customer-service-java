package dev.merlionos.customerservice.config;

import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Removes the sampling parameters from the Anthropic chat defaults.
 *
 * <p>Claude Opus 5 and Sonnet 5 dropped {@code temperature}, {@code top_p} and
 * {@code top_k}; sending any of them returns HTTP 400. Spring AI 1.1.8's
 * {@link AnthropicChatProperties} seeds a non-null default temperature (0.8) in a field
 * initialiser, and Spring's binder cannot write a null back over it -- both
 * {@code temperature: null} and {@code temperature: ""} are treated as "absent" and
 * leave the seeded value in place.
 *
 * <p>Post-processing the properties bean is therefore the only hook that lands before
 * {@code AnthropicChatModel} reads the options. Since
 * {@code AnthropicApi.ChatCompletionRequest} is annotated {@code @JsonInclude(NON_NULL)},
 * nulling the fields keeps them out of the serialised request entirely.
 *
 * <p>Delete this class once Spring AI stops seeding a default temperature, or if the
 * configured model is rolled back to one that still accepts sampling parameters.
 * {@code AnthropicChatOptionsTest} guards the behaviour either way.
 */
@Component
class AnthropicSamplingParameterStripper implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof AnthropicChatProperties properties) {
            properties.getOptions().setTemperature(null);
            properties.getOptions().setTopP(null);
            properties.getOptions().setTopK(null);
        }
        return bean;
    }
}
