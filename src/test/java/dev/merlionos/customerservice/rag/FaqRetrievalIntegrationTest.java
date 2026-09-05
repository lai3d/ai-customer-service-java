package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.RagProperties;
import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality, measured against the real ONNX embedding model and a real pgvector
 * instance. No API key is involved: everything up to the model call is testable, and this is
 * where a silent regression -- a changed corpus, a different embedding model, a lost prefix --
 * would otherwise show up only as vaguer answers in production.
 *
 * <p>Queries deliberately avoid the corpus wording, in both languages. Matching a question to
 * its own text proves nothing about a customer describing a problem in their own words.
 */
@SpringBootTest(properties = "app.rag.ingest-on-startup=true")
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class FaqRetrievalIntegrationTest {

    @Autowired VectorStore vectorStore;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RagProperties ragProperties;
    @Autowired FaqIngestionService ingestionService;

    @ParameterizedTest(name = "[en] \"{0}\" retrieves {1}")
    @CsvSource(delimiter = '|', value = {
            "I want to send something back, is it too late after three weeks? | returns-window",
            "how much do I pay for delivery                                   | shipping-cost",
            "my card was rejected at checkout                                 | payment-declined",
            "when can I talk to a real person                                 | support-hours",
            "my parcel showed up broken                                       | returns-damaged",
            "can I get a different size instead                               | returns-exchange",
            "can I still change where it gets delivered                       | shipping-address-change",
            "I forgot my password                                             | account-password",
            "do you send orders overseas                                      | shipping-international",
            "I was billed twice                                               | payment-double-charge",
    })
    @DisplayName("an English paraphrase retrieves the right entry first")
    void retrievesForEnglish(String query, String expectedEntryId) {
        assertTopHit(query, expectedEntryId.trim());
    }

    @ParameterizedTest(name = "[zh] \"{0}\" retrieves {1}")
    @CsvSource(delimiter = '|', value = {
            "我想退货，过了三个星期还来得及吗 | returns-window",
            "运费多少钱                       | shipping-cost",
            "刷卡付款失败了                   | payment-declined",
            "怎么才能找到人工客服             | support-hours",
            "包裹到的时候是坏的               | returns-damaged",
            "下单之后还能改地址吗             | shipping-address-change",
            "密码忘了怎么办                   | account-password",
            "能寄到国外吗                     | shipping-international",
            "同一笔订单扣了两次钱             | payment-double-charge",
            "想换个大一号的                   | returns-exchange",
    })
    @DisplayName("a Chinese paraphrase retrieves the right entry first")
    void retrievesForChinese(String query, String expectedEntryId) {
        assertTopHit(query, expectedEntryId.trim());
    }

    @ParameterizedTest
    @ValueSource(strings = {"运费多少钱", "包裹到的时候是坏的", "密码忘了怎么办"})
    @DisplayName("a Chinese question is answered from the Chinese passage")
    void prefersSameLanguagePassage(String query) {
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query).topK(1).similarityThresholdAll().build());

        assertThat(hits.getFirst().getMetadata())
                .as("a same-language passage is a shorter distance for the model to travel")
                .containsEntry(FaqDocumentReader.METADATA_LANGUAGE, "zh");
    }

    @ParameterizedTest(name = "[zh->en] \"{0}\" finds {1}")
    @CsvSource(delimiter = '|', value = {
            "运费多少钱         | shipping-cost",
            "包裹到的时候是坏的 | returns-damaged",
            "密码忘了怎么办     | account-password",
            "能寄到国外吗       | shipping-international",
    })
    @DisplayName("a Chinese question finds the right English passage when only English exists")
    void retrievesCrossLingually(String query, String expectedEntryId) {
        // The real test of a multilingual model, and the reason it was worth switching. Note
        // that this cannot be observed on the full corpus: same-language matches score high
        // enough that all eighteen Chinese passages outrank every English one, so the English
        // half has to be isolated to see whether cross-lingual retrieval works at all.
        // It matters for any entry that has not been translated yet.
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(1)
                .similarityThresholdAll()
                .filterExpression(FaqDocumentReader.METADATA_LANGUAGE + " == 'en'")
                .build());

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().getMetadata())
                .containsEntry(FaqDocumentReader.METADATA_ENTRY_ID, expectedEntryId.trim());
    }

    @Test
    @DisplayName("relevant and off-topic scores overlap, so no threshold can separate them")
    void scoreDistributionsOverlap() {
        // This test used to assert the opposite, and passed -- on four hand-picked off-topic
        // questions. Widening the sample inverted it. The margin was never +0.006; with fifteen
        // off-topic questions and twelve degenerate inputs the populations overlap outright.
        // Pinning the real shape is what stops someone reintroducing a threshold that cannot
        // work, and what stops this test from being evidence for a claim that is false.
        double weakestRelevant = List.of(
                        "my parcel showed up broken", "I was billed twice", "包裹到的时候是坏的")
                .stream().mapToDouble(this::topScore).min().orElseThrow();

        double strongestOffTopic = List.of(
                        "who won the world cup in 2022", "how do I cook rice", "给我讲个笑话",
                        "明天天气怎么样", "你们公司多少人", "你们招聘工程师吗",
                        "你用的是什么模型", "今天股市怎么样")
                .stream().mapToDouble(this::topScore).max().orElseThrow();

        assertThat(strongestOffTopic)
                .as("an off-topic question outscores a real one, which is why relevance "
                        + "judgement lives in the system prompt and not in a threshold")
                .isGreaterThan(weakestRelevant);
    }

    @Test
    @DisplayName("even degenerate input scores highly, so the threshold is not a floor either")
    void degenerateInputIsNotFilteredOut() {
        // "。。。" scores 0.8417 against a corpus of shipping policies. A threshold low enough
        // to keep real questions cannot reject this, which is why the configured value is 0:
        // a number that filters nothing should not be dressed up as a filter.
        double strongestDegenerate = List.of("。。。", "...", "???", "aaaaaaa", "1234567890")
                .stream().mapToDouble(this::topScore).max().orElseThrow();

        assertThat(strongestDegenerate).isGreaterThan(0.8);
    }

    @Test
    @DisplayName("both languages of every entry are indexed")
    void indexesEveryLanguage() {
        assertThat(countDocuments())
                .as("18 entries in 2 languages")
                .isEqualTo(36);
    }

    @Test
    @DisplayName("re-ingesting replaces the corpus instead of duplicating it")
    void reingestionDoesNotDuplicate() {
        long before = countDocuments();

        ingestionService.ingest();
        ingestionService.ingest();

        assertThat(countDocuments()).isEqualTo(before);
    }

    private void assertTopHit(String query, String expectedEntryId) {
        List<Document> hits = search(query);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().getMetadata())
                .containsEntry(FaqDocumentReader.METADATA_ENTRY_ID, expectedEntryId);
    }

    private double topScore(String query) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query).topK(1).similarityThresholdAll().build())
                .getFirst().getScore();
    }

    private List<Document> search(String query) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(ragProperties.topK())
                .similarityThreshold(ragProperties.similarityThreshold())
                .build());
    }

    private long countDocuments() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Long.class);
    }
}
