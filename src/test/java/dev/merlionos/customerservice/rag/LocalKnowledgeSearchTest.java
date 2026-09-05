package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.rag.api.KnowledgeSearch;
import dev.merlionos.customerservice.rag.api.Passage;
import dev.merlionos.customerservice.rag.api.RagProperties;
import dev.merlionos.customerservice.rag.api.SearchQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The knowledge seam against the real embedding model and a real pgvector, because this is
 * what a {@code knowledge} process will serve and what a {@code chat} process will retrieve
 * through. The claim is parity: a search through the seam returns the passages, in the
 * order, with the scores, that {@code QuestionAnswerAdvisor}'s own request would.
 */
@SpringBootTest(properties = "app.rag.ingest-on-startup=true")
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class LocalKnowledgeSearchTest {

    @Autowired KnowledgeSearch knowledgeSearch;
    @Autowired VectorStore vectorStore;
    @Autowired RagProperties ragProperties;

    @Test
    @DisplayName("the seam retrieves what the advisor would, in the advisor's order")
    void matchesTheAdvisorsOwnSearch() {
        String question = "my parcel showed up broken";

        List<Document> direct = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(ragProperties.topK())
                .similarityThreshold(ragProperties.similarityThreshold())
                .build());
        List<Passage> viaSeam = knowledgeSearch.search(
                new SearchQuery(question, ragProperties.topK(), ragProperties.similarityThreshold()));

        assertThat(viaSeam).hasSize(direct.size()).hasSize(ragProperties.topK());
        assertThat(viaSeam).extracting(Passage::id).containsExactlyElementsOf(direct.stream().map(Document::getId).toList());
        assertThat(viaSeam).extracting(Passage::score).containsExactlyElementsOf(direct.stream().map(Document::getScore).toList());
        assertThat(viaSeam.getFirst().metadata()).containsEntry(FaqDocumentReader.METADATA_ENTRY_ID, "returns-damaged");
        assertThat(viaSeam.getFirst().text()).isEqualTo(direct.getFirst().getText());
    }

    @Test
    @DisplayName("a Chinese question comes back with Chinese passages, the same as the direct path")
    void crossLanguageParity() {
        String question = "包裹到了但是摔坏了";

        List<Passage> viaSeam = knowledgeSearch.search(new SearchQuery(question, 3, 0));

        assertThat(viaSeam).hasSize(3);
        assertThat(viaSeam.getFirst().metadata()).containsEntry(FaqDocumentReader.METADATA_ENTRY_ID, "returns-damaged");
        assertThat(viaSeam.getFirst().id()).endsWith(":zh");
    }
}
