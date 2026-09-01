package dev.merlionos.customerservice.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FaqDocumentReaderTest {

    private final List<Document> documents = new FaqDocumentReader(
            new ClassPathResource("faq/faq.json"), new ObjectMapper()).get();

    @Test
    @DisplayName("every corpus entry becomes exactly one document")
    void readsOneDocumentPerEntry() {
        assertThat(documents).hasSize(18);
        assertThat(documents).extracting(Document::getId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("document ids are derived from entry ids, so re-ingestion is deterministic")
    void idsAreStableAndTraceable() {
        assertThat(documents).extracting(Document::getId).contains("faq:returns-window");
    }

    @Test
    @DisplayName("both the question and the answer are embedded")
    void textCarriesQuestionAndAnswer() {
        Document returnsWindow = documents.stream()
                .filter(document -> "faq:returns-window".equals(document.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(returnsWindow.getText())
                .contains("How long do I have to return an item?")
                .contains("within 30 days of delivery");
    }

    @Test
    @DisplayName("metadata carries what filtering and citation need")
    void metadataIsPopulated() {
        Document returnsWindow = documents.stream()
                .filter(document -> "faq:returns-window".equals(document.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(returnsWindow.getMetadata())
                .containsEntry(FaqDocumentReader.METADATA_SOURCE, "faq")
                .containsEntry(FaqDocumentReader.METADATA_ENTRY_ID, "returns-window")
                .containsEntry(FaqDocumentReader.METADATA_CATEGORY, "returns")
                .containsKey(FaqDocumentReader.METADATA_VERSION);
    }
}
