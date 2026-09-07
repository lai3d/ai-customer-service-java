package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.orders.OrderConnector;
import dev.merlionos.customerservice.orders.OrderConnectors;
import dev.merlionos.customerservice.orders.shopify.ShopifyOrderLookup;
import dev.merlionos.customerservice.orders.xboard.XboardAccountLookup;
import dev.merlionos.customerservice.tenancy.Tenants;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A tenant's order connector, for its admins and platform admins (docs/connectors.md). The
 * token is written, never read back: the admin sees its last four characters. "Test" asks
 * the store for its name with the stored token, which is the whole of what a wrong token or
 * domain would fail.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH + "/tenants/{tenantId}/order-connector")
@PreAuthorize("hasRole('ADMIN')")
class AdminOrderConnectorController {

    private final OrderConnectors connectors;
    private final ShopifyOrderLookup shopify;
    private final XboardAccountLookup xboard;
    private final Tenants tenants;
    private final AdminAudit audit;

    AdminOrderConnectorController(OrderConnectors connectors, ShopifyOrderLookup shopify, XboardAccountLookup xboard, Tenants tenants,
                                  AdminAudit audit) {
        this.connectors = connectors;
        this.shopify = shopify;
        this.xboard = xboard;
        this.tenants = tenants;
        this.audit = audit;
    }

    private String scoped(String tenantId, Authentication auth) {
        StaffScope scope = StaffScope.of(auth);
        if (!scope.covers(tenantId) || tenants.find(tenantId).isEmpty()) {
            throw new NotFound("No tenant '" + tenantId + "'");
        }
        return tenantId;
    }

    @GetMapping
    ResponseEntity<OrderConnector> get(@PathVariable String tenantId, Authentication auth) {
        return connectors.of(scoped(tenantId, auth)).map(OrderConnector::masked).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * @param kind       {@code shopify} (the default) with {@code shopDomain} and {@code accessToken}, or
     *                   {@code xboard} with {@code baseUrl} and an optional admin {@code accessToken}
     */
    record ConnectorConfig(String kind, String shopDomain, String baseUrl, String accessToken, String apiVersion, String adminPath) {
    }

    @PutMapping
    OrderConnector put(@PathVariable String tenantId, @RequestBody ConnectorConfig config, Authentication auth) {
        String id = scoped(tenantId, auth);
        String kind = config.kind() == null || config.kind().isBlank() ? OrderConnector.SHOPIFY : config.kind().strip().toLowerCase(java.util.Locale.ROOT);
        OrderConnector stored = switch (kind) {
            case OrderConnector.SHOPIFY -> connectors.configureShopify(id, config.shopDomain(), config.accessToken(), config.apiVersion(), auth.getName());
            case OrderConnector.XBOARD -> connectors.configureXboard(id, config.baseUrl(), config.accessToken(), config.adminPath(), auth.getName());
            default -> throw new IllegalArgumentException("a connector kind is shopify or xboard");
        };
        audit.record(auth.getName(), AdminAudit.Action.CONNECTOR_CHANGED, id, stored.kind() + " "
                + (stored.shopDomain() != null ? stored.shopDomain() : stored.baseUrl()) + " " + stored.apiVersion());
        return stored.masked();
    }

    @DeleteMapping
    ResponseEntity<Void> delete(@PathVariable String tenantId, Authentication auth) {
        String id = scoped(tenantId, auth);
        if (connectors.remove(id)) {
            audit.record(auth.getName(), AdminAudit.Action.CONNECTOR_CHANGED, id, "removed");
        }
        return ResponseEntity.noContent().build();
    }

    record TestResult(boolean ok, String shopDomain, String shopName, String error) {
    }

    @PostMapping("/test")
    TestResult test(@PathVariable String tenantId, Authentication auth) {
        String id = scoped(tenantId, auth);
        OrderConnector connector = connectors.of(id).orElseThrow(() -> new NotFound("No connector for '" + id + "'"));
        String where = connector.shopDomain() != null ? connector.shopDomain() : connector.baseUrl();
        try {
            String name = OrderConnector.XBOARD.equals(connector.kind()) ? xboard.panelName(connector) : shopify.shopName(connector);
            return new TestResult(true, where, name, null);
        }
        catch (RuntimeException e) {
            return new TestResult(false, where, null, e.getMessage());
        }
    }

    static class NotFound extends RuntimeException {
        NotFound(String message) {
            super(message);
        }
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<Map<String, String>> notFound(NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e, Authentication auth) {
        audit.record(auth.getName(), AdminAudit.Action.REFUSED, "order-connector", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
}
