package dev.merlionos.customerservice.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Reads the FAQ corpus into Spring AI {@link Document}s, one per question-and-answer pair.
 *
 * <p>No {@code TextSplitter} sits behind this reader, and that is deliberate. Splitters exist
 * to cut long prose into retrievable pieces; an FAQ entry is already the unit a customer's
 * question should match, and splitting one would separate a question from its answer or strand
 * half an answer in a chunk of its own. A corpus of long-form policy documents would need a
 * splitter here.
 *
 * <p>Both the question and the answer are embedded. Embedding the question alone matches the
 * phrasing of incoming queries most closely, but loses recall whenever a customer describes a
 * situation in the answer's vocabulary rather than the question's.
 */
class FaqDocumentReader implements DocumentReader {

    static final String SOURCE = "faq";

    static final String METADATA_SOURCE = "source";
    static final String METADATA_ENTRY_ID = "entry_id";
    static final String METADATA_CATEGORY = "category";
    static final String METADATA_QUESTION = "question";
    static final String METADATA_VERSION = "corpus_version";

    private final Resource corpus;
    private final ObjectMapper objectMapper;

    FaqDocumentReader(Resource corpus, ObjectMapper objectMapper) {
        this.corpus = corpus;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Document> get() {
        FaqCorpus parsed = parse();

        Assert.notEmpty(parsed.entries(), "FAQ corpus contains no entries: " + corpus.getDescription());

        return parsed.entries().stream()
                .map(entry -> toDocument(entry, parsed.version()))
                .toList();
    }

    private FaqCorpus parse() {
        try (InputStream in = corpus.getInputStream()) {
            return objectMapper.readValue(in, FaqCorpus.class);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read FAQ corpus " + corpus.getDescription(), e);
        }
    }

    private static Document toDocument(FaqEntry entry, String version) {
        String text = "Q: %s%nA: %s".formatted(entry.question(), entry.answer());

        Map<String, Object> metadata = Map.of(
                METADATA_SOURCE, SOURCE,
                METADATA_ENTRY_ID, entry.id(),
                METADATA_CATEGORY, entry.category(),
                METADATA_QUESTION, entry.question(),
                METADATA_VERSION, version);

        // A stable id keeps re-ingestion deterministic and makes a document traceable back
        // to the corpus entry it came from.
        return new Document(SOURCE + ":" + entry.id(), text, metadata);
    }
}
