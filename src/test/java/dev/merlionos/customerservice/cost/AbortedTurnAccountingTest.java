package dev.merlionos.customerservice.cost;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.chat.ChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * A turn that does not finish still costs money.
 *
 * <p>Usage used to be recorded only on normal completion, so a stream that failed or was
 * cancelled after the provider had already reported its usage was billed by Anthropic and
 * invisible here — repeatedly aborted requests slipped the conversation budget and the cost
 * meters under-reported.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AbortedTurnAccountingTest {

    @Autowired ChatService chatService;
    @Autowired ConversationBudget budget;

    @MockitoBean AnthropicChatModel chatModel;

    private static ChatResponse withUsage() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("Standard shipping"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(900, 30)).build());
    }

    @Test
    @DisplayName("a failed stream still records what the provider reported")
    void recordsUsageWhenTheStreamFails() {
        given(chatModel.stream(any(Prompt.class))).willReturn(
                Flux.just(withUsage()).concatWith(Flux.error(new IllegalStateException("upstream died"))));

        String conversation = UUID.randomUUID().toString();
        chatService.stream(conversation, "How much is delivery?")
                .onErrorResume(error -> Flux.empty())
                .blockLast();

        assertThat(budget.spent(conversation))
                .as("930 tokens were consumed whether or not the stream finished")
                .isEqualTo(930);
    }

    @Test
    @DisplayName("a cancelled stream records the usage that had already arrived")
    void recordsUsageWhenTheStreamIsCancelled() {
        given(chatModel.stream(any(Prompt.class)))
                .willReturn(Flux.range(0, 50).map(chunk -> withUsage()));

        String conversation = UUID.randomUUID().toString();
        // Cancel after tokens have flowed, which is when usage exists to record. Cancelling on
        // the first event would cancel on the retrieval event, before the model had replied at
        // all -- nothing to account for, and the assertion would be about the wrong thing.
        chatService.stream(conversation, "How much is delivery?")
                .filter(event -> event instanceof dev.merlionos.customerservice.chat.TurnEvent.Token)
                .take(2)
                .blockLast();

        // Cancellation propagates asynchronously: blockLast returns as soon as the take is
        // satisfied, before doFinally has necessarily run. Poll rather than sleep a fixed
        // guess, and rather than assert on a value that is merely usually there by now.
        assertThat(spentWithin(conversation, Duration.ofSeconds(5)))
                .as("cancelled mid-answer, but the provider had already reported usage")
                .isPositive();
    }

    private long spentWithin(String conversation, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long spent;
        do {
            spent = budget.spent(conversation);
            if (spent > 0) {
                return spent;
            }
            Thread.onSpinWait();
        }
        while (System.nanoTime() < deadline);
        return spent;
    }

    @Test
    @DisplayName("usage is recorded once, not once per terminal hook")
    void recordsExactlyOnce() {
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(withUsage()));

        String conversation = UUID.randomUUID().toString();
        chatService.stream(conversation, "How much is delivery?").blockLast();

        assertThat(budget.spent(conversation))
                .as("the completion path and doFinally must not both count it")
                .isEqualTo(930);
    }
}
