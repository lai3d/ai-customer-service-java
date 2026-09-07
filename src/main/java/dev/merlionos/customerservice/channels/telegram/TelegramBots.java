package dev.merlionos.customerservice.channels.telegram;

import dev.merlionos.customerservice.orders.ConnectorSecrets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** The bots, one per tenant, in {@code telegram_bot}; and the chats' conversation counters. */
@Component
public class TelegramBots {

    /** BotFather's shape: a numeric id, a colon, 35 characters. */
    static final Pattern TOKEN = Pattern.compile("\\d{6,12}:[A-Za-z0-9_-]{30,50}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ConnectorSecrets secrets;

    public TelegramBots(JdbcTemplate jdbc, ConnectorSecrets secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    public Optional<TelegramBot> of(String tenantId) {
        return jdbc.query("SELECT * FROM telegram_bot WHERE tenant_id = ?", this::map, tenantId).stream().findFirst();
    }

    public List<TelegramBot> all() {
        return jdbc.query("SELECT * FROM telegram_bot ORDER BY configured_at", this::map);
    }

    private TelegramBot map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TelegramBot(rs.getString("tenant_id"), secrets.open(rs.getString("bot_token")), rs.getString("bot_username"),
                rs.getString("mode"), rs.getString("webhook_secret"), rs.getTimestamp("configured_at").toInstant(), rs.getString("configured_by"));
    }

    public TelegramBot save(String tenantId, String botToken, String botUsername, String mode, String actor) {
        String token = botToken == null ? "" : botToken.strip();
        if (!TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("a bot token from BotFather looks like 123456789:ABCdef...");
        }
        if (!TelegramBot.POLLING.equals(mode) && !TelegramBot.WEBHOOK.equals(mode)) {
            throw new IllegalArgumentException("mode is polling or webhook");
        }
        String secret = of(tenantId).map(TelegramBot::webhookSecret).orElseGet(() -> {
            byte[] bytes = new byte[24];
            RANDOM.nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        });
        jdbc.update("""
                INSERT INTO telegram_bot (tenant_id, bot_token, bot_username, mode, webhook_secret, configured_at, configured_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET bot_token = EXCLUDED.bot_token, bot_username = EXCLUDED.bot_username,
                    mode = EXCLUDED.mode, configured_at = EXCLUDED.configured_at, configured_by = EXCLUDED.configured_by
                """, tenantId, secrets.seal(token), botUsername, mode, secret, Timestamp.from(Instant.now()), actor);
        return of(tenantId).orElseThrow();
    }

    public boolean remove(String tenantId) {
        return jdbc.update("DELETE FROM telegram_bot WHERE tenant_id = ?", tenantId) == 1;
    }

    // --- chats ------------------------------------------------------------------------------

    /** The client-facing conversation id of a chat: {@code tg-<chat>-<n>}, n counting /new. */
    public String conversationOf(String tenantId, long chatId) {
        jdbc.update("INSERT INTO telegram_chat (tenant_id, chat_id) VALUES (?, ?) ON CONFLICT DO NOTHING", tenantId, chatId);
        int n = jdbc.queryForObject("SELECT conversations FROM telegram_chat WHERE tenant_id = ? AND chat_id = ?", Integer.class, tenantId, chatId);
        return "tg-" + chatId + "-" + n;
    }

    public String newConversation(String tenantId, long chatId) {
        jdbc.update("INSERT INTO telegram_chat (tenant_id, chat_id) VALUES (?, ?) ON CONFLICT DO NOTHING", tenantId, chatId);
        jdbc.update("UPDATE telegram_chat SET conversations = conversations + 1, panel_user_id = NULL WHERE tenant_id = ? AND chat_id = ?",
                tenantId, chatId);
        return conversationOf(tenantId, chatId);
    }

    /** The panel user this chat was last identified as, if any. */
    public Optional<Long> panelUserOf(String tenantId, long chatId) {
        return jdbc.query("SELECT panel_user_id FROM telegram_chat WHERE tenant_id = ? AND chat_id = ?",
                (rs, i) -> rs.getObject("panel_user_id", Long.class), tenantId, chatId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public void rememberPanelUser(String tenantId, long chatId, long panelUserId) {
        jdbc.update("INSERT INTO telegram_chat (tenant_id, chat_id) VALUES (?, ?) ON CONFLICT DO NOTHING", tenantId, chatId);
        jdbc.update("UPDATE telegram_chat SET panel_user_id = ? WHERE tenant_id = ? AND chat_id = ?", panelUserId, tenantId, chatId);
    }
}
