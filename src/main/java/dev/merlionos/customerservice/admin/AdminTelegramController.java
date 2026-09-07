package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.channels.telegram.TelegramApi;
import dev.merlionos.customerservice.channels.telegram.TelegramBot;
import dev.merlionos.customerservice.channels.telegram.TelegramBots;
import dev.merlionos.customerservice.channels.telegram.TelegramPollers;
import dev.merlionos.customerservice.channels.telegram.TelegramProperties;
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
 * A tenant's Telegram bot (docs/channels.md): the token from BotFather goes in once, is
 * checked against {@code getMe} before it is stored, and comes back masked. Polling starts
 * or stops with the row; webhook mode registers this deployment's public URL with Telegram.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH + "/tenants/{tenantId}/telegram")
@PreAuthorize("hasRole('ADMIN')")
class AdminTelegramController {

    private final TelegramBots bots;
    private final TelegramApi api;
    private final TelegramPollers pollers;
    private final TelegramProperties properties;
    private final Tenants tenants;
    private final AdminAudit audit;

    AdminTelegramController(TelegramBots bots, TelegramApi api, TelegramPollers pollers, TelegramProperties properties, Tenants tenants,
                            AdminAudit audit) {
        this.bots = bots;
        this.api = api;
        this.pollers = pollers;
        this.properties = properties;
        this.tenants = tenants;
        this.audit = audit;
    }

    private String scoped(String tenantId, Authentication auth) {
        if (!StaffScope.of(auth).covers(tenantId) || tenants.find(tenantId).isEmpty()) {
            throw new NotFound("No tenant '" + tenantId + "'");
        }
        return tenantId;
    }

    record BotView(TelegramBot bot, boolean polling, String webhookUrl) {
    }

    @GetMapping
    ResponseEntity<BotView> get(@PathVariable String tenantId, Authentication auth) {
        String id = scoped(tenantId, auth);
        return bots.of(id).map(bot -> ResponseEntity.ok(view(bot))).orElseGet(() -> ResponseEntity.noContent().build());
    }

    record BotConfig(String botToken, String mode) {
    }

    @PutMapping
    BotView put(@PathVariable String tenantId, @RequestBody BotConfig config, Authentication auth) {
        String id = scoped(tenantId, auth);
        String mode = config.mode() == null || config.mode().isBlank() ? TelegramBot.POLLING : config.mode().strip().toLowerCase(java.util.Locale.ROOT);
        if (TelegramBot.WEBHOOK.equals(mode) && !properties.hasPublicUrl()) {
            throw new IllegalArgumentException("webhook mode needs app.telegram.public-url (PUBLIC_URL) on this deployment; use polling on a laptop");
        }
        String token = config.botToken() == null ? "" : config.botToken().strip();
        String username;
        try {
            Object name = api.getMe(token).get("username");
            username = name == null ? null : String.valueOf(name);
        }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("Telegram did not accept that bot token: " + e.getMessage());
        }
        TelegramBot bot = bots.save(id, token, username, mode, auth.getName());
        if (TelegramBot.WEBHOOK.equals(mode)) {
            pollers.stop(id);
            api.setWebhook(bot.botToken(), webhookUrl(bot), bot.webhookSecret());
        }
        else {
            try {
                api.deleteWebhook(bot.botToken());
            }
            catch (RuntimeException ignored) {
                // A bot that never had one answers ok anyway; a failure here does not stop polling.
            }
            pollers.start(bot);
        }
        audit.record(auth.getName(), AdminAudit.Action.CHANNEL_CHANGED, id, "telegram @" + username + " " + mode);
        return view(bot);
    }

    @DeleteMapping
    ResponseEntity<Void> delete(@PathVariable String tenantId, Authentication auth) {
        String id = scoped(tenantId, auth);
        bots.of(id).ifPresent(bot -> {
            pollers.stop(id);
            try {
                api.deleteWebhook(bot.botToken());
            }
            catch (RuntimeException ignored) {
                // The row goes regardless; Telegram will get a 404 from us and stop.
            }
            bots.remove(id);
            audit.record(auth.getName(), AdminAudit.Action.CHANNEL_CHANGED, id, "telegram removed");
        });
        return ResponseEntity.noContent().build();
    }

    record TestResult(boolean ok, String botUsername, String error) {
    }

    @PostMapping("/test")
    TestResult test(@PathVariable String tenantId, Authentication auth) {
        String id = scoped(tenantId, auth);
        TelegramBot bot = bots.of(id).orElseThrow(() -> new NotFound("No Telegram bot for '" + id + "'"));
        try {
            return new TestResult(true, String.valueOf(api.getMe(bot.botToken()).get("username")), null);
        }
        catch (RuntimeException e) {
            return new TestResult(false, bot.botUsername(), e.getMessage());
        }
    }

    private BotView view(TelegramBot bot) {
        return new BotView(bot.masked(), pollers.polling(bot.tenantId()),
                TelegramBot.WEBHOOK.equals(bot.mode()) && properties.hasPublicUrl() ? webhookUrl(bot) : null);
    }

    private String webhookUrl(TelegramBot bot) {
        return properties.publicUrl().replaceFirst("/+$", "") + "/telegram/" + bot.tenantId() + "/" + bot.webhookSecret();
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
        audit.record(auth.getName(), AdminAudit.Action.REFUSED, "telegram", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
}
