package dev.merlionos.customerservice.admin;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Form login's view of {@link StaffAccounts}. A disabled account is a {@code disabled}
 * {@link User}, which Spring Security refuses with the same message as a wrong password,
 * so the login page cannot be used to tell the two apart.
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
        return User.withUsername(credential.username())
                .password(credential.passwordHash())
                .authorities(credential.role().authority())
                .disabled(!credential.enabled())
                .build();
    }
}
