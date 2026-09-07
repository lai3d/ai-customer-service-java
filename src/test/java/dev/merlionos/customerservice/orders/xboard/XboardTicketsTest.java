package dev.merlionos.customerservice.orders.xboard;

import dev.merlionos.customerservice.orders.ConnectorProperties;
import dev.merlionos.customerservice.orders.OrderConnector;
import dev.merlionos.customerservice.ticket.api.TicketResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class XboardTicketsTest {

    static FakeXboard panel;
    static XboardTickets tickets;

    @BeforeAll
    static void start() throws Exception {
        panel = new FakeXboard();
        tickets = new XboardTickets(RestClient.builder(), new ConnectorProperties(null, Duration.ofSeconds(3), null, panel.baseUrl(), true));
    }

    @AfterAll
    static void stop() {
        panel.close();
    }

    private static OrderConnector connector() {
        return new OrderConnector("cloud", OrderConnector.XBOARD, null, "https://panel.example.com", null, "v1", null, Instant.now(), "root");
    }

    @Test
    @DisplayName("a ticket is saved in the panel as the customer, with the summary and order in its message, and numbered from the panel")
    void raisedAsTheCustomer() {
        TicketResult result = tickets.create(connector(), FakeXboard.CUSTOMER_TOKEN, "conv-1",
                "Customer cannot connect on the HK node since this morning", "account", null);

        assertThat(result.created()).isTrue();
        assertThat(result.ticket().ticketNumber()).isEqualTo(XboardTickets.NUMBER_PREFIX + "100");
        assertThat(result.ticket().conversationId()).isEqualTo("conv-1");
        assertThat(panel.tickets).hasSize(1);
        String body = String.valueOf(panel.tickets.getFirst().get("body"));
        assertThat(body).contains("\"subject\":\"Account: Customer cannot connect on the HK node since this morning\"")
                .contains("\"level\":1").contains("conversation conv-1");
        assertThat(panel.requests).contains("/api/v1/user/ticket/save", "/api/v1/user/ticket/fetch");
    }

    @Test
    @DisplayName("a refused token or a panel that is down is 'unavailable', so the tool keeps a ticket of its own")
    void unavailable() {
        assertThat(tickets.create(connector(), "2|nope", "conv-2", "x", "other", null).status()).isEqualTo(TicketResult.Status.UNAVAILABLE);
        XboardTickets nowhere = new XboardTickets(RestClient.builder(), new ConnectorProperties(null, Duration.ofSeconds(1), null, "http://127.0.0.1:1", true));
        assertThat(nowhere.create(connector(), FakeXboard.CUSTOMER_TOKEN, "conv-3", "x", "other", null).status()).isEqualTo(TicketResult.Status.UNAVAILABLE);
    }
}
