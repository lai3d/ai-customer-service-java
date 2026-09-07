package dev.merlionos.customerservice.channels.telegram;

import dev.merlionos.customerservice.orders.CustomerRef;
import dev.merlionos.customerservice.orders.OrderConnector;
import dev.merlionos.customerservice.orders.OrderConnectors;
import dev.merlionos.customerservice.orders.xboard.XboardAccountLookup;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Who a Telegram user is to the tenant's panel. A panel of the V2board family lets a
 * customer bind their Telegram account in its settings; with the tenant's admin token the
 * panel says which of its users a Telegram id belongs to, and from then on the
 * subscription tool reads that user's account through the admin API. Found once and
 * remembered on the chat; {@code /new} forgets it, so an unbinding is noticed the next
 * time the customer starts over. A tenant without a panel, or without admin access to it,
 * has no way to identify a Telegram user, and the tool says so.
 */
@Component
public class TelegramIdentity {

    private final OrderConnectors connectors;
    private final XboardAccountLookup xboard;
    private final TelegramBots bots;

    public TelegramIdentity(OrderConnectors connectors, XboardAccountLookup xboard, TelegramBots bots) {
        this.connectors = connectors;
        this.xboard = xboard;
        this.bots = bots;
    }

    public CustomerRef resolve(String tenantId, long chatId, Long telegramUserId) {
        Optional<Long> remembered = bots.panelUserOf(tenantId, chatId);
        if (remembered.isPresent()) {
            return CustomerRef.panelUser(remembered.get());
        }
        if (telegramUserId == null) {
            return CustomerRef.none();
        }
        Optional<OrderConnector> panel = connectors.of(tenantId).filter(c -> OrderConnector.XBOARD.equals(c.kind()));
        if (panel.isEmpty()) {
            return CustomerRef.none();
        }
        Optional<Long> bound = xboard.panelUserByTelegram(panel.get(), telegramUserId);
        bound.ifPresent(id -> bots.rememberPanelUser(tenantId, chatId, id));
        return bound.map(CustomerRef::panelUser).orElse(CustomerRef.none());
    }
}
