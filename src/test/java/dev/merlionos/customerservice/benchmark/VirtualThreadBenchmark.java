package dev.merlionos.customerservice.benchmark;

import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Does the virtual-thread choice actually buy anything?
 *
 * <p>The project's brief specifies {@code spring.threads.virtual.enabled=true} and no WebFlux,
 * on the reasoning that an LLM call is a long blocking wait. That is a claim, and the repository
 * had no evidence for it. This runs the same real endpoint under the same load with the setting
 * on and off, and prints what happened.
 *
 * <p>The model is stubbed with a fixed delay: an LLM call is mostly waiting, and a real one
 * would add cost, network variance, and rate limits to a measurement that is about thread
 * scheduling. Everything else is the production path -- validation, chat memory in Postgres,
 * query embedding on the CPU, a pgvector search, the tool definitions, metrics and spans. That
 * makes the numbers honest rather than flattering: the retrieval work is real work, and it is
 * why the speedup is not the ratio the arithmetic alone suggests.
 *
 * <p>Thread counts come from {@link ServerThreadSampler}, which counts Tomcat's request-handling
 * platform threads while the load is in flight. Whole-JVM peak was tried first and was useless:
 * the load driver shares this JVM, so its own carrier threads landed in the same total and the
 * virtual run looked worse than the platform one.
 *
 * <pre>./mvnw test -Dgroups=benchmark -Dtest=VirtualThreadBenchmark</pre>
 */
@Tag("benchmark")
class VirtualThreadBenchmark {

    /** Concurrent in-flight requests. Well past Tomcat's default 200-thread pool. */
    private static final int CONCURRENCY = 1000;

    /** Stand-in for time spent waiting on the model. */
    private static final long MODEL_DELAY_MILLIS = 1000;

    private static final Map<String, LoadDriver.Result> RESULTS = new LinkedHashMap<>();
    private static final Map<String, Integer> PEAK_WORKERS = new LinkedHashMap<>();
    private static final Map<String, Integer> PEAK_PLATFORM = new LinkedHashMap<>();

    private static ChatResponse cannedResponse() {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("Standard shipping is free over $50."))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1200, 40)).build());
    }

    private static void measure(String label, int port) throws Exception {
        LoadDriver.Result result;
        try (ServerThreadSampler sampler = new ServerThreadSampler()) {
            result = LoadDriver.run(port, CONCURRENCY, "How much is delivery?");
            PEAK_WORKERS.put(label, sampler.peakWorkers());
            PEAK_PLATFORM.put(label, sampler.peakPlatformThreads());
        }
        RESULTS.put(label, result);

        assertThat(result.failures())
                .as("a failed request would make the timings meaningless")
                .isZero();
    }

    @AfterAll
    static void report() {
        if (RESULTS.isEmpty()) {
            return;
        }
        System.out.printf("%n### %d concurrent requests, %d ms stubbed model delay%n",
                CONCURRENCY, MODEL_DELAY_MILLIS);
        System.out.printf("### %-10s %9s %8s %7s %7s %7s %14s %16s%n",
                "threads", "wall(ms)", "req/s", "p50", "p95", "p99",
                "tomcat workers", "all platform");
        RESULTS.forEach((label, r) -> System.out.printf(
                "### %-10s %9d %8.1f %7d %7d %7d %14d %16d%n",
                label, r.wallMillis(), r.throughputPerSecond(), r.p50(), r.p95(), r.p99(),
                PEAK_WORKERS.get(label), PEAK_PLATFORM.get(label)));

        LoadDriver.Result virtual = RESULTS.get("virtual");
        LoadDriver.Result platform = RESULTS.get("platform");
        if (virtual != null && platform != null) {
            System.out.printf("### wall-clock ratio platform/virtual: %.2fx%n",
                    platform.wallMillis() / (double) virtual.wallMillis());
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "spring.threads.virtual.enabled=true")
    @Import(PostgresTestcontainer.class)
    @ActiveProfiles("test")
    @Tag("benchmark")
    // Without this the first server stays in Spring's context cache while the second runs, and
    // its idle 200-thread pool is counted against whichever measurement happens to run second.
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("virtual threads")
    class Virtual {

        @LocalServerPort int port;

        @MockitoBean AnthropicChatModel chatModel;

        @Test
        void measureVirtual() throws Exception {
            given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
                Thread.sleep(MODEL_DELAY_MILLIS);
                return cannedResponse();
            });

            measure("virtual", port);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "spring.threads.virtual.enabled=false")
    @Import(PostgresTestcontainer.class)
    @ActiveProfiles("test")
    @Tag("benchmark")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("platform threads")
    class Platform {

        @LocalServerPort int port;

        @MockitoBean AnthropicChatModel chatModel;

        @Test
        void measurePlatform() throws Exception {
            given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
                Thread.sleep(MODEL_DELAY_MILLIS);
                return cannedResponse();
            });

            measure("platform", port);
        }
    }
}
