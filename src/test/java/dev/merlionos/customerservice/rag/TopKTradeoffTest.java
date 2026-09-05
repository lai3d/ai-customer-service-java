package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.RagProperties;
import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

/**
 * How many passages are worth retrieving?
 *
 * <p>The relevant and irrelevant passages for a multi-intent question sit within about 0.03 of
 * each other, so which one ranks first is close to noise. Retrieving more is the cheap answer;
 * the question is what it costs. Passages are short, so this measures both sides.
 */
@SpringBootTest(properties = "app.rag.import-mode=startup")
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class TopKTradeoffTest {

    @Autowired VectorStore vectorStore;
    @Autowired RagProperties ragProperties;

    private static final List<String[]> CASES = List.of(
            new String[]{"我的订单 ORD-10045 退货退款一直没到账，我要找人工客服处理", "returns-refund-timing"},
            new String[]{"订单 ORD-10042 我想退掉，请问多久能收到退款", "returns-refund-timing"},
            new String[]{"你好，我上周买的耳机想退，另外我的付款方式能不能换成 PayPal", "returns-how"},
            new String[]{"我昨天下的单还没发货，能不能顺便帮我把收货地址改到公司", "shipping-address-change"},
            new String[]{"Hi, I ordered ORD-10042 last week and it still hasn't arrived, how do I track it",
                    "shipping-tracking"},
            new String[]{"My card got declined twice and now I see two charges on my statement, what do I do",
                    "payment-double-charge"},
            new String[]{"I want to return the headphones I bought, how long does the refund take to reach my bank",
                    "returns-refund-timing"},
            new String[]{"忘记密码了登录不上，而且我想看看两年前的订单记录", "account-password"},
            new String[]{"运费多少钱", "shipping-cost"},
            new String[]{"how much do I pay for delivery", "shipping-cost"},
            new String[]{"包裹到的时候是坏的", "returns-damaged"},
            new String[]{"when can I talk to a real person", "support-hours"},
            new String[]{"能寄到国外吗", "shipping-international"},
            new String[]{"can I get a different size instead", "returns-exchange"});

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the configured topK still answers what it was chosen to answer")
    void configuredTopKHoldsItsRecall() {
        int hits = 0;
        for (String[] testCase : CASES) {
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(testCase[0]).topK(ragProperties.topK())
                    .similarityThreshold(ragProperties.similarityThreshold()).build());
            if (docs.stream().anyMatch(d ->
                    testCase[1].equals(d.getMetadata().get(FaqDocumentReader.METADATA_ENTRY_ID)))) {
                hits++;
            }
        }
        // 13 of 14 at topK 8. The remaining case is a documented limit, not a regression --
        // see docs/retrieval.md. Lowering topK silently gives up recall this measured.
        org.assertj.core.api.Assertions.assertThat(hits)
                .as("multi-intent recall at the configured topK")
                .isGreaterThanOrEqualTo(13);
    }

    @Test
    void measure() {
        System.out.printf("### %5s %10s %14s %16s%n", "topK", "recall", "chars/turn", "~tokens/turn");
        for (int topK : new int[]{4, 6, 8, 10, 13, 16}) {
            int hits = 0;
            long chars = 0;
            for (String[] testCase : CASES) {
                List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(testCase[0]).topK(topK).similarityThresholdAll().build());
                chars += docs.stream().mapToLong(d -> d.getText().length()).sum();
                if (docs.stream().anyMatch(d ->
                        testCase[1].equals(d.getMetadata().get(FaqDocumentReader.METADATA_ENTRY_ID)))) {
                    hits++;
                }
            }
            long avgChars = chars / CASES.size();
            // Mixed English and Chinese; roughly 2.5 characters per token across this corpus.
            System.out.printf("### %5d %7d/%-2d %14d %16d%n",
                    topK, hits, CASES.size(), avgChars, Math.round(avgChars / 2.5));
        }
    }
}
