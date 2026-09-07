package dev.merlionos.customerservice.orders;

/**
 * The seam behind {@code lookup_my_subscription}: the customer's own account, read with the
 * customer's own credential to the tenant's panel. The tenant comes from the API key; the
 * credential is the customer's panel token, carried by the widget on the panel's page,
 * used for this turn and never stored. Ownership is proved by construction: the token reads
 * one account, and it is the account of whoever signed in.
 */
public interface AccountLookup {

    AccountLookupResult lookup(String tenantId, String customerToken);
}
