package dev.merlionos.customerservice.orders.xboard;

import dev.merlionos.customerservice.orders.AccountLookupResult;
import dev.merlionos.customerservice.orders.ConnectorProperties;
import dev.merlionos.customerservice.orders.OrderConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The panel's shapes into the tool's, as the signed-in customer; refusal and absence as values. */
class XboardAccountLookupTest {

    static FakeXboard panel;
    static XboardAccountLookup lookup;

    @BeforeAll
    static void start() throws Exception {
        panel = new FakeXboard();
        lookup = new XboardAccountLookup(RestClient.builder(), new ConnectorProperties(null, Duration.ofSeconds(3), null, panel.baseUrl(), true));
    }

    @AfterAll
    static void stop() {
        panel.close();
    }

    private static OrderConnector connector() {
        return new OrderConnector("cloud", OrderConnector.XBOARD, null, "https://panel.example.com", null, "v1", Instant.now(), "root");
    }

    @Test
    @DisplayName("plan, expiry, traffic in gigabytes, balance and the recent orders, the email masked, read with the customer's token")
    void found() {
        AccountLookupResult result = lookup.lookup(connector(), FakeXboard.CUSTOMER_TOKEN);

        assertThat(result.outcome()).isEqualTo(AccountLookupResult.FOUND);
        AccountLookupResult.Account a = result.account();
        assertThat(a.email()).isEqualTo("al***@example.com");
        assertThat(a.plan()).isEqualTo("Pro 200G");
        assertThat(a.expiresOn()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(a.expired()).isFalse();
        assertThat(a.trafficTotalGb()).isEqualTo(200.0);
        assertThat(a.trafficUsedGb()).isEqualTo(101.5);
        assertThat(a.trafficRemainingGb()).isEqualTo(98.5);
        assertThat(a.resetDay()).isEqualTo(9);
        assertThat(a.balance()).isEqualTo(12.5);
        assertThat(a.recentOrders()).hasSize(3);
        assertThat(a.recentOrders().getFirst().tradeNo()).isEqualTo("2026090712345678");
        assertThat(a.recentOrders().getFirst().status()).isEqualTo("completed");
        assertThat(a.recentOrders().getFirst().amount()).isEqualTo("19.90");
        assertThat(a.recentOrders().getFirst().placedOn()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(a.recentOrders().get(2).status()).isEqualTo("pending payment");
        assertThat(panel.requests).contains("/api/v1/user/getSubscribe", "/api/v1/user/info", "/api/v1/user/order/fetch");
    }

    @Test
    @DisplayName("no token, or one the panel refuses, is 'not signed in'; a panel that is down is 'unavailable'")
    void notSignedInAndUnavailable() {
        assertThat(lookup.lookup(connector(), null).outcome()).isEqualTo(AccountLookupResult.NOT_SIGNED_IN);
        assertThat(lookup.lookup(connector(), "  ").outcome()).isEqualTo(AccountLookupResult.NOT_SIGNED_IN);
        AccountLookupResult refused = lookup.lookup(connector(), "2|expired-token");
        assertThat(refused.outcome()).isEqualTo(AccountLookupResult.NOT_SIGNED_IN);
        assertThat(refused.explanation()).contains("sign in");

        XboardAccountLookup nowhere = new XboardAccountLookup(RestClient.builder(),
                new ConnectorProperties(null, Duration.ofSeconds(1), null, "http://127.0.0.1:1", true));
        AccountLookupResult down = nowhere.lookup(connector(), FakeXboard.CUSTOMER_TOKEN);
        assertThat(down.outcome()).isEqualTo(AccountLookupResult.UNAVAILABLE);
        assertThat(down.explanation()).contains("could not be reached").contains("do not guess");

        assertThat(lookup.panelName(connector())).isEqualTo("Northwind Cloud");
        assertThat(XboardAccountLookup.mask("a@x.io")).isEqualTo("a*@x.io");
        assertThat(XboardAccountLookup.mask("not-an-email")).isEqualTo("not-an-email");
    }
}
