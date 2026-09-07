package dev.merlionos.customerservice.channels.telegram;

import dev.merlionos.customerservice.rag.api.ImportMode;
import dev.merlionos.customerservice.rag.api.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One long-polling loop per bot in polling mode, on a virtual thread, started at ready and
 * when a bot is configured, stopped when it is removed or switched to webhooks. Each update
 * is handled on its own virtual thread, so one slow model call does not hold the others.
 *
 * <p>Polling is for one chat process: Telegram gives the updates to whichever instance asks
 * and answers the others with 409, so replicas use webhooks (docs/channels.md).
 */
@Component
public class TelegramPollers {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollers.class);

    private final TelegramBots bots;
    private final TelegramApi api;
    private final TelegramChannel channel;
    private final RagProperties rag;
    private final Map<String, Poller> pollers = new ConcurrentHashMap<>();

    public TelegramPollers(TelegramBots bots, TelegramApi api, TelegramChannel channel, RagProperties rag) {
        this.bots = bots;
        this.api = api;
        this.channel = channel;
        this.rag = rag;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (rag.importMode() == ImportMode.ONCE) {
            return; // an import job, exiting
        }
        for (TelegramBot bot : bots.all()) {
            if (TelegramBot.POLLING.equals(bot.mode())) {
                start(bot);
            }
        }
    }

    public synchronized void start(TelegramBot bot) {
        stop(bot.tenantId());
        Poller poller = new Poller(bot);
        pollers.put(bot.tenantId(), poller);
        Thread.ofVirtual().name("telegram-" + bot.tenantId()).start(poller);
        log.info("Polling Telegram for tenant {} as @{}", bot.tenantId(), bot.botUsername());
    }

    public synchronized void stop(String tenantId) {
        Poller poller = pollers.remove(tenantId);
        if (poller != null) {
            poller.stopped = true;
        }
    }

    public boolean polling(String tenantId) {
        return pollers.containsKey(tenantId);
    }

    private final class Poller implements Runnable {
        private final TelegramBot bot;
        private volatile boolean stopped;
        private long offset;

        Poller(TelegramBot bot) {
            this.bot = bot;
        }

        @Override
        public void run() {
            while (!stopped) {
                try {
                    List<Map<String, Object>> updates = api.getUpdates(bot.botToken(), offset);
                    for (Map<String, Object> update : updates) {
                        long id = ((Number) update.get("update_id")).longValue();
                        offset = Math.max(offset, id + 1);
                        Thread.ofVirtual().name("telegram-update-" + id).start(() -> channel.handle(bot, update));
                    }
                }
                catch (RuntimeException e) {
                    if (stopped) {
                        return;
                    }
                    log.warn("Telegram polling for tenant {} failed, retrying in 5 s: {}", bot.tenantId(), e.toString());
                    try {
                        Thread.sleep(5_000);
                    }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}
