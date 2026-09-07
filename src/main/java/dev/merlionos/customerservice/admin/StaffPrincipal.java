package dev.merlionos.customerservice.admin;

import org.springframework.security.core.userdetails.User;

import java.util.List;

/**
 * The signed-in account as the session carries it: username, role, and the tenant it
 * belongs to, null for platform staff. Serialised into {@code spring_session} with the
 * security context, so a re-tenanted account is signed out (every account change deletes
 * its sessions) rather than kept under its old scope.
 */
public class StaffPrincipal extends User {

    private final String tenantId;

    public StaffPrincipal(String username, String passwordHash, StaffRole role, boolean enabled, String tenantId) {
        super(username, passwordHash, enabled, true, true, true,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role.authority())));
        this.tenantId = tenantId;
    }

    public String getTenantId() {
        return tenantId;
    }
}
