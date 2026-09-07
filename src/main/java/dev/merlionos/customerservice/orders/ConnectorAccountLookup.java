package dev.merlionos.customerservice.orders;

import dev.merlionos.customerservice.orders.xboard.XboardAccountLookup;
import org.springframework.stereotype.Component;

/** Routes {@code lookup_my_subscription} to the tenant's panel; a tenant without one is told so. */
@Component
public class ConnectorAccountLookup implements AccountLookup {

    private final OrderConnectors connectors;
    private final XboardAccountLookup xboard;

    public ConnectorAccountLookup(OrderConnectors connectors, XboardAccountLookup xboard) {
        this.connectors = connectors;
        this.xboard = xboard;
    }

    @Override
    public AccountLookupResult lookup(String tenantId, String customerToken) {
        return connectors.of(tenantId)
                .filter(c -> OrderConnector.XBOARD.equals(c.kind()))
                .map(c -> xboard.lookup(c, customerToken))
                .orElseGet(AccountLookupResult::notConnected);
    }
}
