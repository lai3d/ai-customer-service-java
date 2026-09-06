package dev.merlionos.customerservice.admin;

/**
 * A change to an account that the rules refuse: acting on your own account's access, or
 * leaving the system without an enabled admin. Reloading will not help, so it is a
 * {@code 422} on the API and recorded in {@code admin_audit} as a refusal.
 */
public class StaffRuleException extends RuntimeException {

    private final String username;

    public StaffRuleException(String username, String message) {
        super(message);
        this.username = username;
    }

    public String username() {
        return username;
    }
}
