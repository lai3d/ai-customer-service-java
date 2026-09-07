package dev.merlionos.customerservice.evaluation;

import dev.merlionos.customerservice.chat.ChatService;
import dev.merlionos.customerservice.chat.TurnEvent;
import dev.merlionos.customerservice.tenancy.Conversations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Runs a tenant's golden set through the real chat path -- the advisor chain, retrieval,
 * the tools, the model -- one fresh conversation per case, and scores each answer with
 * {@link Scoring}. Off the request thread, one run per tenant at a time, polled like a
 * publication or an import. Every case is a real model call and costs what a customer turn
 * costs; the run records the tokens so the price of an evaluation is a number, not a guess.
 *
 * <p>The conversations it creates are marked {@code evaluation} so nothing counts them as
 * customers: not deflection, not the admin's overview, not the records staff read.
 */
@Component
public class Evaluator {

    private static final Logger log = LoggerFactory.getLogger(Evaluator.class);
    static final Duration TURN_TIMEOUT = Duration.ofMinutes(3);

    private final GoldenCases cases;
    private final Evaluations evaluations;
    private final Conversations conversations;
    private final ChatService chat;
    private final QualityMetrics metrics;

    public Evaluator(GoldenCases cases, Evaluations evaluations, Conversations conversations, ChatService chat, QualityMetrics metrics) {
        this.cases = cases;
        this.evaluations = evaluations;
        this.conversations = conversations;
        this.chat = chat;
        this.metrics = metrics;
    }

    /** Starts a run and returns it in {@code running}; poll {@link Evaluations#find}. */
    public EvaluationRun start(String tenantId, String actor, String note) {
        if (evaluations.running(tenantId).isPresent()) {
            throw new EvaluationRuleException("an evaluation is already running for this tenant; wait for it to finish");
        }
        List<GoldenCase> golden = cases.enabled(tenantId);
        if (golden.isEmpty()) {
            throw new EvaluationRuleException("the golden set has no enabled case; add one before running an evaluation");
        }
        EvaluationRun run = evaluations.start(tenantId, golden.size(), actor, note);
        Thread.ofVirtual().name("evaluation-" + run.id()).start(() -> run(run, golden));
        return run;
    }

    /** The same, on the calling thread, for the opt-in test and for scripts. */
    public EvaluationRun runNow(String tenantId, String actor, String note) {
        if (evaluations.running(tenantId).isPresent()) {
            throw new EvaluationRuleException("an evaluation is already running for this tenant; wait for it to finish");
        }
        List<GoldenCase> golden = cases.enabled(tenantId);
        if (golden.isEmpty()) {
            throw new EvaluationRuleException("the golden set has no enabled case; add one before running an evaluation");
        }
        EvaluationRun run = evaluations.start(tenantId, golden.size(), actor, note);
        run(run, golden);
        return evaluations.find(tenantId, run.id()).orElseThrow();
    }

    private void run(EvaluationRun run, List<GoldenCase> golden) {
        int passed = 0;
        int retrieval = 0;
        int answers = 0;
        int tools = 0;
        long in = 0;
        long out = 0;
        String model = null;
        try {
            for (GoldenCase golden1 : golden) {
                String conversation = conversations.createEvaluation(run.tenantId(), "eval-" + run.id() + "-" + golden1.id());
                Scoring.Turn turn = ask(run.tenantId(), conversation, golden1.question());
                Scoring.Score score = Scoring.score(golden1, turn);
                evaluations.record(new EvaluationResult(run.id(), golden1.id(), golden1.question(), conversation, turn.retrieved(),
                        turn.tools(), turn.answer(), score.retrievalHit(), score.answerPass(), score.toolPass(), score.passed(),
                        score.failures().isEmpty() ? null : String.join("; ", score.failures()), turn.inputTokens(),
                        turn.outputTokens(), turn.millis()));
                passed += score.passed() ? 1 : 0;
                retrieval += score.retrievalHit() ? 1 : 0;
                answers += score.answerPass() ? 1 : 0;
                tools += score.toolPass() ? 1 : 0;
                in += turn.inputTokens() == null ? 0 : turn.inputTokens();
                out += turn.outputTokens() == null ? 0 : turn.outputTokens();
                if (model == null) {
                    model = modelOf(run.tenantId(), conversation).orElse(null);
                }
            }
            evaluations.finish(run.id(), passed, retrieval, answers, tools, in, out, model);
            evaluations.find(run.tenantId(), run.id()).ifPresent(metrics::recordEvaluation);
            log.info("Evaluation {} for tenant {}: {}/{} passed (retrieval {}, answers {}, tools {}), {} in / {} out tokens",
                    run.id(), run.tenantId(), passed, golden.size(), retrieval, answers, tools, in, out);
        }
        catch (RuntimeException e) {
            log.error("Evaluation {} for tenant {} failed", run.id(), run.tenantId(), e);
            evaluations.fail(run.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** One turn, as a customer would have it, with a failed model call scored rather than thrown. */
    private Scoring.Turn ask(String tenantId, String conversation, String question) {
        try {
            List<TurnEvent> events = chat.stream(tenantId, conversation, question)
                    .onErrorResume(e -> reactor.core.publisher.Flux.just(new TurnEvent.Failure(String.valueOf(e.getMessage()))))
                    .collectList().block(TURN_TIMEOUT);
            return Scoring.Turn.of(events == null ? List.of() : events);
        }
        catch (RuntimeException e) {
            return Scoring.Turn.of(List.of(new TurnEvent.Failure(String.valueOf(e.getMessage()))));
        }
    }

    private Optional<String> modelOf(String tenantId, String conversation) {
        return evaluations.modelOf(conversation);
    }
}
