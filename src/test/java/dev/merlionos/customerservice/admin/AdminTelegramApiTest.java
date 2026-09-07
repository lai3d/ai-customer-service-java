package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.channels.telegram.FakeTelegram;
import dev.merlionos.customerservice.orders.OrderConnectors;
import dev.merlionos.customerservice.orders.xboard.FakeXboard;
import dev.merlionos.customerservice.tenancy.Conversations;
import dev.merlionos.customerservice.tenancy.Tenant;
import dev.merlionos.customerservice.tenancy.Tenants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * A tenant's Telegram bot end to end with the model stubbed and the Bot API stood in: the
 * admin connects it, a message arrives by polling and is answered, /new starts another
 * conversation, webhook mode registers the URL and takes an update at it, and the bot is
 * removed. Its own context: the stand-in's address is a property.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.rag.import-mode=off", "app.test.isolated=telegram", "app.telegram.public-url=https://cs.example.com/",
                "app.telegram.poll-timeout=1s"})
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminTelegramApiTest {

    static final String PASSWORD = "a-long-enough-password";
    static FakeTelegram telegram;
    static FakeXboard panel;

    @BeforeAll
    static void startStandIns() throws Exception {
        telegram = new FakeTelegram();
        panel = new FakeXboard();
    }

    @AfterAll
    static void stopStandIns() {
        telegram.close();
        panel.close();
    }

    @DynamicPropertySource
    static void standInAddresses(DynamicPropertyRegistry registry) {
        registry.add("app.telegram.api-base-url", () -> telegram.baseUrl());
        registry.add("app.connectors.xboard-base-url", () -> panel.baseUrl());
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StaffAccounts accounts;
    @Autowired Tenants tenants;
    @Autowired Conversations conversations;
    @Autowired OrderConnectors connectors;
    @Autowired TestRestTemplate rest;
    @MockitoBean AnthropicChatModel chatModel;

    @BeforeEach
    void staffAndAModel() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        accounts.create("root", PASSWORD, StaffRole.ADMIN, null, "seed");
        accounts.create("alice", PASSWORD, StaffRole.SUPPORT, Tenant.DEFAULT, "root");
        ChatResponse response = new ChatResponse(java.util.List.of(new Generation(new AssistantMessage("You have 30 days to return it."))));
        given(chatModel.call(any(Prompt.class))).willReturn(response);
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(response));
    }

    @Test
    @DisplayName("connect by polling, answer a message, start a new conversation, switch to webhooks, take an update there, remove")
    void theBotLifecycle() throws Exception {
        String tenant = "tg-" + UUID.randomUUID().toString().substring(0, 6);
        tenants.create(tenant, "Northwind");
        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        String base = "/admin/api/tenants/" + tenant + "/telegram";

        assertThat(root.get(base).statusCode()).isEqualTo(204);
        assertThat(put(root, base, "{\"botToken\":\"not-a-token\"}").statusCode()).isEqualTo(422);
        assertThat(put(root, base, "{\"botToken\":\"123456789:AAHwrongTokenTelegramRefuses0123456789\"}").statusCode())
                .as("checked against getMe before it is stored").isEqualTo(422);
        HttpResponse<String> connected = put(root, base, "{\"botToken\":\"" + FakeTelegram.TOKEN + "\",\"mode\":\"polling\"}");
        assertThat(connected.statusCode()).as(connected.body()).isEqualTo(200);
        assertThat(connected.body()).contains("\"botUsername\":\"northwind_support_bot\"", "\"mode\":\"polling\"", "\"polling\":true", "\"botToken\":\"****6789\"")
                .doesNotContain(FakeTelegram.TOKEN).doesNotContain("webhookSecret\":\"");
        assertThat(root.postJson(base + "/test", "{}").body()).contains("\"ok\":true", "northwind_support_bot");

        // The tenant's panel, with admin access: Telegram 424242 is bound to Alice, 777 to nobody.
        connectors.configureXboard(tenant, "https://panel.example.com", FakeXboard.ADMIN_TOKEN, FakeXboard.ADMIN_PATH, "root");

        long chat = 424242;
        telegram.textMessage(chat, "/start", "en");
        telegram.textMessage(chat, "How long do I have to return a lamp?", "en");
        awaitTexts(chat, 2);
        assertThat(telegram.textsTo(chat).get(0)).contains("Ask me about orders");
        assertThat(telegram.textsTo(chat).get(1)).isEqualTo("You have 30 days to return it.");
        assertThat(conversations.find(tenant, "tg-" + chat + "-1")).as("the chat is a conversation of the tenant").isPresent();
        assertThat(jdbc.queryForObject("SELECT panel_user_id FROM telegram_chat WHERE tenant_id = ? AND chat_id = ?", Long.class, tenant, chat))
                .as("identified through the panel's binding and remembered").isEqualTo(1L);

        telegram.textMessage(chat, "/new", "zh-hans");
        telegram.textMessage(chat, "退货有时间限制吗", "zh-hans");
        awaitTexts(chat, 4);
        assertThat(telegram.textsTo(chat).get(2)).isEqualTo("好的，我们重新开始。");
        assertThat(conversations.find(tenant, "tg-" + chat + "-2")).as("/new started another").isPresent();
        assertThat(jdbc.queryForObject("SELECT panel_user_id FROM telegram_chat WHERE tenant_id = ? AND chat_id = ?", Long.class, tenant, chat))
                .as("/new forgets the binding and the next message finds it again").isEqualTo(1L);

        HttpResponse<String> webhook = put(root, base, "{\"botToken\":\"" + FakeTelegram.TOKEN + "\",\"mode\":\"webhook\"}");
        assertThat(webhook.statusCode()).as(webhook.body()).isEqualTo(200);
        assertThat(webhook.body()).contains("\"polling\":false", "\"webhookUrl\":\"https://cs.example.com/telegram/" + tenant + "/");
        assertThat(telegram.webhooks).hasSize(1);
        String url = String.valueOf(telegram.webhooks.getFirst().get("url"));
        String secret = String.valueOf(telegram.webhooks.getFirst().get("secret_token"));
        assertThat(url).endsWith("/" + secret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Telegram-Bot-Api-Secret-Token", secret);
        Map<String, Object> update = Map.of("update_id", 900, "message", Map.of("message_id", 1, "date", 1,
                "from", Map.of("id", 777, "language_code", "en"), "chat", Map.of("id", 777, "type", "private"), "text", "Is delivery free?"));
        assertThat(rest.postForEntity("/telegram/" + tenant + "/" + secret, new HttpEntity<>(update, headers), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        awaitTexts(777, 1);
        assertThat(telegram.textsTo(777).getFirst()).isEqualTo("You have 30 days to return it.");
        assertThat(jdbc.queryForObject("SELECT panel_user_id FROM telegram_chat WHERE tenant_id = ? AND chat_id = ?", Long.class, tenant, 777L))
                .as("an unbound Telegram account stays unidentified").isNull();
        assertThat(rest.postForEntity("/telegram/" + tenant + "/wrong-secret", new HttpEntity<>(update, headers), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.postForEntity("/telegram/no-such-tenant/" + secret, new HttpEntity<>(update, headers), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);
        assertThat(alice.get(base).statusCode()).as("support cannot see the bot").isEqualTo(403);

        assertThat(delete(root, base).statusCode()).isEqualTo(204);
        assertThat(telegram.webhookDeletes).isGreaterThanOrEqualTo(1);
        assertThat(root.get(base).statusCode()).isEqualTo(204);
        assertThat(rest.postForEntity("/telegram/" + tenant + "/" + secret, new HttpEntity<>(update, headers), Void.class).getStatusCode())
                .as("a removed bot's webhook is gone").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForList("SELECT detail FROM admin_audit WHERE action = 'channel_changed' AND target = ? ORDER BY id", String.class, tenant))
                .containsExactly("telegram @northwind_support_bot polling", "telegram @northwind_support_bot webhook", "telegram removed");
    }

    private void awaitTexts(long chat, int count) throws InterruptedException {
        for (int i = 0; i < 120 && telegram.textsTo(chat).size() < count; i++) {
            Thread.sleep(250);
        }
        assertThat(telegram.textsTo(chat)).as("messages sent to chat %s", chat).hasSizeGreaterThanOrEqualTo(count);
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
