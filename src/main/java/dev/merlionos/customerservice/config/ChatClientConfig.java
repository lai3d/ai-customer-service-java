package dev.merlionos.customerservice.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import dev.merlionos.customerservice.rag.RagProperties;
import dev.merlionos.customerservice.tools.OrderTools;
import dev.merlionos.customerservice.tools.SupportTicketTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
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

            Reference material is selected by similarity, so some of it will have nothing to \
            do with what was asked. Judge each passage on whether it actually answers the \
            question. If none of it does, say so plainly -- do not stretch an unrelated \
            passage to fit.
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

    /**
     * The advisor chain. Order is not cosmetic here: the memory advisor must run first.
     *
     * <p>{@code QuestionAnswerAdvisor} rewrites the user message to carry the retrieved
     * passages, and {@code MessageChatMemoryAdvisor} stores whatever user message it sees.
     * Run the other way round, every retrieved passage would be written into the customer's
     * conversation history and re-sent on each subsequent turn.
     *
     * <p>Spring AI's defaults already order them correctly -- the memory advisor sits at
     * {@code Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER} and the QA advisor at 0 -- so this
     * method relies on them rather than restating the numbers. {@code AdvisorChainOrderTest}
     * fails if that ever stops being true.
     */
    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory,
                          VectorStore vectorStore, RagProperties ragProperties,
                          OrderTools orderTools, SupportTicketTools supportTicketTools) {
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(ragProperties.topK())
                .similarityThreshold(ragProperties.similarityThreshold())
                .build();

        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor.builder(vectorStore).searchRequest(searchRequest).build())
                .defaultTools(orderTools, supportTicketTools)
                .build();
    }

    /**
     * Stops internal failure detail from reaching the customer.
     *
     * <p>Spring AI's default processor hands a thrown tool exception's message back to the
     * model as the tool result. That message then informs a customer-facing answer, so a
     * connection string, a stack frame, or an internal id in an exception becomes something the
     * assistant can repeat. Tools here return failures as values instead; anything that still
     * throws is unexpected, and the model is told only that the tool failed.
     */
    @Bean
    ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        Logger log = LoggerFactory.getLogger(ChatClientConfig.class);

        return (ToolExecutionException exception) -> {
            log.error("Tool '{}' failed", exception.getToolDefinition().name(), exception);
            return "The tool failed to run. Tell the customer you could not complete that step "
                    + "and offer to raise a support ticket.";
        };
    }
}
