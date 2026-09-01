package dev.merlionos.customerservice.rag;

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
 * <p>Ingestion deletes every document it previously wrote before writing again, rather than
 * appending. Appending on each boot would duplicate the corpus, and duplicates do not merely
 * waste space: they crowd out distinct passages in the top-k window, so the model sees the
 * same answer four times instead of four different ones.
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

        vectorStore.delete(new FilterExpressionBuilder()
                .eq(FaqDocumentReader.METADATA_SOURCE, FaqDocumentReader.SOURCE)
                .build());
        vectorStore.add(documents);

        log.info("Ingested {} FAQ documents from {} in {} ms", documents.size(),
                properties.corpusLocation(), Duration.between(started, Instant.now()).toMillis());
        return documents.size();
    }
}
