package dev.merlionos.customerservice.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Answer feedback: flag a recorded turn, list the flags, close one with a conclusion. Both
 * roles may flag and handle; a refused change is written to {@code admin_audit} like a
 * refused ticket change, and a lost race is not.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH + "/feedback")
class AdminFeedbackController {

    private final AnswerFeedback feedback;
    private final AdminAudit audit;

    AdminFeedbackController(AnswerFeedback feedback, AdminAudit audit) {
        this.feedback = feedback;
        this.audit = audit;
    }

    @GetMapping
    AnswerFeedback.Page list(@RequestParam(required = false) String state,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "0") int size) {
        return feedback.list(state, page, size);
    }

    @GetMapping("/{id}")
    AnswerFeedback.Report one(@PathVariable long id) {
        return feedback.find(id).orElseThrow(() -> new AnswerFeedback.NotFound(id));
    }

    record NewReport(String turnId, String issue, String note) {
    }

    @PostMapping
    ResponseEntity<AnswerFeedback.Report> report(@RequestBody NewReport request, Authentication authentication) {
        AnswerFeedback.Report created = feedback.report(request.turnId(), request.issue(), request.note(),
                authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** @param revisionId the knowledge revision that fixed the answer, optional */
    record Handling(String state, String conclusion, Long revisionId, int expectedVersion) {
    }

    @PostMapping("/{id}/handle")
    AnswerFeedback.Report handle(@PathVariable long id, @RequestBody Handling request, Authentication authentication) {
        return feedback.handle(id, request.state(), request.conclusion(), request.revisionId(), authentication.getName(),
                request.expectedVersion());
    }

    @ExceptionHandler(AnswerFeedback.NotFound.class)
    ResponseEntity<Map<String, String>> notFound(AnswerFeedback.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AnswerFeedback.Conflict.class)
    ResponseEntity<Map<String, String>> conflict(AnswerFeedback.Conflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AnswerFeedback.Rule.class)
    ResponseEntity<Map<String, String>> refused(AnswerFeedback.Rule e, Authentication authentication) {
        audit.record(authentication.getName(), AdminAudit.Action.REFUSED, "feedback", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
