package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.orders.OrderLookup;
import dev.merlionos.customerservice.orders.OrderLookupResult;
import dev.merlionos.customerservice.orders.OrderStatus;
import dev.merlionos.customerservice.orders.AccountLookup;
import dev.merlionos.customerservice.orders.AccountLookupResult;
import dev.merlionos.customerservice.orders.shopify.FakeShopify;
import dev.merlionos.customerservice.orders.xboard.FakeXboard;
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
    static FakeXboard xboard;

    @BeforeAll
    static void startStandIns() throws Exception {
        shopify = new FakeShopify();
        xboard = new FakeXboard();
    }

    @AfterAll
    static void stopStandIns() {
        shopify.close();
        xboard.close();
    }

    @DynamicPropertySource
    static void standInAddresses(DynamicPropertyRegistry registry) {
        registry.add("app.connectors.shopify-base-url", () -> shopify.baseUrl());
        registry.add("app.connectors.xboard-base-url", () -> xboard.baseUrl());
        registry.add("app.connectors.secret-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StaffAccounts accounts;
    @Autowired Tenants tenants;
    @Autowired OrderLookup orders;
    @Autowired AccountLookup customerAccounts;

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

    @Test
    @DisplayName("an Xboard panel: configured by URL, tested without a token, the customer's own account read with theirs")
    void xboardPanel() throws Exception {
        String tenant = "cloud-" + UUID.randomUUID().toString().substring(0, 6);
        tenants.create(tenant, "Northwind Cloud");
        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        String base = "/admin/api/tenants/" + tenant + "/order-connector";

        assertThat(customerAccounts.lookup(tenant, dev.merlionos.customerservice.orders.CustomerRef.token(FakeXboard.CUSTOMER_TOKEN)).outcome()).as("nothing configured").isEqualTo(AccountLookupResult.NOT_CONNECTED);
        assertThat(put(root, base, "{\"kind\":\"xboard\",\"baseUrl\":\"panel.example.com/user\"}").statusCode()).as("a URL with a scheme").isEqualTo(422);
        assertThat(put(root, base, "{\"kind\":\"xboard\",\"baseUrl\":\"https://panel.example.com/user\"}").statusCode()).as("no path").isEqualTo(422);

        HttpResponse<String> configured = put(root, base, "{\"kind\":\"xboard\",\"baseUrl\":\"https://Panel.Example.com/\"}");
        assertThat(configured.statusCode()).as(configured.body()).isEqualTo(200);
        assertThat(configured.body()).contains("\"kind\":\"xboard\"", "\"baseUrl\":\"https://panel.example.com\"", "\"accessToken\":null", "\"adminPath\":null");
        assertThat(root.postJson("/admin/api/knowledge/imports/xboard?tenant=" + tenant, "{}").statusCode())
                .as("no admin token and path yet").isEqualTo(422);
        assertThat(put(root, base, "{\"kind\":\"xboard\",\"baseUrl\":\"https://panel.example.com\",\"accessToken\":\"" + FakeXboard.ADMIN_TOKEN
                + "\",\"adminPath\":\"/" + FakeXboard.ADMIN_PATH + "/\"}").body()).contains("\"adminPath\":\"" + FakeXboard.ADMIN_PATH + "\"", "\"accessToken\":\"****0123\"");
        assertThat(put(root, base, "{\"kind\":\"xboard\",\"baseUrl\":\"https://panel.example.com\",\"adminPath\":\"a b/c\"}").statusCode()).isEqualTo(422);
        assertThat(root.postJson(base + "/test", "{}").body()).contains("\"ok\":true", "\"shopName\":\"Northwind Cloud\"");

        AccountLookupResult mine = customerAccounts.lookup(tenant, dev.merlionos.customerservice.orders.CustomerRef.token(FakeXboard.CUSTOMER_TOKEN));
        assertThat(mine.found()).isTrue();
        assertThat(mine.account().plan()).isEqualTo("Pro 200G");
        assertThat(mine.account().trafficRemainingGb()).isEqualTo(98.5);
        assertThat(customerAccounts.lookup(tenant, dev.merlionos.customerservice.orders.CustomerRef.none()).outcome()).isEqualTo(AccountLookupResult.NOT_SIGNED_IN);
        assertThat(customerAccounts.lookup(tenant, dev.merlionos.customerservice.orders.CustomerRef.token("2|someone-else")).outcome()).isEqualTo(AccountLookupResult.NOT_SIGNED_IN);
        assertThat(orders.lookup(tenant, "#1001").outcome()).as("orders live in the panel").isEqualTo(AccountLookupResult.UNAVAILABLE);
        assertThat(orders.lookup(tenant, "#1001").explanation()).contains("subscription lookup");
        assertThat(jdbc.queryForList("SELECT detail FROM admin_audit WHERE action = 'connector_changed' AND target = ? ORDER BY id", String.class, tenant))
                .as("two configurations, the refused one not recorded as a change").containsExactly("xboard https://panel.example.com v1", "xboard https://panel.example.com v1");
    }
}
