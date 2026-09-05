package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The corpus must never be momentarily absent.
 *
 * <p>Deleting before writing was the original design, and on one machine it is invisible: the
 * gap is milliseconds and nothing reads during startup. The Kubernetes manifest runs two
 * replicas against one database, so during a rolling deploy the replica still serving traffic
 * retrieves from the same rows the starting one has just deleted — and if embedding or
 * insertion then fails, it stays empty while the old replica keeps answering.
 *
 * <p>Asserting on call order is the direct test of that. Counting rows afterwards would pass
 * for either ordering.
 */
class FaqIngestionOrderTest {

    @Test
    @DisplayName("documents are written before anything is deleted")
    void writesBeforeItDeletes() {
        RecordingVectorStore store = new RecordingVectorStore();
        FaqIngestionService service = new FaqIngestionService(store, new DefaultResourceLoader(),
                new ObjectMapper(),
                new RagProperties("classpath:/faq/faq.json", false, 4, 0.5, "query: ", "passage: "));

        int written = service.ingest();

        assertThat(written).isEqualTo(36);
        assertThat(store.calls)
                .as("delete-then-add leaves a window with no corpus at all")
                .containsExactly("add", "delete");
    }

    @Test
    @DisplayName("deletion is scoped to older corpus versions, not to everything this source owns")
    void deletesOnlyStaleVersions() {
        RecordingVectorStore store = new RecordingVectorStore();
        FaqIngestionService service = new FaqIngestionService(store, new DefaultResourceLoader(),
                new ObjectMapper(),
                new RagProperties("classpath:/faq/faq.json", false, 4, 0.5, "query: ", "passage: "));

        service.ingest();

        // A wholesale "source == faq" delete would also remove the rows just written, which is
        // what makes two replicas ingesting at once destroy each other's work.
        assertThat(store.deleteExpression).isNotNull();
        assertThat(store.deleteExpression.toString())
                .contains("corpus_version")
                .contains("NE");
    }

    /** Records what was called and in which order. */
    private static final class RecordingVectorStore implements VectorStore {

        private final List<String> calls = new ArrayList<>();
        private Filter.Expression deleteExpression;

        @Override
        public void add(List<Document> documents) {
            calls.add("add");
        }

        @Override
        public void delete(List<String> ids) {
            calls.add("delete-by-id");
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            calls.add("delete");
            this.deleteExpression = filterExpression;
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }
    }
}
