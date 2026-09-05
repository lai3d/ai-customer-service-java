package dev.merlionos.customerservice.rag.api;

import java.util.List;
import java.util.Optional;

/**
 * Editing and publishing the knowledge base: the seam the operations admin reaches it
 * through, as {@link KnowledgeSearch} is the seam retrieval reaches it through.
 *
 * <h2>How a change reaches a customer</h2>
 *
 * <p>Saving a draft changes nothing a customer sees. {@link #publish} builds a new version
 * from every entry's draft where there is one and its published revision where there is
 * not, embeds the whole set under a new {@code corpus_version}, and only then switches the
 * active version, with an expected-version check so an older publication finishing late
 * cannot overwrite a newer one. A failed build leaves the previous version serving and the
 * failure on the version row. {@link #rollback} switches back to any retained version;
 * the last few are kept, older ones are retired and their documents deleted.
 *
 * <p>Retrieval filters by the active version at query time, so requests that started before
 * a switch keep reading the version they started on, and a candidate is never mixed with
 * what is live.
 */
public interface KnowledgeAdmin {

    List<KnowledgeEntry> entries();

    Optional<KnowledgeEntry> entry(String entryId);

    /** Creates an entry with no text yet; drafts follow per language. */
    KnowledgeEntry createEntry(String entryId, String category, String actor);

    /** Saves the draft for an entry and language, replacing the previous draft if there was one. */
    KnowledgeRevision saveDraft(String entryId, String language, String question, String answer, String note, String actor);

    /** Drops the draft for an entry and language; the published text stays as it is. */
    void discardDraft(String entryId, String language);

    /** Marks an entry retired; the next publication leaves it out. Reversible until then. */
    KnowledgeEntry retire(String entryId, boolean retired, String actor);

    List<KnowledgeVersion> versions();

    Optional<KnowledgeVersion> version(String version);

    /** The active version's id, or empty on a knowledge base nothing has been published to. */
    Optional<String> activeVersion();

    /**
     * Builds and activates a new version. Synchronous: embedding a few dozen entries takes
     * seconds; the caller decides whether to wait or to run it off the request thread and
     * return the version row as the job to poll.
     *
     * @param expectedActive the active version the caller saw, or null to skip the check
     */
    KnowledgeVersion publish(String note, String actor, String expectedActive);

    /** Activates a retained version. */
    KnowledgeVersion rollback(String version, String expectedActive, String actor);

    /** What retrieval would find in a given version, or in the active one when null. */
    List<Passage> preview(SearchQuery query, String version);
}
