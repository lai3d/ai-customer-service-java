package dev.merlionos.customerservice.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The memory advisor has to run before the retrieval advisor. QuestionAnswerAdvisor rewrites
 * the user message to carry retrieved passages, and MessageChatMemoryAdvisor stores whatever
 * user message it is handed -- reverse them and every retrieved passage is written into the
 * customer's history and re-sent on every later turn.
 *
 * <p>Spring AI's defaults get this right today. This test is what notices if a version bump
 * changes them, because the symptom otherwise is a slow, silent inflation of prompt size.
 */
class AdvisorChainOrderTest {

    @Test
    @DisplayName("memory advisor is ordered ahead of the retrieval advisor")
    void memoryAdvisorRunsFirst() {
        int memoryOrder = MessageChatMemoryAdvisor
                .builder(Mockito.mock(ChatMemory.class)).build().getOrder();
        int retrievalOrder = QuestionAnswerAdvisor
                .builder(Mockito.mock(VectorStore.class)).build().getOrder();

        assertThat(memoryOrder)
                .as("memory must be stored before retrieval rewrites the user message")
                .isLessThan(retrievalOrder);
    }
}
