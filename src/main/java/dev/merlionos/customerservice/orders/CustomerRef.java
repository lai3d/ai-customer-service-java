package dev.merlionos.customerservice.orders;

/**
 * Who the customer is to the tenant's panel, for one turn: their own panel token (the
 * widget on the panel's page forwards it), or the panel's user id (a Telegram account the
 * panel has bound), or nothing. Never stored; it rides in the tool context.
 */
public record CustomerRef(String panelToken, Long panelUserId) {

    private static final CustomerRef NONE = new CustomerRef(null, null);

    public static CustomerRef none() {
        return NONE;
    }

    public static CustomerRef token(String token) {
        return token == null || token.isBlank() ? NONE : new CustomerRef(token.strip(), null);
    }

    public static CustomerRef panelUser(long userId) {
        return new CustomerRef(null, userId);
    }

    public boolean known() {
        return panelToken != null || panelUserId != null;
    }
}
