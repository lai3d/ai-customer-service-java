package dev.merlionos.customerservice.benchmark;

import dev.merlionos.customerservice.CustomerServiceApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * The same load as {@link VirtualThreadBenchmark} -- a thousand concurrent requests against a
 * one-second stubbed model, on the production path -- with the chat role talking to knowledge
 * and ticket over HTTP instead of in-process. What changes per request is one retrieval call
 * across a socket to a knowledge process that embeds the query and searches pgvector, and
 * the lease and budget rows as before. Tickets are not on this path: the stubbed model calls
 * no tool.
 *
 * <p>The three roles share this JVM, as they do in {@code TopologyParityTest}. The worker
 * column is filtered to the chat server's Tomcat connector so it counts what a chat pod would hold; the
 * whole-JVM platform-thread column includes the co-located knowledge and ticket servers and
 * the load driver, so it is reported but is not the chat pod's number -- the kind harness
 * measured the roles' memory separately for that reason.
 *
 * <pre>./mvnw test -Dexcluded.test.groups= -Dtest='ServicesTopologyBenchmark*'</pre>
 */
@Tag("benchmark")
class ServicesTopologyBenchmark {

    private static final int CONCURRENCY = 1000;
    private static final long MODEL_DELAY_MILLIS = 1000;
    private static final String TOKEN = "benchmark-token";

    private static final Map<String, LoadDriver.Result> RESULTS = new LinkedHashMap<>();
    private static final Map<String, Integer> PEAK_WORKERS = new LinkedHashMap<>();
    private static final Map<String, Integer> PEAK_PLATFORM = new LinkedHashMap<>();

    static PostgreSQLContainer<?> postgres;
    static ConfigurableApplicationContext knowledge;
    static ConfigurableApplicationContext ticket;

    @BeforeAll
    static void startDownstreamRoles() {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
        postgres.start();
        knowledge = role("knowledge", "--app.rag.import-mode=startup");
        ticket = role("ticket");
    }

    @AfterAll
    static void stopEverythingAndReport() {
        for (ConfigurableApplicationContext context : List.of(ticket, knowledge)) {
            if (context != null && context.isActive()) {
                context.close();
            }
        }
        postgres.stop();

        if (RESULTS.isEmpty()) {
            return;
        }
        System.out.printf("%n### services topology: %d concurrent requests, %d ms stubbed model delay%n",
                CONCURRENCY, MODEL_DELAY_MILLIS);
        System.out.printf("### %-10s %9s %8s %7s %7s %7s %14s %16s%n",
                "threads", "wall(ms)", "req/s", "p50", "p95", "p99",
                "chat workers", "jvm platform*");
        RESULTS.forEach((label, r) -> System.out.printf(
                "### %-10s %9d %8.1f %7d %7d %7d %14d %16d%n",
                label, r.wallMillis(), r.throughputPerSecond(), r.p50(), r.p95(), r.p99(),
                PEAK_WORKERS.get(label), PEAK_PLATFORM.get(label)));
        System.out.println("### * the JVM also hosts the knowledge and ticket servers and the load driver");
    }

    private static ConfigurableApplicationContext role(String target, String... extra) {
        return new SpringApplicationBuilder(CustomerServiceApplication.class).run(args(target, extra));
    }

    private static String[] args(String target, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--app.target=" + target,
                "--app.internal.token=" + TOKEN,
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword()));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private static ChatResponse cannedResponse() {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("Standard shipping is free over $50."))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1200, 40)).build());
    }

    private static void measure(String label, boolean virtualThreads) throws Exception {
        AnthropicChatModel chatModel = Mockito.mock(AnthropicChatModel.class);
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
            Thread.sleep(MODEL_DELAY_MILLIS);
            return cannedResponse();
        });
        ConfigurableApplicationContext chat = new SpringApplicationBuilder(CustomerServiceApplication.class)
                .profiles("test")
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("anthropicChatModel", chatModel))
                .run(args("chat",
                        "--spring.threads.virtual.enabled=" + virtualThreads,
                        "--app.services.knowledge.url=http://localhost:" + port(knowledge),
                        "--app.services.ticket.url=http://localhost:" + port(ticket)));
        try {
            int port = port(chat);
            // AbstractProtocol.getName() is `"http-nio-auto-3-50426"` -- quoted, and with the
            // bound port appended -- while the worker threads are `http-nio-auto-3-exec-N`. The
            // ProtocolHandler interface does not expose the name at all, and the first two
            // filters (by bound port, then by this name verbatim) each reported zero workers
            // under a thousand requests, which a platform-thread run cannot have.
            String connector = ((org.apache.coyote.AbstractProtocol<?>) ((TomcatWebServer)
                    ((WebServerApplicationContext) chat).getWebServer())
                    .getTomcat().getConnector().getProtocolHandler()).getName()
                    .replace("\"", "").replaceAll("-\\d+$", "");
            LoadDriver.Result result;
            try (ServerThreadSampler sampler = new ServerThreadSampler(connector)) {
                result = LoadDriver.run(port, CONCURRENCY, "How much is delivery?");
                PEAK_WORKERS.put(label, sampler.peakWorkers());
                PEAK_PLATFORM.put(label, sampler.peakPlatformThreads());
            }
            RESULTS.put(label, result);
            assertThat(result.failures())
                    .as("a failed request would make the timings meaningless")
                    .isZero();
        }
        finally {
            chat.close();
        }
    }

    @Test
    void measureVirtual() throws Exception {
        measure("virtual", true);
    }

    @Test
    void measurePlatform() throws Exception {
        measure("platform", false);
    }
}
