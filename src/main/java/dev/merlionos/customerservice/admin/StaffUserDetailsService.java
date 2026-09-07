package dev.merlionos.customerservice.admin;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * The login's view of {@link StaffAccounts}. A disabled account is a disabled
 * {@link StaffPrincipal}, which Spring Security refuses with the same message as a wrong
 * password, so the login cannot be used to tell the two apart. The tenant rides on the
 * principal, so every request after sign-in knows whose rows it may see.
 */
class StaffUserDetailsService implements UserDetailsService {

    private final StaffAccounts accounts;

    StaffUserDetailsService(StaffAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        StaffAccounts.Credential credential = accounts.credential(username)
                .orElseThrow(() -> new UsernameNotFoundException("No staff account named " + username));
        return new StaffPrincipal(credential.username(), credential.passwordHash(), credential.role(),
                credential.enabled(), credential.tenantId());
    }
}
