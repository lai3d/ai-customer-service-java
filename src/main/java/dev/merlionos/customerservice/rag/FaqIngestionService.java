package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Loads the FAQ corpus into the vector store.
 *
 * <p>Ingestion writes the current corpus first and only then deletes what is left over from an
 * older version. The order matters. Deleting first was the original design and it left a window
 * in which the corpus was empty — invisible on one machine, but the Kubernetes manifest runs two
 * replicas against one database, so a rolling deploy meant the replica still serving traffic
 * could retrieve nothing, and a failure between the delete and the insert left it that way.
 *
 * <p>Writing first is safe because document ids are stable ({@code faq:<entry>:<language>}) and
 * the store upserts on id conflict, so a re-ingest overwrites in place rather than duplicating.
 * Stale rows are then identified by {@code corpus_version} rather than deleted wholesale, which
 * also makes two replicas ingesting the same version at once harmless: they converge on the
 * same rows instead of racing to delete each other's work.
 *
 * <p>Re-embedding the whole corpus on every start is only reasonable because the corpus is
 * small and the embedding model runs in-process. A corpus large enough for that to hurt would
 * want per-document change detection and an incremental sync instead.
 */
@Service
public class FaqIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FaqIngestionService.class);

    private final VectorStore vectorStore;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final RagProperties properties;

    FaqIngestionService(VectorStore vectorStore, ResourceLoader resourceLoader,
                        ObjectMapper objectMapper, RagProperties properties) {
        this.vectorStore = vectorStore;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ingestOnStartup() {
        if (!properties.ingestOnStartup()) {
            log.info("FAQ ingestion disabled (app.rag.ingest-on-startup=false)");
            return;
        }
        ingest();
    }

    /**
     * @return the number of documents written
     */
    public int ingest() {
        Instant started = Instant.now();

        List<Document> documents = new FaqDocumentReader(
                resourceLoader.getResource(properties.corpusLocation()), objectMapper).get();

        String version = String.valueOf(
                documents.getFirst().getMetadata().get(FaqDocumentReader.METADATA_VERSION));

        // Write first: an upsert on stable ids, so the corpus is never momentarily absent.
        vectorStore.add(documents);

        // Then retire only what belongs to an older corpus version.
        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        vectorStore.delete(filter.and(
                filter.eq(FaqDocumentReader.METADATA_SOURCE, FaqDocumentReader.SOURCE),
                filter.ne(FaqDocumentReader.METADATA_VERSION, version)).build());

        log.info("Ingested {} FAQ documents (version {}) from {} in {} ms", documents.size(),
                version, properties.corpusLocation(),
                Duration.between(started, Instant.now()).toMillis());
        return documents.size();
    }
}
