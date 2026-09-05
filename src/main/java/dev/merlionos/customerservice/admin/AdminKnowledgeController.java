package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.rag.api.KnowledgeAdmin;
import dev.merlionos.customerservice.rag.api.KnowledgeConflictException;
import dev.merlionos.customerservice.rag.api.KnowledgeEntry;
import dev.merlionos.customerservice.rag.api.KnowledgeRevision;
import dev.merlionos.customerservice.rag.api.KnowledgeRuleException;
import dev.merlionos.customerservice.rag.api.KnowledgeVersion;
import dev.merlionos.customerservice.rag.api.Passage;
import dev.merlionos.customerservice.rag.api.RagProperties;
import dev.merlionos.customerservice.rag.api.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Knowledge in the admin: entries and drafts for every signed-in member of staff; retire,
 * publish and rollback for admins. A publication runs off the request thread and answers
 * {@code 202} with the version row, which is the job to poll: {@code building} until the
 * documents are embedded, then {@code active}, or {@code failed} with the reason. A
 * publication or rollback is recorded in {@code admin_audit}; a refusal is, as everywhere
 * in the admin; a conflict is not.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH + "/knowledge")
class AdminKnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(AdminKnowledgeController.class);

    private final KnowledgeAdmin knowledge;
    private final RagProperties rag;
    private final AdminAudit audit;

    AdminKnowledgeController(KnowledgeAdmin knowledge, RagProperties rag, AdminAudit audit) {
        this.knowledge = knowledge;
        this.rag = rag;
        this.audit = audit;
    }

    @GetMapping("/entries")
    List<KnowledgeEntry> entries() {
        return knowledge.entries();
    }

    @GetMapping("/entries/{id}")
    KnowledgeEntry entry(@PathVariable String id) {
        return knowledge.entry(id).orElseThrow(() -> new NotFound("No entry '" + id + "'"));
    }

    record NewEntry(String category) {
    }

    @PostMapping("/entries/{id}")
    ResponseEntity<KnowledgeEntry> create(@PathVariable String id, @RequestBody NewEntry request, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(knowledge.createEntry(id, request.category(), auth.getName()));
    }

    record Draft(String question, String answer, String note) {
    }

    @PutMapping("/entries/{id}/drafts/{language}")
    KnowledgeRevision saveDraft(@PathVariable String id, @PathVariable String language, @RequestBody Draft draft, Authentication auth) {
        return knowledge.saveDraft(id, language, draft.question(), draft.answer(), draft.note(), auth.getName());
    }

    @DeleteMapping("/entries/{id}/drafts/{language}")
    ResponseEntity<Void> discardDraft(@PathVariable String id, @PathVariable String language) {
        knowledge.discardDraft(id, language);
        return ResponseEntity.noContent().build();
    }

    record Retirement(boolean retired) {
    }

    @PostMapping("/entries/{id}/retire")
    @PreAuthorize("hasRole('ADMIN')")
    KnowledgeEntry retire(@PathVariable String id, @RequestBody Retirement request, Authentication auth) {
        return knowledge.retire(id, request.retired(), auth.getName());
    }

    @GetMapping("/versions")
    Map<String, Object> versions() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("active", knowledge.activeVersion().orElse(null));
        body.put("versions", knowledge.versions());
        return body;
    }

    @GetMapping("/versions/{version}")
    KnowledgeVersion version(@PathVariable String version) {
        return knowledge.version(version).orElseThrow(() -> new NotFound("No version '" + version + "'"));
    }

    record Publication(String note, String expectedActive) {
    }

    /**
     * Started, not finished: the response is the version row in {@code building}. The
     * conflict check on {@code expectedActive} happens before anything is built, so a stale
     * page is told at once; a switch that loses a later race leaves the version {@code ready}
     * with the reason on the row, which the poll shows.
     */
    @PostMapping("/publish")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<Map<String, Object>> publish(@RequestBody Publication request, Authentication auth) {
        String expected = request.expectedActive();
        if (expected != null && !expected.equals(knowledge.activeVersion().orElse(null))) {
            throw new KnowledgeConflictException("The active version is " + knowledge.activeVersion().orElse("none")
                    + ", not " + expected + "; reload and look again");
        }
        String actor = auth.getName();
        Thread.ofVirtual().name("knowledge-publish").start(() -> {
            try {
                KnowledgeVersion version = knowledge.publish(request.note(), actor, expected);
                audit.record(actor, AdminAudit.Action.PUBLISHED, version.version(),
                        version.state() + (request.note() == null ? "" : ": " + request.note()));
            }
            catch (RuntimeException e) {
                log.warn("Publication by {} did not complete: {}", actor, e.getMessage());
                audit.record(actor, AdminAudit.Action.REFUSED, "knowledge", e.getMessage());
            }
        });
        return ResponseEntity.accepted().body(Map.of("started", true, "by", actor));
    }

    record Rollback(String version, String expectedActive) {
    }

    @PostMapping("/rollback")
    @PreAuthorize("hasRole('ADMIN')")
    KnowledgeVersion rollback(@RequestBody Rollback request, Authentication auth) {
        KnowledgeVersion version = knowledge.rollback(request.version(), request.expectedActive(), auth.getName());
        audit.record(auth.getName(), AdminAudit.Action.ROLLED_BACK, version.version(), "from " + request.expectedActive());
        return version;
    }

    record Preview(String text, String version, Integer topK) {
    }

    @PostMapping("/preview")
    List<Passage> preview(@RequestBody Preview request) {
        int topK = request.topK() == null || request.topK() < 1 ? rag.topK() : Math.min(request.topK(), 20);
        return knowledge.preview(new SearchQuery(request.text(), topK, rag.similarityThreshold()), request.version());
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

    @ExceptionHandler(KnowledgeConflictException.class)
    ResponseEntity<Map<String, String>> conflict(KnowledgeConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(KnowledgeRuleException.class)
    ResponseEntity<Map<String, String>> refused(KnowledgeRuleException e, Authentication auth) {
        audit.record(auth.getName(), AdminAudit.Action.REFUSED, "knowledge", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
}
