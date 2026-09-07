package dev.merlionos.customerservice.orders;

/**
 * The seam behind {@code lookup_my_subscription}: the customer's own account, read with the
 * customer's own credential to the tenant's panel. The tenant comes from the API key; the
 * credential is the customer's own panel token, carried by the widget on the panel's page,
 * or the panel's user id when the panel has bound the customer's Telegram account; either is
 * used for this turn and never stored. Ownership is proved by construction: the token reads
 * one account, and a binding is the panel's own statement of whose account a Telegram id is.
 */
public interface AccountLookup {

    AccountLookupResult lookup(String tenantId, CustomerRef customer);
}
