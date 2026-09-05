package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.RagProperties;
import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Does query expansion earn the extra model call it costs?
 *
 * <p>Multi-intent questions are where plain single-vector retrieval is weakest: one embedding
 * lands between topics and the passage that answers one of them can fall out of the window
 * entirely. Expansion asks the model to split the question first, then retrieves for each part.
 *
 * <p>Tagged {@code benchmark} because it calls the live API — one expansion per case — so it is
 * excluded from the normal build and needs a real key:
 *
 * <pre>./mvnw test -Dexcluded.test.groups= -Dtest=QueryExpansionComparisonTest</pre>
 */
@Tag("benchmark")
@SpringBootTest(properties = "app.rag.ingest-on-startup=true")
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class QueryExpansionComparisonTest {

    private static final int TOP_K = 4;

    @Autowired org.springframework.ai.vectorstore.VectorStore vectorStore;
    @Autowired RagProperties ragProperties;
    @Autowired ChatClient.Builder chatClientBuilder;

    /** query | the entry that answers it */
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
            new String[]{"how much do I pay for delivery", "shipping-cost"});

    @Test
    void compare() {
        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(TOP_K)
                .similarityThreshold(ragProperties.similarityThreshold())
                .build();
        // The default template does not survive contact with this model: MultiQueryExpander
        // requires the response to split into exactly `numberOfQueries` lines, and anything
        // else -- a blank line between variants, a trailing newline, a numbered list -- makes
        // it discard the expansion and silently return the original query. With the shipped
        // template that happened on 10 of 10 cases. This one leaves no room for formatting.
        MultiQueryExpander expander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .promptTemplate(new org.springframework.ai.chat.prompt.PromptTemplate("""
                        Split the customer message into {number} standalone search queries,
                        one for each distinct thing they are asking about. If they ask about
                        fewer things, restate the same need in different words to fill the
                        quota. Write each query in the language of the original.

                        Output format, followed exactly:
                        - exactly {number} lines
                        - one query per line
                        - no blank lines, no numbering, no leading punctuation
                        - no explanation before or after

                        Customer message: {query}
                        """))
                .numberOfQueries(3)
                .includeOriginal(true)
                .build();
        ConcatenationDocumentJoiner joiner = new ConcatenationDocumentJoiner();

        int plainHits = 0, expandedHits = 0;
        long plainMillis = 0, expandedMillis = 0;

        for (String[] testCase : CASES) {
            Query query = new Query(testCase[0]);
            String expected = testCase[1];

            long t0 = System.currentTimeMillis();
            boolean plain = contains(retriever.retrieve(query), expected, TOP_K);
            plainMillis += System.currentTimeMillis() - t0;

            t0 = System.currentTimeMillis();
            List<Query> expanded = expander.expand(query);
            Map<Query, List<List<Document>>> retrieved = new LinkedHashMap<>();
            for (Query sub : expanded) {
                retrieved.put(sub, List.of(retriever.retrieve(sub)));
            }
            List<Document> joined = joiner.join(retrieved);
            expandedMillis += System.currentTimeMillis() - t0;

            boolean withExpansion = contains(joined, expected, TOP_K);
            if (plain) plainHits++;
            if (withExpansion) expandedHits++;

            System.out.printf("### %-8s %-10s want=%-22s subqueries=%d%n    q=%s%n",
                    plain ? "hit" : "MISS", withExpansion ? "hit" : "MISS",
                    expected, expanded.size(), testCase[0]);
        }

        System.out.printf("### === plain    recall@%d = %d/%d   %d ms total%n",
                TOP_K, plainHits, CASES.size(), plainMillis);
        System.out.printf("### === expanded recall@%d = %d/%d   %d ms total (%d ms/query)%n",
                TOP_K, expandedHits, CASES.size(), expandedMillis, expandedMillis / CASES.size());
    }

    private static boolean contains(List<Document> documents, String entryId, int limit) {
        return documents.stream().limit(limit)
                .anyMatch(d -> entryId.equals(d.getMetadata().get(FaqDocumentReader.METADATA_ENTRY_ID)));
    }
}
