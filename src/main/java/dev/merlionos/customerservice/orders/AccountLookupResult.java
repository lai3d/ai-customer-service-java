package dev.merlionos.customerservice.orders;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * What the {@code lookup_my_subscription} tool returns, and so what the model reads. Four
 * outcomes, each with a sentence the model can act on: {@code found}; {@code not_signed_in}
 * (the customer's panel session did not reach us, so ask them to sign in to the panel and
 * ask again); {@code not_connected} (this tenant has no account system behind the tool);
 * {@code unavailable} (the panel did not answer; try again later, never guess).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountLookupResult(boolean found, String outcome, Account account, String explanation) {

    public static final String FOUND = "found";
    public static final String NOT_SIGNED_IN = "not_signed_in";
    public static final String NOT_CONNECTED = "not_connected";
    public static final String UNAVAILABLE = "unavailable";

    /**
     * @param email            partly masked: the customer knows their own address, the transcript need not carry it
     * @param trafficTotalGb   the plan's allowance in the current period
     * @param trafficUsedGb    upload plus download used
     * @param trafficRemainingGb what is left
     * @param resetDay         days until the traffic resets, when the panel says
     * @param recentOrders     newest first, at most five
     */
    public record Account(String email, String plan, LocalDate expiresOn, boolean expired, Double trafficTotalGb, Double trafficUsedGb,
                          Double trafficRemainingGb, Integer resetDay, Double balance, List<AccountOrder> recentOrders) {
    }

    public record AccountOrder(String tradeNo, String plan, String amount, String status, LocalDate placedOn) {
    }

    public static AccountLookupResult found(Account account) {
        return new AccountLookupResult(true, FOUND, account, null);
    }

    public static AccountLookupResult notSignedIn() {
        return new AccountLookupResult(false, NOT_SIGNED_IN, null, "The customer is not signed in to the panel, so their account "
                + "cannot be read. Ask them to sign in to the panel and ask again from there.");
    }

    public static AccountLookupResult notConnected() {
        return new AccountLookupResult(false, NOT_CONNECTED, null, "This service has no account system connected, so subscription "
                + "details cannot be looked up here. Offer to raise a ticket for a person instead.");
    }

    public static AccountLookupResult unavailable(String explanation) {
        return new AccountLookupResult(false, UNAVAILABLE, null, explanation);
    }
}
