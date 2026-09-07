package dev.merlionos.customerservice.tools;

import dev.merlionos.customerservice.chat.TurnEventBus;
import dev.merlionos.customerservice.orders.AccountLookupResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AccountToolsTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final List<String> seen = new java.util.ArrayList<>();
    private final AccountTools tools = new AccountTools((tenant, customer) -> {
        seen.add(tenant + "/" + (customer.panelToken() != null ? customer.panelToken() : customer.panelUserId()));
        return !customer.known() ? AccountLookupResult.notSignedIn()
                : AccountLookupResult.found(new AccountLookupResult.Account("al***@x.io", "Pro", null, false, 200.0, 1.0, 199.0, 9, 0.0, List.of()));
    }, meterRegistry, new TurnEventBus());

    private static ToolContext context(String token) {
        Map<String, Object> c = new HashMap<>(Map.of(SupportTicketTools.TENANT_ID_KEY, "cloud",
                SupportTicketTools.CONVERSATION_ID_KEY, "conversation-1", TurnEventBus.TURN_ID_KEY, "turn-1"));
        if (token != null) {
            c.put(AccountTools.CUSTOMER_TOKEN_KEY, token);
        }
        return new ToolContext(c);
    }

    @Test
    @DisplayName("the tenant and the customer's token come from the context; the outcome is counted")
    void readsTheContext() {
        AccountLookupResult result = tools.lookupMySubscription(context("tok"));
        assertThat(result.found()).isTrue();
        assertThat(seen).containsExactly("cloud/tok");
        assertThat(meterRegistry.counter("chat.tool.invocations", "tool", "lookup_my_subscription", "outcome", "found").count()).isEqualTo(1.0);

        assertThat(tools.lookupMySubscription(context(null)).outcome()).isEqualTo(AccountLookupResult.NOT_SIGNED_IN);
        assertThat(seen).containsExactly("cloud/tok", "cloud/null");

        java.util.Map<String, Object> byUser = new HashMap<>(Map.of(SupportTicketTools.TENANT_ID_KEY, "cloud",
                SupportTicketTools.CONVERSATION_ID_KEY, "conversation-1", TurnEventBus.TURN_ID_KEY, "turn-1", AccountTools.CUSTOMER_USER_KEY, 7L));
        assertThat(tools.lookupMySubscription(new ToolContext(byUser)).found()).as("a panel user id identifies the customer too").isTrue();
        assertThat(seen.getLast()).isEqualTo("cloud/7");
        assertThat(meterRegistry.counter("chat.tool.invocations", "tool", "lookup_my_subscription", "outcome", "unavailable").count())
                .as("registered at zero").isZero();
    }
}
