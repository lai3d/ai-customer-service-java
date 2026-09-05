package dev.merlionos.customerservice.clients;

import dev.merlionos.customerservice.internal.InternalProperties;
import dev.merlionos.customerservice.rag.api.KnowledgeAdmin;
import dev.merlionos.customerservice.rag.api.KnowledgeSearch;
import dev.merlionos.customerservice.target.ConditionalOnTarget;
import dev.merlionos.customerservice.target.DeploymentTarget;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import dev.merlionos.customerservice.ticket.api.TicketWorkflow;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * How a process that is exactly {@code chat} reaches the other two roles. Absent from
 * {@code all}, where the local implementations are wired instead, and from the other roles,
 * which have no chat side.
 *
 * <p>Clients are built from the auto-configured {@link RestClient.Builder}, not
 * {@code RestClient.create()}: the builder carries the observation customiser that propagates
 * the trace, so a tool span in Jaeger has a child in the ticket service.
 * {@code TopologyParityTest} asserts that a request built from it carries {@code traceparent}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnTarget(value = DeploymentTarget.CHAT, exclusive = true)
public class RemoteClientsConfiguration {

    @Bean
    RestClient knowledgeClient(RestClient.Builder builder, ServicesProperties services, InternalProperties internal) {
        return internalClient(builder, services.knowledge().url(), services.timeoutOrDefault(), internal.token());
    }

    @Bean
    RestClient ticketClient(RestClient.Builder builder, ServicesProperties services, InternalProperties internal) {
        return internalClient(builder, services.ticket().url(), services.timeoutOrDefault(), internal.token());
    }

    private static RestClient internalClient(RestClient.Builder builder, String baseUrl,
                                             java.time.Duration timeout, String token) {
        return builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(timeout)
                                .withReadTimeout(timeout)))
                .build();
    }

    @Bean
    KnowledgeSearch knowledgeSearch(RestClient knowledgeClient, MeterRegistry meterRegistry) {
        return new HttpKnowledgeSearch(knowledgeClient, meterRegistry);
    }

    /** The operations admin's side of the knowledge seam. */
    @Bean
    KnowledgeAdmin knowledgeAdmin(RestClient knowledgeClient) {
        return new HttpKnowledgeAdmin(knowledgeClient);
    }

    /** The bean {@code QuestionAnswerAdvisor} is built on; pgvector is switched off in this role. */
    @Bean
    VectorStore vectorStore(KnowledgeSearch knowledgeSearch) {
        return new RemoteKnowledgeVectorStore(knowledgeSearch);
    }

    @Bean
    TicketOperations ticketOperations(RestClient ticketClient) {
        return new HttpTicketOperations(ticketClient);
    }

    /** The operations admin's side of the same seam. */
    @Bean
    TicketWorkflow ticketWorkflow(RestClient ticketClient) {
        return new HttpTicketWorkflow(ticketClient);
    }

    @Bean("knowledge")
    HealthIndicator knowledgeReadiness(RestClient knowledgeClient) {
        return new KnowledgeReadinessIndicator(knowledgeClient);
    }
}
