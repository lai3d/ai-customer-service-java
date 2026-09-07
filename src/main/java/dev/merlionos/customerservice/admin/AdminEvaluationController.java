package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.evaluation.EvaluationResult;
import dev.merlionos.customerservice.evaluation.EvaluationRuleException;
import dev.merlionos.customerservice.evaluation.EvaluationRun;
import dev.merlionos.customerservice.evaluation.Evaluations;
import dev.merlionos.customerservice.evaluation.Evaluator;
import dev.merlionos.customerservice.evaluation.GoldenCase;
import dev.merlionos.customerservice.evaluation.GoldenCases;
import dev.merlionos.customerservice.evaluation.QualityMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The golden set and its evaluation runs, and the deflection number, per tenant
 * (docs/evaluation.md). Every member of staff reads; admins write cases and start runs. A
 * run is started, not finished: the response is the row in {@code running}, to poll. A
 * started run is recorded, since every case is a paid model call.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH + "/evaluation")
class AdminEvaluationController {

    private final GoldenCases cases;
    private final Evaluations evaluations;
    private final Evaluator evaluator;
    private final QualityMetrics quality;
    private final AdminAudit audit;

    AdminEvaluationController(GoldenCases cases, Evaluations evaluations, Evaluator evaluator, QualityMetrics quality, AdminAudit audit) {
        this.cases = cases;
        this.evaluations = evaluations;
        this.evaluator = evaluator;
        this.quality = quality;
        this.audit = audit;
    }

    // --- the golden set --------------------------------------------------------------------

    @GetMapping("/cases")
    List<GoldenCase> cases(@RequestParam(required = false) String tenant, Authentication auth) {
        return cases.of(StaffScope.of(auth).oneTenant(tenant));
    }

    record CaseInput(String question, String language, List<String> expectedEntryIds, List<String> mustContain, List<String> anyOf,
                     List<String> mustNotContain, String expectTool, Boolean expectRefusal, Boolean enabled, String note) {

        GoldenCase draft(Long id, String tenantId) {
            return new GoldenCase(id, tenantId, question, language, expectedEntryIds, mustContain, anyOf, mustNotContain, expectTool,
                    Boolean.TRUE.equals(expectRefusal), enabled == null || enabled, note, null, null);
        }
    }

    @PostMapping("/cases")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<GoldenCase> create(@RequestParam(required = false) String tenant, @RequestBody CaseInput input, Authentication auth) {
        String id = StaffScope.of(auth).oneTenant(tenant);
        return ResponseEntity.status(HttpStatus.CREATED).body(cases.create(input.draft(null, id), auth.getName()));
    }

    @PutMapping("/cases/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    GoldenCase update(@PathVariable long id, @RequestBody CaseInput input, Authentication auth) {
        GoldenCase existing = scoped(id, auth);
        return cases.update(id, input.draft(id, existing.tenantId()));
    }

    @DeleteMapping("/cases/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<Void> delete(@PathVariable long id, Authentication auth) {
        scoped(id, auth);
        cases.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** A case outside the caller's tenant is answered as missing. */
    private GoldenCase scoped(long id, Authentication auth) {
        return cases.find(id).filter(c -> StaffScope.of(auth).covers(c.tenantId()))
                .orElseThrow(() -> new NotFound("No case " + id));
    }

    // --- runs ----------------------------------------------------------------------------------

    record NewRun(String note) {
    }

    @PostMapping("/runs")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<EvaluationRun> start(@RequestParam(required = false) String tenant, @RequestBody(required = false) NewRun request,
                                        Authentication auth) {
        String id = StaffScope.of(auth).oneTenant(tenant);
        EvaluationRun run = evaluator.start(id, auth.getName(), request == null ? null : request.note());
        audit.record(auth.getName(), AdminAudit.Action.EVALUATED, id, "run " + run.id() + ", " + run.cases() + " cases"
                + (run.note() == null ? "" : ": " + run.note()));
        return ResponseEntity.accepted().body(run);
    }

    @GetMapping("/runs")
    List<EvaluationRun> runs(@RequestParam(required = false) String tenant, Authentication auth) {
        return evaluations.of(StaffScope.of(auth).oneTenant(tenant));
    }

    record RunDetail(EvaluationRun run, List<EvaluationResult> results) {
    }

    @GetMapping("/runs/{id}")
    RunDetail run(@PathVariable long id, @RequestParam(required = false) String tenant, Authentication auth) {
        String tenantId = StaffScope.of(auth).oneTenant(tenant);
        EvaluationRun run = evaluations.find(tenantId, id).orElseThrow(() -> new NotFound("No run " + id));
        return new RunDetail(run, evaluations.resultsOf(id));
    }

    // --- deflection --------------------------------------------------------------------------

    record DeflectionView(String tenant, int days, long conversations, long escalated, long flagged, double deflectionRate, String definition) {
    }

    @GetMapping("/deflection")
    DeflectionView deflection(@RequestParam(required = false) String tenant, @RequestParam(defaultValue = "7") int days, Authentication auth) {
        String id = StaffScope.of(auth).oneTenant(tenant);
        int window = Math.max(1, Math.min(days, 90));
        QualityMetrics.Deflection d = quality.deflection(id, Instant.now().minus(java.time.Duration.ofDays(window)));
        return new DeflectionView(id, window, d.conversations(), d.escalated(), d.flagged(), d.deflectionRate(),
                "Customer conversations with a turn in the last " + window + " days that ended without a ticket for a person, "
                        + "over all such conversations. Evaluation conversations are not counted.");
    }

    static class NotFound extends RuntimeException {
        NotFound(String message) {
            super(message);
        }
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<Map<String, String>> notFound(NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(EvaluationRuleException.class)
    ResponseEntity<Map<String, String>> refused(EvaluationRuleException e, Authentication auth) {
        audit.record(auth.getName(), AdminAudit.Action.REFUSED, "evaluation", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
}
