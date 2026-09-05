package dev.merlionos.customerservice;

import dev.merlionos.customerservice.chat.ChatService;
import dev.merlionos.customerservice.chat.TurnEvent;
import dev.merlionos.customerservice.chat.TurnEventBus;
import dev.merlionos.customerservice.clients.KnowledgeUnavailableException;
import dev.merlionos.customerservice.clients.RemoteKnowledgeVectorStore;
import dev.merlionos.customerservice.rag.api.KnowledgeSearch;
import dev.merlionos.customerservice.rag.api.Passage;
import dev.merlionos.customerservice.rag.api.RagProperties;
import dev.merlionos.customerservice.rag.api.SearchQuery;
import dev.merlionos.customerservice.ticket.api.TicketResult;
import dev.merlionos.customerservice.tools.SupportTicketTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.Mockito;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * The distributed topology, in one JVM with real sockets: a {@code knowledge} process, a
 * {@code ticket} process and a {@code chat} process, each started as the application starts
 * them, over one database. The claims are contract parity -- what the chat side gets through
 * the seams is what the local calls would have returned -- and that each role is only what it
 * is. Multi-replica concurrency and process death under load are the deployment phase's
 * separate-process tests; this covers the seams, not the fleet.
 *
 * <p>Ordered, because the last two tests stop the downstream processes.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TopologyParityTest {

    static final String TOKEN = "topology-test-token";

    static PostgreSQLContainer<?> postgres;
    static ConfigurableApplicationContext knowledge;
    static ConfigurableApplicationContext ticket;
    static ConfigurableApplicationContext chat;
    static AnthropicChatModel chatModel;

    @BeforeAll
    static void startTheThreeProcesses() {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
        postgres.start();

        // Neither of these gets the test profile, so neither has an LLM key of any kind.
        knowledge = role("knowledge", "--app.rag.import-mode=startup");
        ticket = role("ticket");

        chatModel = Mockito.mock(AnthropicChatModel.class);
        chat = new SpringApplicationBuilder(CustomerServiceApplication.class)
                .profiles("test")
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("anthropicChatModel", chatModel))
                .run(args("chat",
                        "--app.services.knowledge.url=http://localhost:" + port(knowledge),
                        "--app.services.ticket.url=http://localhost:" + port(ticket)));
    }

    @AfterAll
    static void stopEverything() {
        for (ConfigurableApplicationContext context : List.of(chat, ticket, knowledge)) {
            if (context != null && context.isActive()) {
                context.close();
            }
        }
        postgres.stop();
    }

    private static ConfigurableApplicationContext role(String target, String... extra) {
        return new SpringApplicationBuilder(CustomerServiceApplication.class).run(args(target, extra));
    }

    private static String[] args(String target, String... extra) {
        List<String> args = new java.util.ArrayList<>(List.of(
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

    private static RestClient http(ConfigurableApplicationContext context) {
        return RestClient.builder().baseUrl("http://localhost:" + port(context)).build();
    }

    // --- each role is only what it is ------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("a knowledge process has the model and the store, and no chat model")
    void knowledgeRoleIsOnlyKnowledge() {
        assertThat(knowledge.getBeanNamesForType(EmbeddingModel.class)).isNotEmpty();
        assertThat(knowledge.getBean(VectorStore.class).getClass().getSimpleName()).isEqualTo("PgVectorStore");
        assertThat(knowledge.getBeanNamesForType(ChatModel.class)).isEmpty();
        assertThat(knowledge.containsBean("chatController")).isFalse();
        assertThat(knowledge.containsBean("knowledgeController")).isTrue();
        assertThat(knowledge.containsBean("ticketController")).isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("a ticket process has neither a model nor a store, and starts without an LLM key")
    void ticketRoleIsOnlyTickets() {
        assertThat(ticket.getBeanNamesForType(EmbeddingModel.class)).isEmpty();
        assertThat(ticket.getBeanNamesForType(VectorStore.class)).isEmpty();
        assertThat(ticket.getBeanNamesForType(ChatModel.class)).isEmpty();
        assertThat(ticket.containsBean("ticketController")).isTrue();
        assertThat(ticket.containsBean("chatController")).isFalse();
        assertThat(ticket.getEnvironment().containsProperty("ANTHROPIC_API_KEY"))
                .as("no key was supplied, and the process did not ask for one")
                .isFalse();
    }

    @Test
    @Order(3)
    @DisplayName("a chat process holds no ONNX session and retrieves through the seam")
    void chatRoleHasNoLocalKnowledge() {
        assertThat(chat.getBeanNamesForType(EmbeddingModel.class)).isEmpty();
        assertThat(chat.getBean(VectorStore.class)).isInstanceOf(RemoteKnowledgeVectorStore.class);
        assertThat(chat.containsBean("chatController")).isTrue();
        assertThat(chat.containsBean("knowledgeController")).isFalse();
        assertThat(chat.containsBean("ticketController")).isFalse();
        assertThat(chat.containsBean("corpusImporter")).isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("readiness crosses the seam: chat is ready because knowledge has its corpus")
    void chatReadinessFollowsKnowledge() {
        var readiness = http(chat).get().uri("/actuator/health/readiness").retrieve().toEntity(String.class);

        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).contains("\"knowledge\"");
        assertThat(http(knowledge).get().uri("/actuator/health/readiness").retrieve().toEntity(String.class).getBody())
                .contains("\"documents\":36");
    }

    // --- the seams carry the same answers ----------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("internal endpoints refuse a call without the token, and take one with it")
    void internalEndpointsRequireTheToken() {
        HttpStatusCode knowledgeWithout = http(knowledge).post().uri("/internal/v1/knowledge/search")
                .body(new SearchQuery("anything", 1, 0))
                .exchange((request, response) -> response.getStatusCode());
        HttpStatusCode ticketWithout = http(ticket).get().uri("/internal/v1/tickets?conversationId=x")
                .exchange((request, response) -> response.getStatusCode());
        HttpStatusCode ticketWrong = http(ticket).get().uri("/internal/v1/tickets?conversationId=x")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                .exchange((request, response) -> response.getStatusCode());
        assertThat(knowledgeWithout).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ticketWithout).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ticketWrong).isEqualTo(HttpStatus.UNAUTHORIZED);

        var withToken = http(ticket).get().uri("/internal/v1/tickets?conversationId=x")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN).retrieve().toEntity(String.class);
        assertThat(withToken.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(6)
    @DisplayName("what the chat side retrieves through the seam is what knowledge would retrieve locally")
    void retrievalParity() {
        String question = "my parcel showed up broken";
        RagProperties rag = knowledge.getBean(RagProperties.class);
        List<Passage> local = knowledge.getBean(KnowledgeSearch.class)
                .search(new SearchQuery(question, rag.topK(), rag.similarityThreshold()));

        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Sorry to hear that."))))));
        List<TurnEvent> events = chat.getBean(ChatService.class).stream("parity-conversation", question)
                .collectList().block();

        TurnEvent.Retrieval retrieval = events.stream()
                .filter(TurnEvent.Retrieval.class::isInstance).map(TurnEvent.Retrieval.class::cast)
                .findFirst().orElseThrow();
        assertThat(retrieval.passages()).hasSize(local.size()).hasSize(rag.topK());
        assertThat(retrieval.passages()).extracting(TurnEvent.Passage::entryId)
                .containsExactlyElementsOf(local.stream().map(p -> String.valueOf(p.metadata().get("entry_id"))).toList());
        assertThat(retrieval.passages()).extracting(TurnEvent.Passage::score)
                .containsExactlyElementsOf(local.stream().map(Passage::score).toList());
        assertThat(retrieval.passages().getFirst().entryId()).isEqualTo("returns-damaged");
    }

    @Test
    @Order(7)
    @DisplayName("a ticket raised through the seam is a row, deduplicated and capped by the ticket process")
    void ticketParity() {
        SupportTicketTools tools = chat.getBean(SupportTicketTools.class);
        JdbcTemplate ticketDb = ticket.getBean(JdbcTemplate.class);
        String conversation = "seam-conversation";
        ToolContext context = new ToolContext(Map.of(
                SupportTicketTools.CONVERSATION_ID_KEY, conversation, TurnEventBus.TURN_ID_KEY, "turn-1"));

        TicketResult created = tools.createSupportTicket("Parcel arrived crushed", "returns", "ORD-10042", context);
        TicketResult duplicate = tools.createSupportTicket("parcel ARRIVED crushed", "returns", "ORD-10042", context);
        tools.createSupportTicket("Second problem", "other", null, context);
        tools.createSupportTicket("Third problem", "other", null, context);
        TicketResult refused = tools.createSupportTicket("Fourth problem", "other", null, context);

        assertThat(created.status()).isEqualTo(TicketResult.Status.CREATED);
        assertThat(duplicate.status()).isEqualTo(TicketResult.Status.EXISTING);
        assertThat(duplicate.ticket().ticketNumber()).isEqualTo(created.ticket().ticketNumber());
        assertThat(refused.status()).isEqualTo(TicketResult.Status.REFUSED);
        assertThat(ticketDb.queryForObject(
                "SELECT count(*) FROM support_ticket WHERE conversation_id = ?", Integer.class, conversation))
                .isEqualTo(3);
        assertThat(ticketDb.queryForObject(
                "SELECT count(*) FROM ticket_operation WHERE conversation_id = ?", Integer.class, conversation))
                .as("every attempt, including the refused one, was recorded")
                .isEqualTo(5);
    }

    @Test
    @Order(8)
    @DisplayName("a request built from the chat process's client builder carries the trace across the seam")
    void traceCrossesTheSeam() throws Exception {
        // The internal clients are built from the auto-configured RestClient.Builder, which is
        // what carries the observation customiser; RestClient.create() would silently drop the
        // header. A probe server records what arrives.
        java.util.concurrent.atomic.AtomicReference<String> traceparent = new java.util.concurrent.atomic.AtomicReference<>();
        com.sun.net.httpserver.HttpServer probe = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("localhost", 0), 0);
        probe.createContext("/", exchange -> {
            traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        probe.start();
        try {
            RestClient client = chat.getBean(RestClient.Builder.class)
                    .baseUrl("http://localhost:" + probe.getAddress().getPort()).build();
            client.get().uri("/internal/v1/probe").retrieve().toBodilessEntity();
        }
        finally {
            probe.stop(0);
        }

        assertThat(traceparent.get()).as("W3C traceparent header").matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    }

    // --- when a downstream process is gone ---------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("with the ticket process gone, the tool returns an unavailable value, never an exception")
    void ticketProcessGone() {
        ticket.close();
        SupportTicketTools tools = chat.getBean(SupportTicketTools.class);
        ToolContext context = new ToolContext(Map.of(
                SupportTicketTools.CONVERSATION_ID_KEY, "orphan-conversation", TurnEventBus.TURN_ID_KEY, "turn-2"));

        TicketResult result = tools.createSupportTicket("Anything", "other", null, context);

        assertThat(result.status()).isEqualTo(TicketResult.Status.UNAVAILABLE);
        assertThat(result.created()).isFalse();
        assertThat(result.explanation()).contains("human agent");
    }

    @Test
    @Order(10)
    @DisplayName("with the knowledge process gone, a turn fails rather than answering ungrounded, and chat is not ready")
    void knowledgeProcessGone() {
        knowledge.close();
        given(chatModel.call(any(Prompt.class))).willReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Should never be reached.")))));

        assertThatThrownBy(() -> chat.getBean(ChatService.class).ask("grounding-conversation", "how much is delivery"))
                .isInstanceOf(KnowledgeUnavailableException.class);
        assertThatThrownBy(() -> http(chat).get().uri("/actuator/health/readiness").retrieve().toBodilessEntity())
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.ServiceUnavailable.class);
    }
}
