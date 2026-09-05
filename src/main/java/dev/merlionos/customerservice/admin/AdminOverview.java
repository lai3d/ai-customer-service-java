package dev.merlionos.customerservice.admin;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The operational overview: counts and rates computed from the records the admin already
 * shows, over a window, each with its definition next to its value. Nothing here is
 * measured by anything but the tables, and nothing is shown that no table can define:
 * satisfaction, issue resolution and human takeover rates wait for the events that would
 * mean them. Cost is not here either; it is an estimate the meters carry, by model, and the
 * proposal says to label it as one rather than sum it into a number that looks settled.
 */
public class AdminOverview {

    /** One number and what it means. */
    public record Stat(String key, String label, Number value, String definition) {
    }

    public record Overview(Instant from, Instant to, List<Stat> turns, List<Stat> tickets, List<Stat> feedback,
                           List<Stat> knowledge, List<Stat> staff) {
    }

    private final JdbcTemplate jdbc;

    public AdminOverview(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Overview over(Instant from, Instant to) {
        Timestamp start = Timestamp.from(from);
        Timestamp end = Timestamp.from(to);
        return new Overview(from, to, turns(start, end), tickets(start, end), feedback(start, end), knowledge(), staff(start, end));
    }

    private List<Stat> turns(Timestamp from, Timestamp to) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) AS turns,
                       count(DISTINCT conversation_id) AS conversations,
                       count(*) FILTER (WHERE outcome = 'completed') AS completed,
                       count(*) FILTER (WHERE outcome = 'failed') AS failed,
                       count(*) FILTER (WHERE outcome = 'interrupted') AS interrupted,
                       count(*) FILTER (WHERE outcome = 'unknown') AS unknown,
                       count(*) FILTER (WHERE outcome = 'running') AS running,
                       coalesce(sum(input_tokens), 0) AS input_tokens,
                       coalesce(sum(output_tokens), 0) AS output_tokens,
                       count(*) FILTER (WHERE input_tokens IS NULL AND outcome <> 'running') AS unmetered,
                       avg(extract(epoch FROM (ended_at - started_at)) * 1000) FILTER (WHERE outcome = 'completed') AS avg_ms
                FROM conversation_turn WHERE started_at >= ? AND started_at < ?
                """, from, to);
        long turns = number(row, "turns");
        long ended = turns - number(row, "running");
        return List.of(
                stat("turns", "Turns", turns, "Turns started in the window, on either chat path, whatever their outcome."),
                stat("conversations", "Conversations", number(row, "conversations"), "Distinct conversations with at least one turn started in the window."),
                stat("completed", "Completed", number(row, "completed"), "Turns whose model call finished and whose answer reached the customer."),
                stat("failed", "Failed", number(row, "failed"), "Turns whose model call failed; the reason is on the turn."),
                stat("interrupted", "Interrupted", number(row, "interrupted"), "Turns the customer stopped listening to before the answer ended."),
                stat("unknown", "Unknown", number(row, "unknown"), "Turns a process died holding; nothing knows how they ended."),
                stat("failureRate", "Failure rate", rate(number(row, "failed"), ended), "Failed turns over ended turns (completed, failed, interrupted, unknown), as a percentage. Interruptions are the customer's choice and are not failures."),
                stat("avgMs", "Average completed turn", round(row.get("avg_ms")), "Mean wall time of completed turns in milliseconds, from the first row to the last signal."),
                stat("inputTokens", "Input tokens", number(row, "input_tokens"), "Sum of input tokens the provider reported for turns in the window. Missing usage counts as zero here and is counted separately below."),
                stat("outputTokens", "Output tokens", number(row, "output_tokens"), "Sum of output tokens the provider reported."),
                stat("unmetered", "Turns without usage", number(row, "unmetered"), "Ended turns for which the provider reported no usage, typically interrupted or failed before the final chunk. Their cost is unknown, not zero."));
    }

    private List<Stat> tickets(Timestamp from, Timestamp to) {
        Map<String, Object> states = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE state = 'open') AS open,
                       count(*) FILTER (WHERE state = 'claimed') AS claimed,
                       count(*) FILTER (WHERE state = 'resolved') AS resolved,
                       count(*) FILTER (WHERE state = 'closed') AS closed,
                       count(*) FILTER (WHERE created_at >= ? AND created_at < ?) AS created
                FROM support_ticket
                """, from, to);
        Map<String, Object> times = jdbc.queryForMap("""
                SELECT avg(extract(epoch FROM (c.occurred_at - t.created_at)) / 60) AS minutes_to_claim,
                       count(*) AS claimed_in_window
                FROM ticket_event c JOIN support_ticket t ON t.ticket_number = c.ticket_number
                WHERE c.kind IN ('claimed', 'assigned') AND c.occurred_at >= ? AND c.occurred_at < ?
                  AND c.id = (SELECT min(id) FROM ticket_event f WHERE f.ticket_number = c.ticket_number AND f.kind IN ('claimed', 'assigned'))
                """, from, to);
        Map<String, Object> resolved = jdbc.queryForMap("""
                SELECT avg(extract(epoch FROM (r.occurred_at - t.created_at)) / 60) AS minutes_to_resolve,
                       count(*) AS resolved_in_window
                FROM ticket_event r JOIN support_ticket t ON t.ticket_number = r.ticket_number
                WHERE r.kind = 'resolved' AND r.occurred_at >= ? AND r.occurred_at < ?
                """, from, to);
        return List.of(
                stat("open", "Open", number(states, "open"), "Tickets nobody has claimed, right now."),
                stat("claimed", "Claimed", number(states, "claimed"), "Tickets someone is working, right now."),
                stat("resolved", "Resolved", number(states, "resolved"), "Tickets resolved and not yet closed, right now."),
                stat("closed", "Closed", number(states, "closed"), "Tickets closed, all time."),
                stat("created", "Created", number(states, "created"), "Tickets the assistant raised in the window."),
                stat("claimedInWindow", "Claimed in window", number(times, "claimed_in_window"), "Tickets first claimed or assigned in the window."),
                stat("minutesToClaim", "Minutes to first claim", round(times.get("minutes_to_claim")), "Mean minutes from creation to the first claim or assignment, over tickets first claimed in the window."),
                stat("resolvedInWindow", "Resolved in window", number(resolved, "resolved_in_window"), "Resolutions recorded in the window."),
                stat("minutesToResolve", "Minutes to resolve", round(resolved.get("minutes_to_resolve")), "Mean minutes from creation to resolution, over resolutions recorded in the window."));
    }

    private List<Stat> feedback(Timestamp from, Timestamp to) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE state = 'open') AS open,
                       count(*) FILTER (WHERE reported_at >= ? AND reported_at < ?) AS reported,
                       count(*) FILTER (WHERE handled_at >= ? AND handled_at < ? AND state = 'handled') AS handled,
                       count(*) FILTER (WHERE handled_at >= ? AND handled_at < ? AND state = 'dismissed') AS dismissed
                FROM answer_feedback
                """, from, to, from, to, from, to);
        return List.of(
                stat("openFlags", "Open flags", number(row, "open"), "Answer flags nobody has handled, right now."),
                stat("reported", "Flagged in window", number(row, "reported"), "Answers flagged in the window."),
                stat("handled", "Handled in window", number(row, "handled"), "Flags closed with a conclusion in the window. Handled means the report was dealt with, not that the customer's problem was solved."),
                stat("dismissed", "Dismissed in window", number(row, "dismissed"), "Flags closed as needing no change."));
    }

    private List<Stat> knowledge() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT (SELECT version FROM knowledge_active WHERE id = 1) AS active,
                       (SELECT document_count FROM knowledge_version WHERE state = 'active') AS documents,
                       (SELECT count(*) FROM knowledge_entry WHERE NOT retired) AS entries,
                       (SELECT count(*) FROM knowledge_revision WHERE state = 'draft') AS drafts,
                       (SELECT count(*) FROM knowledge_version WHERE state = 'ready') AS retained,
                       (SELECT count(*) FROM knowledge_version WHERE state = 'failed') AS failed
                """);
        return List.of(
                new Stat("activeVersion", "Active version", null, "The knowledge version retrieval reads: " + row.get("active") + "."),
                stat("documents", "Documents", number(row, "documents"), "Documents in the active version, every language counted."),
                stat("entries", "Entries", number(row, "entries"), "Managed entries not retired."),
                stat("drafts", "Drafts", number(row, "drafts"), "Drafts waiting for a publication; none of them is live."),
                stat("retained", "Retained versions", number(row, "retained"), "Versions kept for rollback besides the active one."),
                stat("failedBuilds", "Failed publications", number(row, "failed"), "Publications whose build did not complete; the previous version kept serving each time."));
    }

    private List<Stat> staff(Timestamp from, Timestamp to) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE action = 'viewed_conversation') AS views,
                       count(*) FILTER (WHERE action = 'refused') AS refused,
                       count(DISTINCT actor) AS actors
                FROM admin_audit WHERE occurred_at >= ? AND occurred_at < ?
                """, from, to);
        return List.of(
                stat("actors", "Staff active", number(row, "actors"), "Distinct accounts that opened a conversation or were refused something in the window."),
                stat("views", "Conversations opened", number(row, "views"), "Times a customer conversation was opened in the admin in the window; every one is recorded."),
                stat("refused", "Refused actions", number(row, "refused"), "Actions the server refused in the window, by rule or by role; every one is recorded."));
    }

    private static Stat stat(String key, String label, Number value, String definition) {
        return new Stat(key, label, value, definition);
    }

    private static long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static Number rate(long part, long whole) {
        return whole == 0 ? null : Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static Number round(Object value) {
        return value == null ? null : Math.round(((Number) value).doubleValue());
    }

    /** The default window: the last day. */
    public static Instant[] defaultWindow() {
        Instant now = Instant.now();
        return new Instant[] {now.minus(Duration.ofDays(1)), now};
    }

    public static Map<String, String> definitions(Overview overview) {
        Map<String, String> map = new LinkedHashMap<>();
        for (List<Stat> group : List.of(overview.turns(), overview.tickets(), overview.feedback(), overview.knowledge(), overview.staff())) {
            for (Stat stat : group) {
                map.put(stat.key(), stat.definition());
            }
        }
        return map;
    }
}
