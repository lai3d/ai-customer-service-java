package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.tenancy.Tenant;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * Whose rows a signed-in member of staff may see: their own tenant's, or, for platform
 * staff, every tenant's. Read from the session's principal, never from a parameter; the
 * one parameter the API takes, {@code ?tenant=}, narrows what platform staff see and is
 * refused for anyone else naming a tenant that is not theirs (a {@code 403}, recorded).
 * A row outside the scope is answered as if it did not exist.
 */
public record StaffScope(String username, StaffRole role, String tenantId) {

    public static StaffScope of(Authentication authentication) {
        String tenant = authentication.getPrincipal() instanceof StaffPrincipal principal ? principal.getTenantId() : null;
        return new StaffScope(authentication.getName(), AdminStaffController.roleOf(authentication), tenant);
    }

    public boolean platform() {
        return tenantId == null;
    }

    /** Whether a row of this tenant is within what the caller may see. */
    public boolean covers(String rowTenant) {
        return platform() || tenantId.equals(rowTenant);
    }

    /**
     * The tenant a list is narrowed to: the caller's own, or for platform staff the one
     * requested, or null for every tenant. A tenant member asking for another tenant is
     * refused, not silently redirected, so a wrong link is visible.
     */
    public String listTenant(String requested) {
        String wanted = requested == null || requested.isBlank() ? null : requested.strip();
        if (platform()) {
            return wanted;
        }
        if (wanted != null && !wanted.equals(tenantId)) {
            throw new AccessDeniedException("Tenant '" + wanted + "' is not yours");
        }
        return tenantId;
    }

    /** As {@link #listTenant}, but a single tenant is always named: platform staff default to {@code default}. */
    public String oneTenant(String requested) {
        String tenant = listTenant(requested);
        return tenant == null ? Tenant.DEFAULT : tenant;
    }

    public void requirePlatform() {
        if (!platform()) {
            throw new AccessDeniedException("Only platform staff may do this");
        }
    }
}
