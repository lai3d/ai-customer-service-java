package dev.merlionos.customerservice.chat;

import org.springframework.ai.chat.metadata.Usage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds up what one turn actually cost, across every model call it made.
 *
 * <p>A turn is not a model call. A tool-calling turn is at least two — one where the model asks
 * for the tool, one where it answers with the result — and each is billed separately. The
 * arithmetic looks trivial and every simple rule for it is wrong, which is why this is derived
 * from observed frames rather than from what the providers ought to do.
 *
 * <p>What the wire actually carries, measured:
 *
 * <pre>
 * xAI        in=1800 out=18   x104     in=3696 out=108   in=1800 out=18
 * Anthropic  in=1923 out=25 → out=60   in=4028 out=61 → out=275
 * </pre>
 *
 * <p>Keeping the last frame under-reports: on xAI the frame arriving last is the *first* call's,
 * so a turn costing 5,496 input tokens was recorded as 1,800. Summing every frame over-reports
 * by a hundredfold, because the OpenAI-compatible path repeats one cumulative usage on every
 * chunk. Summing the *distinct* frames is also wrong — that was the second attempt — because
 * Anthropic grows the output count as it streams, so one call contributes several distinct
 * frames and its input is counted once per frame: 11,902 for a turn that spent 5,951.
 *
 * <p>The rule that fits all of it comes from what each number means. Input tokens are fixed for
 * a call: the prompt does not change while the answer streams. Output tokens only grow. So
 * frames are grouped by their input count — one group per model call — and each group
 * contributes its input once and its largest output.
 *
 * <p>Two calls in one turn whose prompts tokenise to exactly the same length would merge into
 * one group and be under-counted. In a tool round trip the second prompt carries the tool
 * result and is reliably longer, so this needs a coincidence; it errs towards under-reporting
 * rather than over-charging, which is the right direction for a number that gates spending.
 */
final class TurnUsage {

    /** input tokens (stable within a model call) -> largest output seen for that call */
    private final Map<Integer, Integer> outputByInput = new LinkedHashMap<>();

    synchronized void record(Usage usage) {
        if (usage == null) {
            return;
        }
        int input = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int output = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        if (input <= 0 && output <= 0) {
            return;
        }
        outputByInput.merge(input, output, Math::max);
    }

    synchronized boolean isEmpty() {
        return outputByInput.isEmpty();
    }

    synchronized int inputTokens() {
        return outputByInput.keySet().stream().mapToInt(Integer::intValue).sum();
    }

    synchronized int outputTokens() {
        return outputByInput.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** How many model calls this turn made, as far as the usage frames can tell. */
    synchronized int modelCalls() {
        return outputByInput.size();
    }
}
