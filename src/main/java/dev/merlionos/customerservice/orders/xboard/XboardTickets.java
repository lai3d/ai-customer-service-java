package dev.merlionos.customerservice.orders.xboard;

import dev.merlionos.customerservice.orders.ConnectorProperties;
import dev.merlionos.customerservice.orders.OrderConnector;
import dev.merlionos.customerservice.ticket.api.SupportTicket;
import dev.merlionos.customerservice.ticket.api.TicketResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A ticket raised in the tenant's panel, as the customer: {@code POST user/ticket/save}
 * with the customer's own token, so the ticket sits under their account and the panel's
 * staff answer it where they answer everything else. The panel says only "true"; the new
 * ticket is read back from {@code user/ticket/fetch}, newest first, for its number.
 *
 * <p>Failure is a value, as with every ticket path: a refused token or a panel that does
 * not answer is {@link TicketResult#unavailable()}, and the tool falls back to a ticket of
 * our own so the customer's request is never lost.
 */
@Component
public class XboardTickets {

    private static final Logger log = LoggerFactory.getLogger(XboardTickets.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    /** The panel's levels: 0 low, 1 medium, 2 high. A ticket the assistant could not settle is medium. */
    static final int LEVEL_MEDIUM = 1;
    public static final String NUMBER_PREFIX = "PANEL-";

    private final RestClient.Builder builder;
    private final ConnectorProperties properties;

    public XboardTickets(RestClient.Builder builder, ConnectorProperties properties) {
        this.builder = builder;
        this.properties = properties;
    }

    public TicketResult create(OrderConnector connector, String customerToken, String conversationId, String summary, String category,
                               String orderNumber) {
        try {
            RestClient client = client(connector, customerToken);
            String subject = category == null || category.isBlank() ? "Support request" : capitalise(category) + ": " + shorten(summary, 60);
            String message = summary + (orderNumber == null || orderNumber.isBlank() ? "" : "\nOrder: " + orderNumber)
                    + "\n\n(Raised by the AI assistant from a chat the customer had; conversation " + conversationId + ")";
            client.post().uri("/api/v1/user/ticket/save")
                    .body(Map.of("subject", subject, "level", LEVEL_MEDIUM, "message", message))
                    .retrieve().body(MAP);
            List<Map<String, Object>> tickets = XboardAccountLookup.list(client.get().uri("/api/v1/user/ticket/fetch").retrieve().body(MAP));
            String id = tickets.isEmpty() ? null : String.valueOf(tickets.getFirst().get("id"));
            SupportTicket ticket = new SupportTicket(NUMBER_PREFIX + (id == null ? "new" : id), conversationId, category, summary,
                    orderNumber, Instant.now(), false);
            return TicketResult.created(ticket);
        }
        catch (HttpClientErrorException e) {
            log.warn("The panel refused a ticket for tenant {} ({}): {}", connector.tenantId(), connector.baseUrl(), e.getStatusCode());
            return TicketResult.unavailable();
        }
        catch (RuntimeException e) {
            log.warn("Ticket in the panel failed for tenant {} ({}): {}", connector.tenantId(), connector.baseUrl(), e.toString());
            return TicketResult.unavailable();
        }
    }

    private RestClient client(OrderConnector connector, String token) {
        String base = properties.xboardBaseUrl() == null || properties.xboardBaseUrl().isBlank() ? connector.baseUrl() : properties.xboardBaseUrl();
        return builder.clone().baseUrl(base)
                .defaultHeader("Accept", "application/json")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(properties.timeoutOrDefault()).withReadTimeout(properties.timeoutOrDefault())))
                .build();
    }

    private static String capitalise(String text) {
        return text.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + text.substring(1);
    }

    private static String shorten(String text, int max) {
        String t = text == null ? "" : text.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
