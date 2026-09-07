package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.orders.OrderLookup;
import dev.merlionos.customerservice.orders.OrderLookupResult;
import dev.merlionos.customerservice.orders.OrderStatus;
import dev.merlionos.customerservice.orders.shopify.FakeShopify;
import dev.merlionos.customerservice.tenancy.Tenant;
import dev.merlionos.customerservice.tenancy.Tenants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant's Shopify connector over the admin API, then the order tool's lookup routed to
 * it: the default tenant keeps the mock, a tenant without a connector is told so, a tenant
 * with one reads its store. Its own context: the Shopify stand-in's address is a property.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.rag.import-mode=off", "app.test.isolated=order-connector"})
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminOrderConnectorApiTest {

    static final String PASSWORD = "a-long-enough-password";
    static FakeShopify shopify;

    @BeforeAll
    static void startShopify() throws Exception {
        shopify = new FakeShopify();
    }

    @AfterAll
    static void stopShopify() {
        shopify.close();
    }

    @DynamicPropertySource
    static void shopifyAddress(DynamicPropertyRegistry registry) {
        registry.add("app.connectors.shopify-base-url", () -> shopify.baseUrl());
        registry.add("app.connectors.secret-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StaffAccounts accounts;
    @Autowired Tenants tenants;
    @Autowired OrderLookup orders;

    @BeforeEach
    void staff() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        accounts.create("root", PASSWORD, StaffRole.ADMIN, null, "seed");
        accounts.create("alice", PASSWORD, StaffRole.SUPPORT, Tenant.DEFAULT, "root");
    }

    @Test
    @DisplayName("configure, read back masked, test the connection, look an order up through the tool's seam, remove")
    void theConnectorLifecycle() throws Exception {
        String tenant = "shop-" + UUID.randomUUID().toString().substring(0, 6);
        tenants.create(tenant, "Northwind Lamps");
        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        String base = "/admin/api/tenants/" + tenant + "/order-connector";

        assertThat(root.get(base).statusCode()).as("nothing configured").isEqualTo(204);
        assertThat(orders.lookup(tenant, "#1001").outcome()).isEqualTo(OrderLookupResult.UNAVAILABLE);
        assertThat(orders.lookup(tenant, "#1001").explanation()).contains("no order system connected");
        assertThat(orders.lookup(Tenant.DEFAULT, "ORD-10042").order().status()).as("the default tenant keeps the mock").isEqualTo(OrderStatus.IN_TRANSIT);

        assertThat(put(root, base, "{\"kind\":\"shopify\",\"shopDomain\":\"not a domain\",\"accessToken\":\"" + FakeShopify.TOKEN + "\"}").statusCode()).isEqualTo(422);
        assertThat(put(root, base, "{\"kind\":\"shopify\",\"shopDomain\":\"northwind-lamps.myshopify.com\",\"accessToken\":\"short\"}").statusCode()).isEqualTo(422);
        assertThat(put(root, base, "{\"kind\":\"woocommerce\",\"shopDomain\":\"x.myshopify.com\",\"accessToken\":\"" + FakeShopify.TOKEN + "\"}").statusCode()).isEqualTo(422);

        HttpResponse<String> configured = put(root, base,
                "{\"kind\":\"shopify\",\"shopDomain\":\"https://Northwind-Lamps.myshopify.com/\",\"accessToken\":\"" + FakeShopify.TOKEN + "\"}");
        assertThat(configured.statusCode()).as(configured.body()).isEqualTo(200);
        assertThat(configured.body()).contains("\"shopDomain\":\"northwind-lamps.myshopify.com\"", "\"apiVersion\":\"2025-07\"", "\"accessToken\":\"****cdef\"")
                .doesNotContain(FakeShopify.TOKEN);
        assertThat(root.get(base).body()).contains("\"accessToken\":\"****cdef\"");
        assertThat(jdbc.queryForObject("SELECT access_token FROM order_connector WHERE tenant_id = ?", String.class, tenant))
                .as("the token is encrypted at rest").startsWith("enc:").doesNotContain("shpat");

        HttpResponse<String> test = root.postJson(base + "/test", "{}");
        assertThat(test.body()).contains("\"ok\":true", "\"shopName\":\"Northwind Lamps\"");

        OrderLookupResult found = orders.lookup(tenant, "1001");
        assertThat(found.found()).isTrue();
        assertThat(found.order().status()).isEqualTo(OrderStatus.IN_TRANSIT);
        assertThat(found.order().trackingNumber()).isEqualTo("SP884213906SG");
        assertThat(orders.lookup(tenant, "#9999").outcome()).isEqualTo(OrderLookupResult.NOT_FOUND);
        assertThat(orders.lookup(Tenant.DEFAULT, "#1001").outcome()).as("another tenant's store is not consulted").isEqualTo(OrderLookupResult.NOT_FOUND);

        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);
        assertThat(alice.get(base).statusCode()).as("support cannot see connectors").isEqualTo(403);
        assertThat(root.get("/admin/api/tenants/no-such/order-connector").statusCode()).isEqualTo(404);

        assertThat(delete(root, base).statusCode()).isEqualTo(204);
        assertThat(root.get(base).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForList("SELECT detail FROM admin_audit WHERE action = 'connector_changed' AND target = ? ORDER BY id", String.class, tenant))
                .containsExactly("shopify northwind-lamps.myshopify.com 2025-07", "removed");
    }

    private static HttpResponse<String> put(AdminBrowser browser, String path, String json) throws Exception {
        return browser.client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + browser.port + path))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", browser.csrf())
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(AdminBrowser browser, String path) throws Exception {
        return browser.client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + browser.port + path))
                .header("X-XSRF-TOKEN", browser.csrf()).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }
}
