package dev.merlionos.customerservice.channels.telegram;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Where Telegram posts a bot's updates in webhook mode: {@code /telegram/{tenant}/{secret}},
 * the secret from the bot's row and also sent by Telegram in
 * {@code X-Telegram-Bot-Api-Secret-Token}. Anything else is a 404 that says nothing. The
 * update is handled off the request thread and the request answered at once, as Telegram
 * expects; a slow answer here would be retried as a duplicate.
 */
@RestController
public class TelegramWebhookController {

    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final TelegramBots bots;
    private final TelegramChannel channel;

    public TelegramWebhookController(TelegramBots bots, TelegramChannel channel) {
        this.bots = bots;
        this.channel = channel;
    }

    @PostMapping("/telegram/{tenantId}/{secret}")
    ResponseEntity<Void> update(@PathVariable String tenantId, @PathVariable String secret, @RequestBody Map<String, Object> update,
                                HttpServletRequest request) {
        TelegramBot bot = bots.of(tenantId).orElse(null);
        String header = request.getHeader(SECRET_HEADER);
        if (bot == null || !TelegramBot.WEBHOOK.equals(bot.mode()) || !bot.webhookSecret().equals(secret)
                || (header != null && !bot.webhookSecret().equals(header))) {
            return ResponseEntity.notFound().build();
        }
        Thread.ofVirtual().name("telegram-webhook-" + tenantId).start(() -> channel.handle(bot, update));
        return ResponseEntity.ok().build();
    }
}
