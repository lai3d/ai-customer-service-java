package dev.merlionos.customerservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatProviderCredentialsValidatorTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "${ANTHROPIC_API_KEY}", "  ${ANTHROPIC_API_KEY}  "})
    @DisplayName("a missing or unresolved key stops startup rather than passing health checks")
    void rejectsUnusableKeys(String apiKey) {
        // "${ANTHROPIC_API_KEY}" is what the binder actually produces when the environment
        // variable is absent -- it ignores unresolvable placeholders instead of raising.
        assertThatThrownBy(() -> ChatProviderCredentialsValidator
                .validate("anthropic", apiKey, "ANTHROPIC_API_KEY"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    @DisplayName("the message names the provider that is actually selected")
    void messageNamesTheSelectedProvider() {
        assertThatThrownBy(() -> ChatProviderCredentialsValidator
                .validate("openai", null, "OPENAI_API_KEY"))
                .hasMessageContaining("openai")
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    @DisplayName("a real key is accepted")
    void acceptsConfiguredKey() {
        assertThatCode(() -> ChatProviderCredentialsValidator
                .validate("anthropic", "sk-ant-something", "ANTHROPIC_API_KEY"))
                .doesNotThrowAnyException();
    }
}
