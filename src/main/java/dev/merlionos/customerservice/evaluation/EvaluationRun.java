package dev.merlionos.customerservice.evaluation;

import java.time.Instant;

/** One pass over a tenant's golden set: {@code running}, then {@code done} with the counts, or {@code failed}. */
public record EvaluationRun(long id, String tenantId, String state, int cases, int passed, int retrievalHits, int answerPasses,
                            int toolPasses, long inputTokens, long outputTokens, String model, String note, String error,
                            String requestedBy, Instant startedAt, Instant finishedAt) {

    public boolean finished() {
        return !"running".equals(state);
    }

    public double passRatio() {
        return cases == 0 ? 0 : (double) passed / cases;
    }

    public double retrievalHitRatio() {
        return cases == 0 ? 0 : (double) retrievalHits / cases;
    }

    public double answerPassRatio() {
        return cases == 0 ? 0 : (double) answerPasses / cases;
    }

    public double toolPassRatio() {
        return cases == 0 ? 0 : (double) toolPasses / cases;
    }
}
