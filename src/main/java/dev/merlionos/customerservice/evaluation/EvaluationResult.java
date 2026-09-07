package dev.merlionos.customerservice.evaluation;

import java.util.List;

/** One case's answer and how it scored; {@code failures} says which rule it broke. */
public record EvaluationResult(long runId, long caseId, String question, String conversationId, List<String> retrieved,
                               List<String> tools, String answer, boolean retrievalHit, boolean answerPass, boolean toolPass,
                               boolean passed, String failures, Integer inputTokens, Integer outputTokens, Long millis) {
}
