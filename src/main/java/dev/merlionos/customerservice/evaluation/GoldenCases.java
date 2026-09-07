package dev.merlionos.customerservice.evaluation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** A tenant's golden set, in {@code golden_case}. */
@Component
public class GoldenCases {

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{2,8})?");
    private static final RowMapper<GoldenCase> CASE = (rs, i) -> new GoldenCase(rs.getLong("id"), rs.getString("tenant_id"),
            rs.getString("question"), rs.getString("language"), Json.read(rs.getString("expected_entry_ids")),
            Json.read(rs.getString("must_contain")), Json.read(rs.getString("any_of")), Json.read(rs.getString("must_not_contain")),
            rs.getString("expect_tool"), rs.getBoolean("expect_refusal"), rs.getBoolean("enabled"), rs.getString("note"),
            rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"));

    private final JdbcTemplate jdbc;

    public GoldenCases(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<GoldenCase> of(String tenantId) {
        return jdbc.query("SELECT * FROM golden_case WHERE tenant_id = ? ORDER BY id", CASE, tenantId);
    }

    public List<GoldenCase> enabled(String tenantId) {
        return jdbc.query("SELECT * FROM golden_case WHERE tenant_id = ? AND enabled ORDER BY id", CASE, tenantId);
    }

    public Optional<GoldenCase> find(long id) {
        return jdbc.query("SELECT * FROM golden_case WHERE id = ?", CASE, id).stream().findFirst();
    }

    public int count(String tenantId) {
        return jdbc.queryForObject("SELECT count(*) FROM golden_case WHERE tenant_id = ?", Integer.class, tenantId);
    }

    /** Validated: a question, a language, and at least one thing to check. */
    public GoldenCase create(GoldenCase draft, String actor) {
        GoldenCase checked = check(draft);
        Long id = jdbc.queryForObject("""
                INSERT INTO golden_case (tenant_id, question, language, expected_entry_ids, must_contain, any_of, must_not_contain,
                                         expect_tool, expect_refusal, enabled, note, created_at, created_by)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, checked.tenantId(), checked.question(), checked.language(), Json.write(checked.expectedEntryIds()),
                Json.write(checked.mustContain()), Json.write(checked.anyOf()), Json.write(checked.mustNotContain()),
                checked.expectTool(), checked.expectRefusal(), checked.enabled(), checked.note(), Timestamp.from(Instant.now()), actor);
        return find(id).orElseThrow();
    }

    public GoldenCase update(long id, GoldenCase draft) {
        GoldenCase checked = check(draft);
        jdbc.update("""
                UPDATE golden_case SET question = ?, language = ?, expected_entry_ids = ?::jsonb, must_contain = ?::jsonb,
                       any_of = ?::jsonb, must_not_contain = ?::jsonb, expect_tool = ?, expect_refusal = ?, enabled = ?, note = ?
                WHERE id = ?
                """, checked.question(), checked.language(), Json.write(checked.expectedEntryIds()), Json.write(checked.mustContain()),
                Json.write(checked.anyOf()), Json.write(checked.mustNotContain()), checked.expectTool(), checked.expectRefusal(),
                checked.enabled(), checked.note(), id);
        return find(id).orElseThrow();
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM golden_case WHERE id = ?", id);
    }

    static GoldenCase check(GoldenCase draft) {
        if (draft.question() == null || draft.question().isBlank()) {
            throw new EvaluationRuleException("a case needs a question");
        }
        String language = draft.language() == null ? "" : draft.language().strip().toLowerCase(Locale.ROOT);
        if (!LANGUAGE.matcher(language).matches()) {
            throw new EvaluationRuleException("a language is a BCP 47 tag such as en or zh");
        }
        List<String> expected = clean(draft.expectedEntryIds());
        List<String> must = clean(draft.mustContain());
        List<String> any = clean(draft.anyOf());
        List<String> mustNot = clean(draft.mustNotContain());
        String tool = draft.expectTool() == null || draft.expectTool().isBlank() ? null : draft.expectTool().strip();
        if (expected.isEmpty() && must.isEmpty() && any.isEmpty() && mustNot.isEmpty() && tool == null && !draft.expectRefusal()) {
            throw new EvaluationRuleException("a case needs something to check: entries to retrieve, phrases, a tool, or a refusal");
        }
        if (draft.expectRefusal() && any.isEmpty() && mustNot.isEmpty()) {
            throw new EvaluationRuleException("a refusal case needs phrases that mark a refusal (anyOf) or facts it must not claim (mustNotContain)");
        }
        return new GoldenCase(draft.id(), draft.tenantId(), draft.question().strip(), language, expected, must, any, mustNot, tool,
                draft.expectRefusal(), draft.enabled(), draft.note() == null || draft.note().isBlank() ? null : draft.note().strip(),
                draft.createdAt(), draft.createdBy());
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
    }
}
