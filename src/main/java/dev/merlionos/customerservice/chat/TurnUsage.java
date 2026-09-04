package dev.merlionos.customerservice.chat;

import org.springframework.ai.chat.metadata.Usage;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Adds up what one turn actually cost, across every model call it made.
 *
 * <p>A turn is not one model call. A tool-calling turn is at least two: one where the model
 * asks for the tool, one where it answers with the result — and each is billed. Keeping the
 * last usage seen therefore under-reported every tool-calling turn on every provider. On xAI it
 * was conspicuous, because the frame that arrived last was the *first* call's, so a turn that
 * spent 5,496 input tokens was recorded as 1,800.
 *
 * <p>Summing every frame is equally wrong. Providers on the OpenAI-compatible path attach the
 * same cumulative usage to every streamed chunk: one measured turn carried 124 identical
 * frames. Adding them would have inflated the same turn a hundredfold.
 *
 * <p>So frames are de-duplicated by their values and then summed. The response id is not
 * usable as the key — xAI reuses one id across both calls of a tool round trip, which was
 * checked rather than assumed. The assumption this does rest on is that a provider reports
 * usage cumulatively for a call rather than incrementally per chunk; an incremental provider
 * would be under-counted, and its arrival would show up as a turn whose reported cost is far
 * below the invoice.
 *
 * <p>Two calls with byte-identical usage collapse into one. That is possible, rare, and errs
 * towards under-reporting rather than over-charging.
 */
final class TurnUsage {

    private record Frame(int inputTokens, int outputTokens) {
    }

    private final Set<Frame> frames = new LinkedHashSet<>();

    synchronized void record(Usage usage) {
        if (usage == null) {
            return;
        }
        int input = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int output = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        if (input > 0 || output > 0) {
            frames.add(new Frame(input, output));
        }
    }

    synchronized boolean isEmpty() {
        return frames.isEmpty();
    }

    synchronized int inputTokens() {
        return frames.stream().mapToInt(Frame::inputTokens).sum();
    }

    synchronized int outputTokens() {
        return frames.stream().mapToInt(Frame::outputTokens).sum();
    }

    /** How many model calls this turn made, as far as the usage frames can tell. */
    synchronized int modelCalls() {
        return frames.size();
    }
}
