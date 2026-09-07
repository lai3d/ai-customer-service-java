package dev.merlionos.customerservice.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link KnowledgeAdmin} as a client, for a {@code chat} process. No retry: the caller is a
 * person at a page, and a publication that may or may not have happened is answered by
 * reading the versions. The three non-record statuses come back as the exceptions the
 * local implementation throws, with the server's sentence.
 */
public class HttpKnowledgeAdmin implements KnowledgeAdmin {

    private static final String BASE = "/internal/v1/knowledge-admin";
    private static final ParameterizedTypeReference<List<KnowledgeEntry>> ENTRIES = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<KnowledgeVersion>> VERSIONS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Passage>> PASSAGES = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Map<String, String>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper();

    public HttpKnowledgeAdmin(RestClient client) {
        this.client = client;
    }

    @Override
    public List<KnowledgeEntry> entries(String tenantId) {
        List<KnowledgeEntry> entries = client.get().uri(BASE + "/{t}/entries", tenantId).retrieve().body(ENTRIES);
        return entries == null ? List.of() : entries;
    }

    @Override
    public Optional<KnowledgeEntry> entry(String tenantId, String entryId) {
        try {
            return Optional.ofNullable(client.get().uri(BASE + "/{t}/entries/{id}", tenantId, entryId).retrieve().body(KnowledgeEntry.class));
        }
        catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public KnowledgeEntry createEntry(String tenantId, String entryId, String category, String actor) {
        return translating(() -> client.post().uri(BASE + "/{t}/entries/{id}", tenantId, entryId)
                .body(new KnowledgeCommand(actor, null, null, category, null, null)).retrieve().body(KnowledgeEntry.class));
    }

    @Override
    public KnowledgeRevision saveDraft(String tenantId, String entryId, String language, String question, String answer, String note, String actor) {
        return translating(() -> client.put().uri(BASE + "/{t}/entries/{id}/drafts/{lang}", tenantId, entryId, language)
                .body(new DraftText(question, answer, note, actor)).retrieve().body(KnowledgeRevision.class));
    }

    @Override
    public void discardDraft(String tenantId, String entryId, String language) {
        translating(() -> client.delete().uri(BASE + "/{t}/entries/{id}/drafts/{lang}", tenantId, entryId, language).retrieve().toBodilessEntity());
    }

    @Override
    public KnowledgeEntry retire(String tenantId, String entryId, boolean retired, String actor) {
        return translating(() -> client.post().uri(BASE + "/{t}/entries/{id}/retire", tenantId, entryId)
                .body(new KnowledgeCommand(actor, null, null, null, null, retired)).retrieve().body(KnowledgeEntry.class));
    }

    @Override
    public List<KnowledgeVersion> versions(String tenantId) {
        List<KnowledgeVersion> versions = client.get().uri(BASE + "/{t}/versions", tenantId).retrieve().body(VERSIONS);
        return versions == null ? List.of() : versions;
    }

    @Override
    public Optional<KnowledgeVersion> version(String tenantId, String version) {
        try {
            return Optional.ofNullable(client.get().uri(BASE + "/{t}/versions/{v}", tenantId, version).retrieve().body(KnowledgeVersion.class));
        }
        catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> activeVersion(String tenantId) {
        Map<String, String> body = client.get().uri(BASE + "/{t}/active", tenantId).retrieve().body(MAP);
        return Optional.ofNullable(body == null ? null : body.get("version"));
    }

    @Override
    public KnowledgeVersion publish(String tenantId, String note, String actor, String expectedActive) {
        return translating(() -> client.post().uri(BASE + "/{t}/publish", tenantId)
                .body(new KnowledgeCommand(actor, expectedActive, note, null, null, null)).retrieve().body(KnowledgeVersion.class));
    }

    @Override
    public KnowledgeVersion rollback(String tenantId, String version, String expectedActive, String actor) {
        return translating(() -> client.post().uri(BASE + "/{t}/rollback", tenantId)
                .body(new KnowledgeCommand(actor, expectedActive, null, null, version, null)).retrieve().body(KnowledgeVersion.class));
    }

    @Override
    public List<Passage> preview(SearchQuery query, String version) {
        List<Passage> passages = translating(() -> client.post().uri(BASE + "/preview")
                .body(new SearchQuery(query.tenantId(), query.text(), query.topK(), query.similarityThreshold(), version)).retrieve().body(PASSAGES));
        return passages == null ? List.of() : passages;
    }

    private <T> T translating(Supplier<T> call) {
        try {
            return call.get();
        }
        catch (HttpClientErrorException e) {
            String message = message(e);
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new KnowledgeConflictException(message);
            }
            if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                throw new KnowledgeRuleException(message);
            }
            throw e;
        }
    }

    private String message(HttpClientErrorException e) {
        try {
            return String.valueOf(json.readValue(e.getResponseBodyAsString(), Map.class).get("error"));
        }
        catch (Exception ignored) {
            return "refused by the knowledge service (" + e.getStatusCode() + ")";
        }
    }
}
