package dev.merlionos.customerservice.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token accounting for a turn, which is not the same as for a model call.
 *
 * <p>Both of the obvious implementations are wrong, and both were measured against a live
 * provider before this existed. Keeping the last frame under-reports a tool-calling turn: the
 * frame that arrived last on xAI was the *first* call's, so a turn that spent 5,496 input
 * tokens was recorded as 1,800. Summing every frame over-reports enormously: the same turn
 * carried 124 identical frames, because the OpenAI-compatible path repeats cumulative usage on
 * every streamed chunk.
 */
class TurnUsageTest {

    @Test
    @DisplayName("repeated frames from one model call count once")
    void repeatedFramesCountOnce() {
        TurnUsage usage = new TurnUsage();

        for (int chunk = 0; chunk < 124; chunk++) {
            usage.record(new DefaultUsage(1800, 18));
        }

        assertThat(usage.inputTokens()).isEqualTo(1800);
        assertThat(usage.outputTokens()).isEqualTo(18);
        assertThat(usage.modelCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("output growing mid-stream is one call, not several")
    void growingOutputWithinACallIsNotCountedTwice() {
        TurnUsage usage = new TurnUsage();

        // Anthropic reports output tokens as they accumulate. Summing distinct frames counted
        // this call's input once per frame: a turn that spent 5,951 was recorded as 11,902.
        usage.record(new DefaultUsage(1923, 25));
        usage.record(new DefaultUsage(1923, 60));
        usage.record(new DefaultUsage(4028, 61));
        usage.record(new DefaultUsage(4028, 275));

        assertThat(usage.inputTokens()).isEqualTo(5951);
        assertThat(usage.outputTokens()).isEqualTo(335);
        assertThat(usage.modelCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("a tool-calling turn bills for both model calls")
    void distinctCallsAreSummed() {
        TurnUsage usage = new TurnUsage();

        // The call that asks for the tool, repeated across chunks...
        for (int chunk = 0; chunk < 100; chunk++) {
            usage.record(new DefaultUsage(1800, 18));
        }
        // ...then the call that answers with the result.
        for (int chunk = 0; chunk < 24; chunk++) {
            usage.record(new DefaultUsage(3696, 108));
        }
        // xAI delivers the first call's frame again at the very end, which is what made
        // "keep the last one" report 1800 for a turn that spent 5496.
        usage.record(new DefaultUsage(1800, 18));

        assertThat(usage.inputTokens()).isEqualTo(5496);
        assertThat(usage.outputTokens()).isEqualTo(126);
        assertThat(usage.modelCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("a turn with no usage reported is empty, not zero-cost fiction")
    void noFramesIsEmpty() {
        TurnUsage usage = new TurnUsage();

        usage.record(null);
        usage.record(new DefaultUsage(0, 0));

        assertThat(usage.isEmpty()).isTrue();
        assertThat(usage.modelCalls()).isZero();
    }
}
