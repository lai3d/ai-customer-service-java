package dev.merlionos.customerservice.chat;

import java.util.List;

/**
 * What happened during one turn, in the order it happened.
 *
 * <p>A chat endpoint that streams only tokens hides everything that makes the answer
 * trustworthy: which passages were retrieved and how well they scored, which tools ran, what
 * the turn cost. These events put that on the wire so a client can show it. The demo UI does;
 * a production widget would ignore everything except {@link Token} and {@link Failure}.
 */
public sealed interface TurnEvent {

    /** SSE event name. */
    String name();

    /** One retrieved passage and how strongly it matched. */
    record Passage(String entryId, String language, double score) {
    }

    /**
     * No duration here on purpose. Retrieval happens inside {@code QuestionAnswerAdvisor},
     * upstream of anything that could time it honestly, and a number measured from the wrong
     * place is worse than no number -- it read as 0ms. The {@code pg_vector query} span in the
     * trace carries the real timing.
     */
    record Retrieval(List<Passage> passages) implements TurnEvent {
        @Override
        public String name() {
            return "retrieval";
        }
    }

    record ToolCall(String tool, String outcome) implements TurnEvent {
        @Override
        public String name() {
            return "tool";
        }
    }

    record Token(String text) implements TurnEvent {
        @Override
        public String name() {
            return "message";
        }
    }

    /**
     * @param traceId links this turn to its span tree, so the UI can hand you a Jaeger link
     */
    record Usage(Integer inputTokens, Integer outputTokens, Long millis, String traceId)
            implements TurnEvent {
        @Override
        public String name() {
            return "usage";
        }
    }

    record Failure(String message) implements TurnEvent {
        @Override
        public String name() {
            return "error";
        }
    }
}
