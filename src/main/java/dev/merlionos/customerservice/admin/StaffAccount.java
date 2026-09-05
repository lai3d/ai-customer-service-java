package dev.merlionos.customerservice.admin;

import java.time.Instant;

/** A staff account as the admin sees it: never the password hash. */
public record StaffAccount(String username, StaffRole role, boolean enabled, Instant createdAt, String createdBy) {
}
