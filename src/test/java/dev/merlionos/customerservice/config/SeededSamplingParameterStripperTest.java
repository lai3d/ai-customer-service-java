package dev.merlionos.customerservice.config;

import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring AI seeds a temperature for every provider — Anthropic 0.8, OpenAI 0.7, Google 0.7 —
 * and the current frontier models reject it. Claude Opus 5 returns HTTP 400 for any sampling
 * parameter; GPT-5 returns "Unsupported value: 'temperature' does not support 0.7 with this
 * model". Both were found by calling the live API after this codebase had asserted, in a
 * comment, that only Anthropic needed the workaround.
 */
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class SeededSamplingParameterStripperTest {

    @Nested
    @SpringBootTest
    @Import(PostgresTestcontainer.class)
    @ActiveProfiles("test")
    @DisplayName("Anthropic")
    class Anthropic {

        @Autowired AnthropicChatProperties properties;

        @Test
        void seededSamplingParametersAreRemoved() {
            assertThat(properties.getOptions().getTemperature()).isNull();
            assertThat(properties.getOptions().getTopP()).isNull();
            assertThat(properties.getOptions().getTopK()).isNull();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.ai.model.chat=openai",
            "spring.ai.openai.api-key=test-key-not-used"})
    @Import(PostgresTestcontainer.class)
    @ActiveProfiles("test")
    @DisplayName("OpenAI")
    class OpenAi {

        @Autowired OpenAiChatProperties properties;

        @Test
        void seededSamplingParametersAreRemoved() {
            assertThat(properties.getOptions().getTemperature())
                    .as("GPT-5 rejects any temperature other than its default")
                    .isNull();
            assertThat(properties.getOptions().getTopP()).isNull();
        }

        @Test
        @DisplayName("usage is requested, or a streamed turn reports no tokens at all")
        void streamUsageIsEnabled() {
            // OpenAI omits usage from a streamed response unless asked. Without this the
            // conversation budget never triggers and the cost meters stay at zero.
            assertThat(properties.getOptions().getStreamUsage()).isTrue();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.ai.model.chat=openai",
            "spring.ai.openai.api-key=test-key-not-used",
            "spring.ai.openai.chat.options.temperature=0.3"})
    @Import(PostgresTestcontainer.class)
    @ActiveProfiles("test")
    @DisplayName("an explicit setting is a choice, not a seed")
    class ExplicitlyConfigured {

        @Autowired OpenAiChatProperties properties;

        @Test
        void deliberateConfigurationSurvives() {
            // Stripping unconditionally would silently discard configuration -- the same class
            // of bug as the one this component exists to fix.
            assertThat(properties.getOptions().getTemperature()).isEqualTo(0.3);
        }
    }
}
