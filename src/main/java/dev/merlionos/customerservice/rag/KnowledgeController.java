package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.KnowledgeSearch;
import dev.merlionos.customerservice.rag.api.Passage;
import dev.merlionos.customerservice.rag.api.SearchQuery;
import dev.merlionos.customerservice.target.ConditionalOnTarget;
import dev.merlionos.customerservice.target.DeploymentTarget;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The knowledge seam over HTTP, served only by a {@code knowledge} process. The query is
 * embedded here, next to the model, so the {@code query:} marker e5 needs is applied exactly
 * once and the caller never sees an embedding. A failure is a {@code 5xx}, which the chat
 * side turns into a failed turn -- an empty result would be a lie about the corpus.
 */
@RestController
@RequestMapping("/internal/v1/knowledge")
@ConditionalOnTarget(value = DeploymentTarget.KNOWLEDGE, exclusive = true)
class KnowledgeController {

    private final KnowledgeSearch search;

    KnowledgeController(KnowledgeSearch search) {
        this.search = search;
    }

    @PostMapping("/search")
    List<Passage> search(@RequestBody SearchQuery query) {
        return search.search(query);
    }
}
