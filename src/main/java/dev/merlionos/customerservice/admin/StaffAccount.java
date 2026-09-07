package dev.merlionos.customerservice.admin;

import java.time.Instant;

/**
 * A staff account as the admin sees it: never the password hash.
 *
 * @param tenantId the tenant the account belongs to, or null for platform staff, who run the
 *                 deployment and are admins by the schema (ADR 002 step 5)
 */
public record StaffAccount(String username, StaffRole role, boolean enabled, Instant createdAt, String createdBy,
                           String tenantId) {

    public boolean platform() {
        return tenantId == null;
    }
}
