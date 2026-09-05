package dev.merlionos.customerservice.chat;

import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * The lease, end to end through {@link ChatService}: while one turn is streaming, the same
 * conversation refuses a second on either endpoint, and admits it once the first is done.
 * A different conversation is admitted throughout.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class OverlappingTurnTest {

    @Autowired ChatService chatService;
    @MockitoBean AnthropicChatModel chatModel;

    @Test
    @DisplayName("a second turn is refused while the first is in flight, and admitted after it")
    void refusesWhileInFlightAdmitsAfter() throws InterruptedException {
        ChatResponse reply = new ChatResponse(List.of(new Generation(new AssistantMessage("Slowly."))));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstFinished = new CountDownLatch(1);
        given(chatModel.stream(any(Prompt.class))).willReturn(
                Flux.just(reply).delayElements(Duration.ofSeconds(2)).doOnSubscribe(s -> firstStarted.countDown()));
        given(chatModel.call(any(Prompt.class))).willReturn(reply);

        chatService.stream("shared-conversation", "first")
                .doFinally(signal -> firstFinished.countDown())
                .subscribe();
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> chatService.ask("shared-conversation", "second"))
                .isInstanceOf(ConversationBusyException.class);
        assertThatThrownBy(() -> chatService.stream("shared-conversation", "second"))
                .as("refused before a stream is built, so the client sees a status, not an error event")
                .isInstanceOf(ConversationBusyException.class);
        assertThatCode(() -> chatService.ask("another-conversation", "unrelated")).doesNotThrowAnyException();

        assertThat(firstFinished.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatCode(() -> chatService.ask("shared-conversation", "second, later")).doesNotThrowAnyException();
    }
}
