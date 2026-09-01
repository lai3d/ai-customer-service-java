package dev.merlionos.customerservice.chat;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.tools.SupportTicketTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Guards an implicit contract that fails loudly and late.
 *
 * <p>{@code SupportTicketTools.createSupportTicket} takes a {@code ToolContext} parameter, and
 * Spring AI rejects a call to such a tool when the context is missing or empty -- before the
 * tool body runs. Any code path that reaches the model without setting the tool context
 * therefore breaks ticket creation, and it breaks it only once a customer's conversation has
 * escalated far enough for the model to try. Both entry points are checked here instead.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class ChatServiceToolContextTest {

    private static final String CONVERSATION_ID = "conversation-42";

    @Autowired ChatService chatService;

    @MockitoBean AnthropicChatModel chatModel;

    @BeforeEach
    void stubModel() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        given(chatModel.call(any(Prompt.class))).willReturn(response);
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(response));
    }

    @Test
    @DisplayName("the blocking path carries the conversation id into the tool context")
    void askSuppliesToolContext() {
        chatService.ask(CONVERSATION_ID, "Where is my order?");

        assertThat(capturedToolContext()).containsEntry(
                SupportTicketTools.CONVERSATION_ID_KEY, CONVERSATION_ID);
    }

    @Test
    @DisplayName("the streaming path carries it too")
    void streamSuppliesToolContext() {
        chatService.stream(CONVERSATION_ID, "Where is my order?").blockLast();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompt.capture());

        assertThat(toolContextOf(prompt.getValue())).containsEntry(
                SupportTicketTools.CONVERSATION_ID_KEY, CONVERSATION_ID);
    }

    private java.util.Map<String, Object> capturedToolContext() {
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        return toolContextOf(prompt.getValue());
    }

    private static java.util.Map<String, Object> toolContextOf(Prompt prompt) {
        assertThat(prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        return ((ToolCallingChatOptions) prompt.getOptions()).getToolContext();
    }
}
