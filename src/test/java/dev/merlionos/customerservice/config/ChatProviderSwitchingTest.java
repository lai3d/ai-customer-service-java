package dev.merlionos.customerservice.config;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.chat.ChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider is configuration, not code.
 *
 * <p>Everything the application does around the model -- the advisor chain, conversation
 * memory, retrieval, the two tools, SSE streaming, the metrics and spans -- is written against
 * Spring AI's {@code ChatModel} interface. These tests boot the real context under each
 * provider with a throwaway key and check that the wiring is genuinely provider-independent,
 * which is the claim that would otherwise be made in a README and never checked.
 *
 * <p>They do not call any provider's API. Contract differences between providers -- tool-call
 * reliability, streaming chunk shapes, how each treats a system prompt -- can only be measured
 * against the live services, which costs money and needs three sets of credentials.
 */
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class ChatProviderSwitchingTest {

    private static void assertWiringIsIntact(ApplicationContext context, Class<?> expectedModel) {
        assertThat(context.getBean(ChatModel.class))
                .as("the selected provider's model is the one that gets built")
                .isInstanceOf(expectedModel);
        assertThat(context.getBeansOfType(ChatModel.class))
                .as("exactly one chat model, or ChatClient.Builder could not be created")
                .hasSize(1);
        assertThat(context.getBean(ChatClient.class)).isNotNull();
        assertThat(context.getBean(ChatService.class)).isNotNull();
    }

    @Nested
    @SpringBootTest
    @Import(PostgresTestcontainer.class)
    @ActiveProfiles("test")
    @DisplayName("Anthropic, the default")
    class Anthropic {

        @Autowired ApplicationContext context;

        @Test
        void wiringIsIntact() {
            assertWiringIsIntact(context, AnthropicChatModel.class);
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.ai.model.chat=openai",
            "spring.ai.openai.api-key=test-key-not-used"})
    @Import(PostgresTestcontainer.class)
    @ActiveProfiles("test")
    @DisplayName("OpenAI, and by extension any OpenAI-compatible API such as Grok")
    class OpenAi {

        @Autowired ApplicationContext context;

        @Test
        void wiringIsIntact() throws Exception {
            assertWiringIsIntact(context,
                    Class.forName("org.springframework.ai.openai.OpenAiChatModel"));
        }

        @Test
        @DisplayName("no Anthropic model is built when it is not selected")
        void anthropicIsNotBuilt() {
            assertThat(context.getBeansOfType(AnthropicChatModel.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.ai.model.chat=google-genai",
            "spring.ai.google.genai.api-key=test-key-not-used"})
    @Import(PostgresTestcontainer.class)
    @ActiveProfiles("test")
    @DisplayName("Gemini")
    class Gemini {

        @Autowired ApplicationContext context;

        @Test
        void wiringIsIntact() throws Exception {
            assertWiringIsIntact(context,
                    Class.forName("org.springframework.ai.google.genai.GoogleGenAiChatModel"));
        }
    }
}
