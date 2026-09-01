package dev.merlionos.customerservice.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single place where the ChatClient and its advisor chain are assembled. Prompts are
 * never hand-built elsewhere in the codebase.
 */
@Configuration(proxyBeanMethods = false)
class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            You are a customer support assistant. Answer the customer's question directly \
            and concisely, in the language they wrote in.

            Ground every factual claim about orders, accounts, policies, or products in \
            retrieved documents or tool results. If you do not have that grounding, say \
            what you don't know and offer to escalate to a human agent rather than \
            guessing. Never invent order numbers, dates, prices, or policy terms.
            """;

    /**
     * How many messages of history travel with each request. Every message is re-sent and
     * re-billed on every turn, so this is a direct cost and latency lever, not just a
     * memory setting. 40 leaves room for a substantial conversation while keeping the
     * resent prefix bounded.
     */
    private static final int MAX_HISTORY_MESSAGES = 40;

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(MAX_HISTORY_MESSAGES)
                .build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
