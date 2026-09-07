package dev.merlionos.customerservice.evaluation;

/** A rule the evaluation would not bend: an empty golden set, a run already going, a case that makes no sense. */
public class EvaluationRuleException extends RuntimeException {

    public EvaluationRuleException(String message) {
        super(message);
    }
}
