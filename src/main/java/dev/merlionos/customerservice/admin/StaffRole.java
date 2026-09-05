package dev.merlionos.customerservice.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * The two staff roles. {@code admin} manages accounts; {@code support} handles tickets and
 * sees the conversations behind them. Both are staff: neither is a customer identity, and
 * the public chat endpoints never consult them.
 */
public enum StaffRole {
    ADMIN, SUPPORT;

    /** The value stored in {@code staff_account.role} and shown to the page. */
    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Spring Security's spelling: {@code hasRole("ADMIN")} matches {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }

    @JsonCreator
    public static StaffRole fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("role is required: one of admin, support");
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role '" + value + "': one of admin, support", e);
        }
    }
}
