package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.cost.CostProperties;
import dev.merlionos.customerservice.evaluation.EvaluationRun;
import dev.merlionos.customerservice.evaluation.Evaluations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The numbers a pilot customer is shown (BUSINESS-PLAN.md, "证明它值钱"): how often a
 * conversation was settled without a person, how often the answers were right, and what a
 * conversation cost. One tenant, one window, every number with its definition, computed
 * from the rows the record already keeps: {@code conversation_turn} for turns, tokens and
 * models, {@code support_ticket} for escalations, {@code answer_feedback} for flags, the
 * latest finished evaluation run for correctness, and the configured prices for cost.
 * Evaluation conversations are not customers' and count nowhere here.
 */
public class PilotReport {

    /** @param unpricedModels models that answered turns in the window but have no configured price; their cost is unknown, not zero */
    public record Report(String tenant, Instant from, Instant to, long conversations, long turns, long escalated, long flagged,
                         Double deflectionRate, Evaluation evaluation, long inputTokens, long outputTokens, long unmeteredTurns,
                         double costUsd, List<String> unpricedModels, Double costPerConversationUsd,
                         Map<String, String> definitions) {
    }

    public record Evaluation(long runId, Instant finishedAt, int cases, int passed, Double passRate, Double retrievalHitRate,
                             Double answerPassRate) {
    }

    private static final String CUSTOMER_ONLY = " AND conversation_id NOT IN (SELECT id FROM conversation WHERE kind = 'evaluation')";

    private final JdbcTemplate jdbc;
    private final CostProperties cost;
    private final Evaluations evaluations;

    public PilotReport(JdbcTemplate jdbc, CostProperties cost, Evaluations evaluations) {
        this.jdbc = jdbc;
        this.cost = cost;
        this.evaluations = evaluations;
    }

    public Report over(String tenantId, Instant from, Instant to) {
        Timestamp start = Timestamp.from(from);
        Timestamp end = Timestamp.from(to);
        Map<String, Object> turns = jdbc.queryForMap("""
                SELECT count(*) AS turns,
                       count(DISTINCT conversation_id) AS conversations,
                       coalesce(sum(input_tokens), 0) AS input_tokens,
                       coalesce(sum(output_tokens), 0) AS output_tokens,
                       count(*) FILTER (WHERE input_tokens IS NULL AND outcome <> 'running') AS unmetered
                FROM conversation_turn WHERE tenant_id = ? AND started_at >= ? AND started_at < ?
                """ + CUSTOMER_ONLY, tenantId, start, end);
        List<Map<String, Object>> byModel = jdbc.queryForList("""
                SELECT model, coalesce(sum(input_tokens), 0) AS input_tokens, coalesce(sum(output_tokens), 0) AS output_tokens
                FROM conversation_turn WHERE tenant_id = ? AND started_at >= ? AND started_at < ? AND model IS NOT NULL
                """ + CUSTOMER_ONLY + " GROUP BY model", tenantId, start, end);
        double usd = 0;
        List<String> unpriced = new ArrayList<>();
        for (Map<String, Object> row : byModel) {
            String model = (String) row.get("model");
            CostProperties.ModelPrice price = cost.prices() == null ? null : cost.prices().get(model);
            if (price == null) {
                unpriced.add(model);
                continue;
            }
            usd += number(row, "input_tokens") * price.inputPerMillionUsd() / 1_000_000
                    + number(row, "output_tokens") * price.outputPerMillionUsd() / 1_000_000;
        }
        // Escalations and flags among the window's conversations, the way the deflection rate counts them.
        Map<String, Object> outcome = jdbc.queryForMap("""
                SELECT count(*) AS conversations,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM support_ticket s WHERE s.conversation_id = c.id)) AS escalated,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM answer_feedback f WHERE f.conversation_id = c.id)) AS flagged
                FROM conversation c
                WHERE c.tenant_id = ? AND c.kind = 'customer'
                  AND EXISTS (SELECT 1 FROM conversation_turn t WHERE t.conversation_id = c.id AND t.started_at >= ? AND t.started_at < ?)
                """, tenantId, start, end);
        long conversations = number(outcome, "conversations");
        long escalated = number(outcome, "escalated");
        Evaluation evaluation = evaluations.latestDone(tenantId).map(PilotReport::evaluation).orElse(null);
        double rounded = Math.round(usd * 10000) / 10000.0;
        return new Report(tenantId, from, to, conversations, number(turns, "turns"), escalated, number(outcome, "flagged"),
                conversations == 0 ? null : round(1 - (double) escalated / conversations), evaluation,
                number(turns, "input_tokens"), number(turns, "output_tokens"), number(turns, "unmetered"),
                rounded, unpriced, conversations == 0 ? null : Math.round(usd / conversations * 10000) / 10000.0, definitions());
    }

    private static Evaluation evaluation(EvaluationRun run) {
        return new Evaluation(run.id(), run.finishedAt(), run.cases(), run.passed(),
                run.cases() == 0 ? null : round((double) run.passed() / run.cases()),
                run.cases() == 0 ? null : round((double) run.retrievalHits() / run.cases()),
                run.cases() == 0 ? null : round((double) run.answerPasses() / run.cases()));
    }

    private static Map<String, String> definitions() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("conversations", "Customer conversations with at least one turn started in the window. Evaluation runs are not customers and are not counted.");
        d.put("turns", "Turns started in the window, on either chat path, whatever their outcome.");
        d.put("escalated", "Of those conversations, the ones in which a support ticket was raised for a person, at any time.");
        d.put("flagged", "Of those conversations, the ones in which staff flagged an answer.");
        d.put("deflectionRate", "Conversations settled without a ticket, over all conversations in the window: 1 minus escalated over conversations. Empty when there were none.");
        d.put("evaluation", "The latest finished evaluation run for this tenant, whenever it ran: its golden cases asked through the real chat path and scored on facts, not prose. Pass rate is cases passing every check; retrieval and answer rates are the two checks apart.");
        d.put("inputTokens", "Input tokens the provider reported for the window's turns. Turns without usage count as zero here and are counted below.");
        d.put("outputTokens", "Output tokens the provider reported.");
        d.put("unmeteredTurns", "Ended turns for which the provider reported no usage, typically interrupted or failed before the final chunk. Their cost is unknown, not zero.");
        d.put("costUsd", "Tokens priced per model at the configured dollars per million (app.cost.prices), summed over the window. A model without a price is listed and its cost is unknown, not zero.");
        d.put("costPerConversationUsd", "Cost over conversations. What one settled or escalated conversation cost in model spend; staff time and the service itself are not in it.");
        return d;
    }

    private static long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static Double round(double ratio) {
        return Math.round(ratio * 10000) / 10000.0;
    }
}
