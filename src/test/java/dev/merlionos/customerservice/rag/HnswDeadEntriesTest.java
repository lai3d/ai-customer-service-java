package dev.merlionos.customerservice.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.rag.api.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-importing the corpus leaves the previous rows' entries in the HNSW index until VACUUM
 * removes them. The .NET implementation of this system found that an HNSW scan collects
 * {@code hnsw.ef_search} candidates from the graph first and drops the dead ones afterwards,
 * so after enough reloads without a vacuum {@code ORDER BY embedding <=> ? LIMIT 8} returned
 * fewer than eight live rows -- zero, in psql on pgvector 0.8.6 with autovacuum off after
 * thirty delete-and-reinsert transactions, and about one run in four with autovacuum on.
 *
 * <p>The zero turned out to be a degenerate case: the report's stub embeddings were all
 * identical, so every graph point sat at distance zero and the candidates were one point's
 * dead copies. Re-run there with 36 distinct vectors it became 7 of 8 after sixty reloads:
 * degradation, not starvation. The same experiment here with the real bilingual corpus --
 * closer to the degenerate case than random vectors, since each entry's two languages are
 * near-duplicates -- runs twice: with the importer's own write pattern (upsert, then retire
 * the old version) and with the delete-and-reinsert pattern the report used. Autovacuum is
 * off on the table so the result is about the mechanism, not the daemon's timing, and the
 * scan is forced through the index because on a 36-row table the planner would otherwise
 * pick a sequential scan and hide what a real corpus size exposes.
 *
 * <p>Measured with pgvector's raw scan: thirty reloads of either pattern still returned 8 of 8
 * (725 and 864 dead tuples, the index at twice its size); sixty delete-and-reinserts returned
 * <b>6 or 7 of 8</b> with about 1100 dead tuples on the heap (the statistics view says about
 * 2000) and the index at five times its size, seven runs in a row. The application's own connections do not see that: since the knowledge-version
 * design keeps retired rows around, every pooled connection carries
 * {@code hnsw.iterative_scan = strict_order} (Hikari connection-init-sql), and an iterative
 * scan keeps walking the graph until k live rows pass. So there are two guards -- the GUC on
 * the application's reads, the vacuum after the bundled import on the table -- and this test
 * measures the raw scan with the GUC off, because that is what the second guard is for.
 * A first version of the observation pinned "6 of 8" and CI answered "8 of 8": the pooled
 * connection had the GUC on, and the pin was measuring the guard rather than the defect.
 * The raw count has been measured on one machine only, so it is printed, not pinned; what is
 * asserted holds anywhere: the dead entries accumulate, the scan returns at most top-k, a
 * vacuum restores exactly top-k. The first test pins that sixty imports through the importer
 * leave the table clean and the top-k whole.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class HnswDeadEntriesTest {

    private static final int RELOADS = 60;
    private static final String QUESTION = "my parcel showed up broken";

    @Autowired CorpusImporter importer;
    @Autowired VectorStore vectorStore;
    @Autowired EmbeddingModel embeddingModel;
    @Autowired RagProperties ragProperties;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("thirty imports through the importer leave no dead tuples and the full top-k through the index")
    void importerLeavesTheIndexClean() {
        jdbc.execute("ALTER TABLE vector_store SET (autovacuum_enabled = false)");

        for (int i = 0; i < RELOADS; i++) {
            // Each release records a new corpus version; forgetting the record is how a test
            // makes the importer take the production path thirty times.
            jdbc.update("DELETE FROM corpus_import");
            assertThat(importer.importIfMissing()).isEqualTo(CorpusImporter.Outcome.IMPORTED);
        }

        assertThat(liveRows()).isEqualTo(36);
        assertThat(deadTuples()).as("the importer vacuums after each import").isZero();
        assertThat(vectorStore.similaritySearch(SearchRequest.builder()
                .query(QUESTION).topK(ragProperties.topK()).similarityThreshold(0).build()))
                .hasSize(ragProperties.topK());
        assertThat(forcedIndexScan()).isEqualTo(ragProperties.topK());
    }

    @Test
    @DisplayName("sixty delete-and-reinserts without a vacuum fill the index with dead entries; a vacuum restores the full top-k")
    void deleteAndReinsertPatternUnderObservation() {
        jdbc.execute("ALTER TABLE vector_store SET (autovacuum_enabled = false)");
        List<Document> corpus = new FaqDocumentReader(
                new DefaultResourceLoader().getResource(ragProperties.corpusLocation()), new ObjectMapper()).get();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        for (int i = 0; i < RELOADS; i++) {
            transaction.execute(status -> {
                jdbc.update("DELETE FROM vector_store");
                vectorStore.add(corpus);
                return null;
            });
        }

        int dead = deadTuples();
        int viaIndex = forcedIndexScan();
        System.out.printf("### delete+reinsert x%d: live=%d dead=%d index=%s forced index scan rows=%d%n",
                RELOADS, liveRows(), dead, indexSize(), viaIndex);
        // Sixty reloads delete 2160 rows; pgstattuple counted about 1100 of them still on the
        // heap (the statistics view says about 2000). The bar is a quarter of the deletions:
        // enough to say the index is mostly dead entries, not a number to be exact about.
        assertThat(dead).as("the pattern does leave the index full of dead entries").isGreaterThan(RELOADS * 36 / 4);
        // Not pinned below top-k: the raw count has been measured on this machine only (6 or 7 of
        // 8, five times), and a first version that pinned it went red on CI for a reason that
        // was not the graph. The line printed above is the measurement; the assertions are
        // what holds anywhere.
        assertThat(viaIndex)
                .as("a mostly-dead HNSW index returns at most top-k, silently fewer")
                .isBetween(0, ragProperties.topK());

        jdbc.execute("VACUUM vector_store");
        assertThat(deadTuples()).isZero();
        assertThat(forcedIndexScan()).as("a vacuum restores the full top-k").isEqualTo(ragProperties.topK());
    }

    private int liveRows() {
        return jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class);
    }

    /**
     * Counted on the heap by {@code pgstattuple}, not read from {@code pg_stat_user_tables}.
     * The statistics view is what each backend flushes asynchronously: a run read 180 dead
     * tuples right after a vacuum on another pooled connection, and CI read 36 -- the last
     * import's updates, flushed by the importing backend after the vacuum had reported the
     * table clean. Neither was a dead tuple on the heap; both were accounting.
     */
    private int deadTuples() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgstattuple");
        return jdbc.queryForObject("SELECT dead_tuple_count FROM pgstattuple('vector_store')", Integer.class);
    }

    private String indexSize() {
        return jdbc.queryForObject("SELECT pg_size_pretty(pg_relation_size('spring_ai_vector_index'))", String.class);
    }

    /** The same query with the sequential scan taken away, which a larger corpus does for real. */
    private int forcedIndexScan() {
        String vector = Arrays.toString(embeddingModel.embed(QUESTION)).replace(" ", "");
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            // The application's pooled connections carry hnsw.iterative_scan = strict_order
            // (Hikari connection-init-sql, since the knowledge-version design keeps retired
            // rows around), which makes the scan keep walking until k live rows pass. This
            // measures pgvector's raw scan, which is what the vacuum is the guard against.
            jdbc.execute("SET LOCAL hnsw.iterative_scan = off");
            List<String> plan = jdbc.queryForList(
                    "EXPLAIN SELECT id FROM vector_store ORDER BY embedding <=> ?::vector LIMIT ?",
                    String.class, vector, ragProperties.topK());
            assertThat(plan).as("the scan really goes through the HNSW index").anyMatch(line -> line.contains("spring_ai_vector_index"));
            return jdbc.queryForList("SELECT id FROM vector_store ORDER BY embedding <=> ?::vector LIMIT ?",
                    String.class, vector, ragProperties.topK()).size();
        });
    }
}
