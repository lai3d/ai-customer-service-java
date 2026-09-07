package dev.merlionos.customerservice.tools;

import dev.merlionos.customerservice.chat.TurnEvent;
import dev.merlionos.customerservice.chat.TurnEventBus;
import dev.merlionos.customerservice.orders.AccountLookup;
import dev.merlionos.customerservice.orders.AccountLookupResult;
import dev.merlionos.customerservice.orders.CustomerRef;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The customer's own subscription, for tenants whose customers sign in to a panel. The
 * adapter: the tenant and the customer's panel token come from the tool context (the
 * request's API key, and the {@code X-Customer-Token} header the widget forwards or the panel
 * user a Telegram binding resolved to), never from a model argument -- the tool has no parameters at all, so the model cannot ask
 * about anyone else.
 */
@Component
public class AccountTools {

    public static final String CUSTOMER_TOKEN_KEY = "customerToken";
    /** The panel's user id for a customer the panel identified another way (a Telegram binding). */
    public static final String CUSTOMER_USER_KEY = "customerUserId";
    private static final String TOOL_NAME = "lookup_my_subscription";

    private final AccountLookup accounts;
    private final MeterRegistry meterRegistry;
    private final TurnEventBus turnEventBus;

    AccountTools(AccountLookup accounts, MeterRegistry meterRegistry, TurnEventBus turnEventBus) {
        this.accounts = accounts;
        this.meterRegistry = meterRegistry;
        this.turnEventBus = turnEventBus;
        for (String outcome : List.of(AccountLookupResult.FOUND, AccountLookupResult.NOT_SIGNED_IN,
                AccountLookupResult.NOT_CONNECTED, AccountLookupResult.UNAVAILABLE)) {
            meterRegistry.counter("chat.tool.invocations", "tool", TOOL_NAME, "outcome", outcome);
        }
    }

    @Tool(name = "lookup_my_subscription", description = """
            Look up the signed-in customer's own subscription account: plan, expiry date, \
            traffic allowance, used and remaining, account balance, and their most recent \
            orders with payment status. Use this whenever a customer asks about their plan, \
            when it expires, how much traffic they have left, whether a payment or renewal \
            went through, or why their service stopped. It reads only the account of the \
            customer who is signed in; there is nothing to pass. If the result says the \
            customer is not signed in, ask them to sign in to the panel and ask again there; \
            if it says the panel is unavailable, say so and offer to try again later or raise \
            a ticket. Never guess a plan, a date or a traffic figure.
            """,
            resultConverter = ReadableToolResultConverter.class)
    public AccountLookupResult lookupMySubscription(ToolContext toolContext) {
        String tenantId = SupportTicketTools.tenantIdFrom(toolContext);
        Object token = toolContext.getContext().get(CUSTOMER_TOKEN_KEY);
        Object userId = toolContext.getContext().get(CUSTOMER_USER_KEY);
        CustomerRef customer = token != null ? CustomerRef.token(String.valueOf(token))
                : userId instanceof Number n ? CustomerRef.panelUser(n.longValue()) : CustomerRef.none();
        AccountLookupResult result = accounts.lookup(tenantId, customer);
        meterRegistry.counter("chat.tool.invocations", "tool", TOOL_NAME, "outcome", result.outcome()).increment();
        turnEventBus.publish(SupportTicketTools.turnIdFrom(toolContext), new TurnEvent.ToolCall(TOOL_NAME, result.outcome()));
        return result;
    }
}
