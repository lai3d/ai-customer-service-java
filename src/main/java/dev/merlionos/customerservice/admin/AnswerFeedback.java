package dev.merlionos.customerservice.admin;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code answer_feedback} table: a flag on one recorded turn, and its handling. The
 * same shape as a ticket change, in miniature: lock the row, compare the version the caller
 * read, decide, write. A stale version is a {@link Conflict}; a rule the store will not bend
 * is a {@link Rule}; the page tells them apart the same way it does for tickets.
 */
public class AnswerFeedback {

    public static final Set<String> ISSUES = Set.of("incorrect", "incomplete", "unhelpful", "other");
    public static final Set<String> STATES = Set.of("open", "handled", "dismissed");
    public static final int MAX_SIZE = 100;

    /** @param revisionId the knowledge revision that fixed it, when handling named one */
    public record Report(long id, String turnId, String conversationId, String issue, String note, String state,
                         String conclusion, String reportedBy, Instant reportedAt, String handledBy,
                         Instant handledAt, int version, Long revisionId) {
    }

    public record Page(List<Report> reports, long total, int page, int size) {
    }

    /** The flag changed under the caller. Reload. */
    public static class Conflict extends RuntimeException {
        Conflict(long id, String what) {
            super("Feedback " + id + " " + what);
        }
    }

    /** The store will not do this; reloading will not help. */
    public static class Rule extends RuntimeException {
        Rule(String what) {
            super(what);
        }
    }

    public static class NotFound extends RuntimeException {
        NotFound(long id) {
            super("No feedback " + id);
        }
    }

    private static final RowMapper<Report> REPORT = (rs, i) -> new Report(rs.getLong("id"), rs.getString("turn_id"),
            rs.getString("conversation_id"), rs.getString("issue"), rs.getString("note"), rs.getString("state"),
            rs.getString("conclusion"), rs.getString("reported_by"), rs.getTimestamp("reported_at").toInstant(),
            rs.getString("handled_by"), rs.getTimestamp("handled_at") == null ? null : rs.getTimestamp("handled_at").toInstant(),
            rs.getInt("version"), rs.getObject("revision_id", Long.class));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public AnswerFeedback(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Flags a turn. The turn must exist in the record; the issue must be one of {@link #ISSUES}. */
    public Report report(String turnId, String issue, String note, String reporter) {
        String kind = issue == null ? "" : issue.strip().toLowerCase(Locale.ROOT);
        if (!ISSUES.contains(kind)) {
            throw new Rule("issue must be one of " + ISSUES);
        }
        String conversation = jdbc.query("SELECT conversation_id FROM conversation_turn WHERE turn_id = ?",
                (rs, i) -> rs.getString(1), turnId).stream().findFirst()
                .orElseThrow(() -> new Rule("no recorded turn " + turnId + " to flag"));
        String text = note == null || note.isBlank() ? null : note.strip();
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO answer_feedback (turn_id, conversation_id, issue, note, reported_by, reported_at)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, turnId, conversation, kind, text, reporter, Timestamp.from(Instant.now()));
            return find(id).orElseThrow();
        }
        catch (DataIntegrityViolationException e) {
            throw new Rule("no recorded turn " + turnId + " to flag");
        }
    }

    public Optional<Report> find(long id) {
        return jdbc.query("SELECT * FROM answer_feedback WHERE id = ?", REPORT, id).stream().findFirst();
    }

    public List<Report> forConversation(String conversationId) {
        return jdbc.query("SELECT * FROM answer_feedback WHERE conversation_id = ? ORDER BY id", REPORT, conversationId);
    }

    /** Newest first; {@code state} null means every state. */
    public Page list(String state, int page, int size) {
        String filter = state == null || state.isBlank() ? null : state.strip().toLowerCase(Locale.ROOT);
        if (filter != null && !STATES.contains(filter)) {
            throw new IllegalArgumentException("state must be one of " + STATES);
        }
        int p = Math.max(page, 0);
        int s = size < 1 ? 25 : Math.min(size, MAX_SIZE);
        List<Object> args = new ArrayList<>();
        String where = "";
        if (filter != null) {
            where = " WHERE state = ?";
            args.add(filter);
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM answer_feedback" + where, Long.class, args.toArray());
        args.add(s);
        args.add((long) p * s);
        List<Report> reports = jdbc.query("SELECT * FROM answer_feedback" + where + " ORDER BY reported_at DESC, id DESC LIMIT ? OFFSET ?",
                REPORT, args.toArray());
        return new Page(reports, total, p, s);
    }

    /**
     * Closes a flag as {@code handled} (with a conclusion, required) or {@code dismissed}
     * (conclusion optional). Only an open flag can be closed; closing is final.
     */
    public Report handle(long id, String state, String conclusion, String actor, int expectedVersion) {
        return handle(id, state, conclusion, null, actor, expectedVersion);
    }

    /** @param revisionId the knowledge revision that fixed the answer, or null */
    public Report handle(long id, String state, String conclusion, Long revisionId, String actor, int expectedVersion) {
        String target = state == null ? "" : state.strip().toLowerCase(Locale.ROOT);
        if (!target.equals("handled") && !target.equals("dismissed")) {
            throw new Rule("a flag is closed as handled or dismissed");
        }
        String text = conclusion == null || conclusion.isBlank() ? null : conclusion.strip();
        if (target.equals("handled") && text == null) {
            throw new Rule("handling a flag needs a conclusion: what was done, or why nothing was");
        }
        return transaction.execute(status -> {
            Report current = jdbc.query("SELECT * FROM answer_feedback WHERE id = ? FOR UPDATE", REPORT, id)
                    .stream().findFirst().orElseThrow(() -> new NotFound(id));
            if (current.version() != expectedVersion) {
                throw new Conflict(id, "changed since it was read (version " + current.version() + ", not "
                        + expectedVersion + "); reload and look again");
            }
            if (!current.state().equals("open")) {
                throw new Rule("Feedback " + id + " is already " + current.state());
            }
            if (revisionId != null && jdbc.queryForObject("SELECT count(*) FROM knowledge_revision WHERE id = ?", Integer.class, revisionId) == 0) {
                throw new Rule("no knowledge revision " + revisionId + " to link");
            }
            jdbc.update("""
                    UPDATE answer_feedback SET state = ?, conclusion = ?, handled_by = ?, handled_at = ?, revision_id = ?,
                        version = version + 1
                    WHERE id = ?
                    """, target, text, actor, Timestamp.from(Instant.now()), revisionId, id);
            return find(id).orElseThrow();
        });
    }
}
