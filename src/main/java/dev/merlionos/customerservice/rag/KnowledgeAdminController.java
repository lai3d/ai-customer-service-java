package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.DraftText;
import dev.merlionos.customerservice.rag.api.ImportRequest;
import dev.merlionos.customerservice.rag.api.KnowledgeImport;
import dev.merlionos.customerservice.rag.api.EntryFilter;
import dev.merlionos.customerservice.rag.api.EntryPage;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/{tenant}/entries")
    List<KnowledgeEntry> entries(@PathVariable String tenant) {
        return admin.entries(tenant);
    }

    @GetMapping("/{tenant}/entries/page")
    EntryPage entriesPage(@PathVariable String tenant, @RequestParam(required = false) String text,
                          @RequestParam(required = false) String source, @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "0") int size) {
        return admin.entries(tenant, new EntryFilter(text, source, page, size));
    }

    @GetMapping("/{tenant}/entries/{id}")
    ResponseEntity<KnowledgeEntry> entry(@PathVariable String tenant, @PathVariable String id) {
        return admin.entry(tenant, id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{tenant}/entries/{id}")
    KnowledgeEntry create(@PathVariable String tenant, @PathVariable String id, @RequestBody KnowledgeCommand command) {
        return admin.createEntry(tenant, id, command.category(), command.actor());
    }

    @PutMapping("/{tenant}/entries/{id}/drafts/{language}")
    KnowledgeRevision saveDraft(@PathVariable String tenant, @PathVariable String id, @PathVariable String language,
                                @RequestBody DraftText draft) {
        return admin.saveDraft(tenant, id, language, draft.question(), draft.answer(), draft.note(), draft.actor());
    }

    @DeleteMapping("/{tenant}/entries/{id}/drafts/{language}")
    void discardDraft(@PathVariable String tenant, @PathVariable String id, @PathVariable String language) {
        admin.discardDraft(tenant, id, language);
    }

    @PostMapping("/{tenant}/entries/{id}/retire")
    KnowledgeEntry retire(@PathVariable String tenant, @PathVariable String id, @RequestBody KnowledgeCommand command) {
        return admin.retire(tenant, id, Boolean.TRUE.equals(command.retired()), command.actor());
    }

    @GetMapping("/{tenant}/versions")
    List<KnowledgeVersion> versions(@PathVariable String tenant) {
        return admin.versions(tenant);
    }

    @GetMapping("/{tenant}/versions/{version}")
    ResponseEntity<KnowledgeVersion> version(@PathVariable String tenant, @PathVariable String version) {
        return admin.version(tenant, version).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{tenant}/active")
    Map<String, String> active(@PathVariable String tenant) {
        Map<String, String> body = new java.util.HashMap<>();
        body.put("version", admin.activeVersion(tenant).orElse(null));
        return body;
    }

    @PostMapping("/{tenant}/publish")
    KnowledgeVersion publish(@PathVariable String tenant, @RequestBody KnowledgeCommand command) {
        return admin.publish(tenant, command.note(), command.actor(), command.expectedActive());
    }

    @PostMapping("/{tenant}/rollback")
    KnowledgeVersion rollback(@PathVariable String tenant, @RequestBody KnowledgeCommand command) {
        return admin.rollback(tenant, command.version(), command.expectedActive(), command.actor());
    }

    @PostMapping("/{tenant}/imports/url")
    KnowledgeImport importUrl(@PathVariable String tenant, @RequestBody ImportRequest request) {
        return admin.importUrl(tenant, request.url(), request.actor());
    }

    @PostMapping("/{tenant}/imports/pdf")
    KnowledgeImport importPdf(@PathVariable String tenant, @RequestBody ImportRequest request) {
        return admin.importPdf(tenant, request.fileName(), request.content(), request.actor());
    }

    @PostMapping("/{tenant}/imports/xboard")
    KnowledgeImport importXboard(@PathVariable String tenant, @RequestBody ImportRequest request) {
        return admin.importXboard(tenant, request.baseUrl(), request.adminPath(), request.adminToken(), request.actor());
    }

    @GetMapping("/{tenant}/imports")
    List<KnowledgeImport> imports(@PathVariable String tenant) {
        return admin.imports(tenant);
    }

    @GetMapping("/{tenant}/imports/{id}")
    ResponseEntity<KnowledgeImport> importOf(@PathVariable String tenant, @PathVariable long id) {
        return admin.importOf(tenant, id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
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
