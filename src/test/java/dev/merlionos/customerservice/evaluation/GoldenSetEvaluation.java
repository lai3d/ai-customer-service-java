package dev.merlionos.customerservice.evaluation;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.tenancy.Tenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bundled golden set against the real provider: opt in with
 * {@code ./mvnw test -Dexcluded.test.groups= -Dtest=GoldenSetEvaluation} and the provider's
 * key in the environment (the test profile's placeholder key is overridden by
 * {@code ANTHROPIC_API_KEY} and friends). Forty-odd model calls; the report says what
 * they cost. Writes {@code target/evaluation-report.md}, the table docs/evaluation.md quotes.
 */
@Tag("evaluation")
@SpringBootTest(properties = "app.rag.import-mode=startup")
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class GoldenSetEvaluation {

    @Autowired Evaluator evaluator;
    @Autowired Evaluations evaluations;
    @Autowired GoldenCases cases;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @DisplayName("the bundled golden set, live: retrieval finds what it should for nearly every case, and the report is written")
    void bundledGoldenSetLive() throws Exception {
        // -Devaluation.only=30,37 runs those case ids alone: for reproducing one answer without paying for forty.
        String only = System.getProperty("evaluation.only", "");
        if (!only.isBlank()) {
            List<Long> keep = java.util.Arrays.stream(only.split(",")).map(String::strip).map(Long::valueOf).toList();
            jdbc.update("UPDATE golden_case SET enabled = (id = ANY(?)) WHERE tenant_id = ?", keep.toArray(Long[]::new), Tenant.DEFAULT);
        }
        EvaluationRun run = evaluator.runNow(Tenant.DEFAULT, "evaluation", "GoldenSetEvaluation");
        List<EvaluationResult> results = evaluations.resultsOf(run.id());

        StringBuilder report = new StringBuilder();
        report.append("# Evaluation run ").append(run.id()).append("\n\n")
                .append("Model: ").append(run.model()).append("  \nCases: ").append(run.cases())
                .append("  \nPassed: ").append(run.passed()).append("  \nRetrieval hits: ").append(run.retrievalHits())
                .append("  \nAnswer passes: ").append(run.answerPasses()).append("  \nTool passes: ").append(run.toolPasses())
                .append("  \nTokens: ").append(run.inputTokens()).append(" in / ").append(run.outputTokens()).append(" out\n\n")
                .append("| # | Question | Retrieval | Answer | Tool | Tokens in/out | ms | Failures |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (EvaluationResult r : results) {
            report.append("| ").append(r.caseId()).append(" | ").append(r.question().replace("|", "\\|"))
                    .append(" | ").append(r.retrievalHit() ? "hit" : "miss").append(" | ").append(r.answerPass() ? "pass" : "fail")
                    .append(" | ").append(r.toolPass() ? "pass" : "fail")
                    .append(" | ").append(r.inputTokens()).append("/").append(r.outputTokens()).append(" | ").append(r.millis()).append(" | ")
                    .append(r.failures() == null ? "" : r.failures().replace("|", "\\|")).append(" |\n");
        }
        report.append("\n## Answers\n\n");
        for (EvaluationResult r : results) {
            report.append("### ").append(r.caseId()).append(". ").append(r.question()).append("\n\n")
                    .append(r.answer() == null ? "(no answer)" : r.answer()).append("\n\n");
        }
        Path out = Path.of("target/evaluation-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println(report.substring(0, report.indexOf("## Answers")));

        assertThat(run.state()).as(run.error()).isEqualTo("done");
        if (only.isBlank()) {
            assertThat(run.retrievalHitRatio()).as("retrieval, the part that does not depend on the model's wording").isGreaterThanOrEqualTo(0.85);
        }
    }
}
