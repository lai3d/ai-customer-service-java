package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.DraftText;
import dev.merlionos.customerservice.rag.api.KnowledgeAdmin;
import dev.merlionos.customerservice.rag.api.KnowledgeCommand;
import dev.merlionos.customerservice.rag.api.KnowledgeConflictException;
import dev.merlionos.customerservice.rag.api.KnowledgeEntry;
import dev.merlionos.customerservice.rag.api.KnowledgeRevision;
import dev.merlionos.customerservice.rag.api.KnowledgeRuleException;
import dev.merlionos.customerservice.rag.api.KnowledgeVersion;
import dev.merlionos.customerservice.rag.api.Passage;
import dev.merlionos.customerservice.rag.api.SearchQuery;
import dev.merlionos.customerservice.target.ConditionalOnTarget;
import dev.merlionos.customerservice.target.DeploymentTarget;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * {@link KnowledgeAdmin} over HTTP, served only by a {@code knowledge} process, for the
 * operations admin in a {@code chat} process. The actor crosses in the body and is trusted
 * because the bearer token authenticates the calling process; the roles were checked there.
 * A conflict is {@code 409}, a refused operation {@code 422}, an unknown thing {@code 404}.
 */
@RestController
@RequestMapping("/internal/v1/knowledge-admin")
@ConditionalOnTarget(value = DeploymentTarget.KNOWLEDGE, exclusive = true)
class KnowledgeAdminController {

    private final KnowledgeAdmin admin;

    KnowledgeAdminController(KnowledgeAdmin admin) {
        this.admin = admin;
    }

    @GetMapping("/entries")
    List<KnowledgeEntry> entries() {
        return admin.entries();
    }

    @GetMapping("/entries/{id}")
    ResponseEntity<KnowledgeEntry> entry(@PathVariable String id) {
        return admin.entry(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/entries/{id}")
    KnowledgeEntry create(@PathVariable String id, @RequestBody KnowledgeCommand command) {
        return admin.createEntry(id, command.category(), command.actor());
    }

    @PutMapping("/entries/{id}/drafts/{language}")
    KnowledgeRevision saveDraft(@PathVariable String id, @PathVariable String language, @RequestBody DraftText draft) {
        return admin.saveDraft(id, language, draft.question(), draft.answer(), draft.note(), draft.actor());
    }

    @DeleteMapping("/entries/{id}/drafts/{language}")
    void discardDraft(@PathVariable String id, @PathVariable String language) {
        admin.discardDraft(id, language);
    }

    @PostMapping("/entries/{id}/retire")
    KnowledgeEntry retire(@PathVariable String id, @RequestBody KnowledgeCommand command) {
        return admin.retire(id, Boolean.TRUE.equals(command.retired()), command.actor());
    }

    @GetMapping("/versions")
    List<KnowledgeVersion> versions() {
        return admin.versions();
    }

    @GetMapping("/versions/{version}")
    ResponseEntity<KnowledgeVersion> version(@PathVariable String version) {
        return admin.version(version).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/active")
    Map<String, String> active() {
        Map<String, String> body = new java.util.HashMap<>();
        body.put("version", admin.activeVersion().orElse(null));
        return body;
    }

    @PostMapping("/publish")
    KnowledgeVersion publish(@RequestBody KnowledgeCommand command) {
        return admin.publish(command.note(), command.actor(), command.expectedActive());
    }

    @PostMapping("/rollback")
    KnowledgeVersion rollback(@RequestBody KnowledgeCommand command) {
        return admin.rollback(command.version(), command.expectedActive(), command.actor());
    }

    @PostMapping("/preview")
    List<Passage> preview(@RequestBody SearchQuery query) {
        return admin.preview(query, query.version());
    }

    @ExceptionHandler(KnowledgeConflictException.class)
    ResponseEntity<Map<String, String>> conflict(KnowledgeConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(KnowledgeRuleException.class)
    ResponseEntity<Map<String, String>> refused(KnowledgeRuleException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
}
