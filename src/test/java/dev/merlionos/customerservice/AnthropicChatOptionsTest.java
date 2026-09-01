package dev.merlionos.customerservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claude Opus 5 and Sonnet 5 removed the sampling parameters: sending {@code temperature},
 * {@code top_p} or {@code top_k} returns HTTP 400. Spring AI's AnthropicChatProperties
 * seeds a non-null default temperature, so application.yml has to null it back out.
 * This test fails the build if that null is ever dropped.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AnthropicChatOptionsTest {

    @Autowired
    AnthropicChatModel chatModel;

    @Test
    @DisplayName("no sampling parameters are sent -- current Claude models reject them")
    void samplingParametersAreUnset() {
        AnthropicChatOptions options = (AnthropicChatOptions) chatModel.getDefaultOptions();

        assertThat(options.getTemperature())
                .as("temperature must be null; Claude Opus 5 rejects it with HTTP 400")
                .isNull();
        assertThat(options.getTopP()).as("top_p must be null").isNull();
        assertThat(options.getTopK()).as("top_k must be null").isNull();
    }

    @Test
    @DisplayName("model id is a current Claude model")
    void modelIsConfigured() {
        AnthropicChatOptions options = (AnthropicChatOptions) chatModel.getDefaultOptions();

        assertThat(options.getModel()).isEqualTo("claude-opus-5");
    }
}
