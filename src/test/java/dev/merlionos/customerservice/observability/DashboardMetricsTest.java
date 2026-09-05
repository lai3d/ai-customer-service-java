package dev.merlionos.customerservice.observability;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.chat.ChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * A dashboard panel whose metric the application does not emit shows "No data" and looks
 * like a quiet system. This reads every PromQL expression in the provisioned dashboards,
 * pulls the metric names out of them, drives one blocking turn and one streamed turn through
 * the real pipeline, and asserts each name appears in {@code /actuator/prometheus} -- with
 * buckets where a panel takes a percentile, since a summary has none.
 *
 * <p>Metrics that other components produce -- Tempo's span-derived series, Prometheus's own
 * {@code up}, Loki queries -- are listed as such and skipped; the Compose smoke test covers
 * those where they are made.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.import-mode=startup")
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
@AutoConfigureObservability
class DashboardMetricsTest {

    private static final List<Path> DASHBOARDS = List.of(
            Path.of("observability/grafana/dashboards/customer-service.json"),
            Path.of("observability/grafana/dashboards/customer-service-roles.json"));

    /** Produced by something other than this application. */
    private static final Set<String> NOT_OURS = Set.of(
            "up", "traces_service_graph_request_total", "tempo_distributor_spans_received_total");

    /**
     * Exists only once a model without a configured price has been called: its label is the
     * model id, and pre-registering it would mean inventing one. The panel that shows it says
     * {@code or vector(0)} and the alert is {@code increase(...) > 0}, both absent-safe.
     */
    private static final Set<String> ONLY_WHEN_IT_HAPPENS = Set.of("chat_unpriced_model_calls_total");

    private static final Pattern METRIC = Pattern.compile(
            "\\b([a-z][a-z0-9_]*_(?:total|bucket|count|sum|max|bytes|threads|usage|connections_active|seconds))\\b");

    private static final Pattern EXPR = Pattern.compile("\"expr\": \"((?:[^\"\\\\]|\\\\.)*)\"");

    private static List<String> promqlExpressions(String dashboardJson) {
        return EXPR.matcher(dashboardJson).results().map(r -> r.group(1)).toList();
    }

    @Autowired ChatService chatService;
    @Autowired TestRestTemplate rest;
    @MockitoBean AnthropicChatModel chatModel;

    @Test
    @DisplayName("every metric the dashboards reference is emitted, and the percentile ones as histograms")
    void everyDashboardMetricExists() throws IOException {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("Standard shipping is free over $50."))),
                ChatResponseMetadata.builder().model("claude-opus-5").usage(new DefaultUsage(1200, 40)).build());
        given(chatModel.call(any(Prompt.class))).willReturn(response);
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(response));

        chatService.ask("dashboard-blocking", "How much is delivery?");
        chatService.stream("dashboard-streaming", "运费多少钱").blockLast();
        rest.getForEntity("/api/v1/chat", String.class); // a 4xx on the public endpoint too

        String exposition = rest.getForObject("/actuator/prometheus", String.class);
        Set<String> exposed = new TreeSet<>();
        for (String line : exposition.split("\n")) {
            if (!line.startsWith("#") && !line.isBlank()) {
                exposed.add(line.substring(0, Math.max(line.indexOf('{'), line.indexOf(' ')) < 0
                        ? line.length() : (line.indexOf('{') > 0 ? line.indexOf('{') : line.indexOf(' '))));
            }
        }

        // Only the PromQL, not the panel descriptions, which name metrics in prose.
        Set<String> referenced = new TreeSet<>();
        for (Path dashboard : DASHBOARDS) {
            for (String expr : promqlExpressions(Files.readString(dashboard))) {
                Matcher matcher = METRIC.matcher(expr);
                while (matcher.find()) {
                    referenced.add(matcher.group(1));
                }
            }
        }
        referenced.removeAll(NOT_OURS);
        referenced.removeAll(ONLY_WHEN_IT_HAPPENS);
        // The regex also matches a shorter prefix of a name it already matched in full.
        referenced.removeIf(name -> Stream.of("_total", "_bucket", "_count", "_sum", "_max")
                .anyMatch(suffix -> referenced.contains(name + suffix)));

        assertThat(referenced).as("sanity: the dashboards reference metrics").hasSizeGreaterThan(10);
        assertThat(exposed)
                .as("referenced by a dashboard but not emitted after a blocking and a streamed turn")
                .containsAll(referenced);
        assertThat(exposed)
                .as("percentile panels need buckets, not summaries")
                .contains("http_server_requests_seconds_bucket", "http_client_requests_seconds_bucket",
                        "gen_ai_client_operation_seconds_bucket", "db_vector_client_operation_seconds_bucket");
    }
}
