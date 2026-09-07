package dev.merlionos.customerservice.evaluation;

import dev.merlionos.customerservice.rag.api.ImportMode;
import dev.merlionos.customerservice.rag.api.RagProperties;
import dev.merlionos.customerservice.tenancy.Tenant;
import dev.merlionos.customerservice.tenancy.TenancyProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two numbers a customer pays for, as gauges next to cost on the dashboard.
 *
 * <p>Deflection is sampled from what happened: over the last {@link #WINDOW}, the share of
 * a tenant's customer conversations that ended without a ticket for a person. Escalation is
 * the complement; flagged is the share a member of staff marked as wrong or incomplete.
 * Evaluation conversations never count. It is a gauge from the tables, not a counter,
 * because the denominator is conversations, which no counter sees.
 *
 * <p>Evaluation ratios are the latest finished run's, per tenant, set when a run finishes
 * and loaded at startup so a restart does not show zero. Tenant labels are bounded the way
 * the spend meters bound them; beyond the limit a tenant is {@code other}.
 */
@Component
public class QualityMetrics {

    static final Duration WINDOW = Duration.ofDays(7);
    private static final Logger log = LoggerFactory.getLogger(QualityMetrics.class);

    /** One tenant's numbers, read by the gauges. */
    static final class Numbers {
        volatile double conversations;
        volatile double deflection;
        volatile double escalation;
        volatile double flagged;
        volatile double evaluationCases;
        volatile double evaluationPass;
        volatile double evaluationRetrieval;
        volatile double evaluationAnswer;
        volatile double evaluationTool;
    }

    private final JdbcTemplate jdbc;
    private final Evaluations evaluations;
    private final MeterRegistry registry;
    private final TenancyProperties tenancy;
    private final RagProperties rag;
    private final Map<String, Numbers> tenants = new ConcurrentHashMap<>();

    public QualityMetrics(JdbcTemplate jdbc, Evaluations evaluations, MeterRegistry registry, TenancyProperties tenancy, RagProperties rag) {
        this.jdbc = jdbc;
        this.evaluations = evaluations;
        this.registry = registry;
        this.tenancy = tenancy;
        this.rag = rag;
        numbers(Tenant.DEFAULT);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (rag.importMode() == ImportMode.ONCE) {
            return; // an import job, exiting; see GoldenSeeder
        }
        sample();
    }

    /** Every minute: the window moves, and a ticket raised a moment ago changes the number. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sample() {
        try {
            for (String tenant : jdbc.queryForList("SELECT tenant_id FROM tenant ORDER BY created_at", String.class)) {
                sampleDeflection(tenant);
                evaluations.latestDone(tenant).ifPresent(this::recordEvaluation);
            }
        }
        catch (RuntimeException e) {
            log.warn("Quality metrics sample skipped: {}", e.getMessage());
        }
    }

    /** What the deflection gauge is, as a query, so the admin can show the same number. */
    public record Deflection(long conversations, long escalated, long flagged) {

        public double deflectionRate() {
            return conversations == 0 ? 0 : 1 - (double) escalated / conversations;
        }
    }

    public Deflection deflection(String tenantId, Instant since) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) AS conversations,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM support_ticket s WHERE s.conversation_id = c.id)) AS escalated,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM answer_feedback f WHERE f.conversation_id = c.id)) AS flagged
                FROM conversation c
                WHERE c.tenant_id = ? AND c.kind = 'customer'
                  AND EXISTS (SELECT 1 FROM conversation_turn t WHERE t.conversation_id = c.id AND t.started_at >= ?)
                """, tenantId, Timestamp.from(since));
        return new Deflection(((Number) row.get("conversations")).longValue(), ((Number) row.get("escalated")).longValue(),
                ((Number) row.get("flagged")).longValue());
    }

    private void sampleDeflection(String tenantId) {
        Deflection d = deflection(tenantId, Instant.now().minus(WINDOW));
        Numbers n = numbers(tenantId);
        n.conversations = d.conversations();
        n.deflection = d.deflectionRate();
        n.escalation = d.conversations() == 0 ? 0 : (double) d.escalated() / d.conversations();
        n.flagged = d.conversations() == 0 ? 0 : (double) d.flagged() / d.conversations();
    }

    void recordEvaluation(EvaluationRun run) {
        Numbers n = numbers(run.tenantId());
        n.evaluationCases = run.cases();
        n.evaluationPass = run.passRatio();
        n.evaluationRetrieval = run.retrievalHitRatio();
        n.evaluationAnswer = run.answerPassRatio();
        n.evaluationTool = run.toolPassRatio();
    }

    private Numbers numbers(String tenantId) {
        String label = tenants.containsKey(tenantId) || tenants.size() < tenancy.metricsLabelLimitOrDefault() ? tenantId : "other";
        return tenants.computeIfAbsent(label, key -> {
            Numbers n = new Numbers();
            gauge("chat_window_conversations", "Customer conversations with a turn in the last 7 days", key, n, x -> x.conversations);
            gauge("chat_deflection_rate", "Share of the window's conversations that ended without a ticket for a person", key, n, x -> x.deflection);
            gauge("chat_escalation_rate", "Share of the window's conversations in which a ticket was raised", key, n, x -> x.escalation);
            gauge("chat_flagged_rate", "Share of the window's conversations with an answer a member of staff flagged", key, n, x -> x.flagged);
            gauge("evaluation_cases", "Golden cases in the latest finished evaluation run", key, n, x -> x.evaluationCases);
            gauge("evaluation_pass_ratio", "Cases that passed every rule in the latest run", key, n, x -> x.evaluationPass);
            gauge("evaluation_retrieval_hit_ratio", "Cases whose expected entries were all retrieved", key, n, x -> x.evaluationRetrieval);
            gauge("evaluation_answer_pass_ratio", "Cases whose answer met its phrases", key, n, x -> x.evaluationAnswer);
            gauge("evaluation_tool_pass_ratio", "Cases whose expected tool ran", key, n, x -> x.evaluationTool);
            return n;
        });
    }

    private void gauge(String name, String description, String tenant, Numbers n, java.util.function.ToDoubleFunction<Numbers> value) {
        Gauge.builder(name, n, value).description(description).tag("tenant", tenant).register(registry);
    }

    /** For tests: the tenants with gauges. */
    List<String> labelled() {
        return List.copyOf(tenants.keySet());
    }
}
