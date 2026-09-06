package dev.merlionos.customerservice.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Who is signed in, and -- for admins -- the staff accounts. Every method here runs behind
 * {@link AdminSecurityConfiguration}'s chain, so an anonymous caller never reaches it; the
 * {@code @PreAuthorize} is the second, per-operation check.
 *
 * <p>A change to an account ends that account's sessions, in Postgres, so every replica
 * stops honouring them at once: a disabled account has no signed-in browser, a changed role
 * is not carried by a session signed in under the old one, and a reset password is not
 * undercut by a session the old password opened. The one session kept is the caller's own,
 * when an admin resets their own password. The rules ({@link StaffRuleException}) are the
 * account store's; refusals are recorded like every other refusal.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH)
class AdminStaffController {

    private final StaffAccounts accounts;
    private final AdminAudit audit;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    AdminStaffController(StaffAccounts accounts, AdminAudit audit, FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.accounts = accounts;
        this.audit = audit;
        this.sessions = sessions;
    }

    /** The signed-in account, from the session; the page uses it to decide what to show. */
    record Me(String username, String role) {
    }

    @GetMapping("/me")
    Me me(Authentication authentication) {
        return new Me(authentication.getName(), roleOf(authentication).value());
    }

    static StaffRole roleOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .map(StaffRole::fromValue)
                .findFirst()
                .orElseThrow();
    }

    @GetMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    List<StaffAccount> list() {
        return accounts.list();
    }

    record NewStaffAccount(String username, String password, String role) {
    }

    @PostMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<StaffAccount> create(@RequestBody NewStaffAccount request, Authentication authentication) {
        StaffAccount created = accounts.create(request.username(), request.password(),
                StaffRole.fromValue(request.role()), authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    record Enabled(Boolean enabled) {
    }

    @PostMapping("/staff/{username}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    StaffAccount setEnabled(@PathVariable String username, @RequestBody Enabled request, Authentication authentication) {
        if (request.enabled() == null) {
            throw new IllegalArgumentException("enabled is required: true or false");
        }
        StaffAccount account = accounts.setEnabled(username, request.enabled(), authentication.getName());
        if (!account.enabled()) {
            endSessionsOf(account.username(), null);
        }
        audit.record(authentication.getName(), account.enabled() ? AdminAudit.Action.ACCOUNT_ENABLED : AdminAudit.Action.ACCOUNT_DISABLED,
                account.username(), null);
        return account;
    }

    record NewRole(String role) {
    }

    @PostMapping("/staff/{username}/role")
    @PreAuthorize("hasRole('ADMIN')")
    StaffAccount setRole(@PathVariable String username, @RequestBody NewRole request, Authentication authentication) {
        StaffAccount before = accounts.find(username).orElseThrow(() -> new StaffAccountNotFoundException(username));
        StaffAccount account = accounts.setRole(username, StaffRole.fromValue(request.role()), authentication.getName());
        endSessionsOf(account.username(), null);
        audit.record(authentication.getName(), AdminAudit.Action.ROLE_CHANGED, account.username(),
                before.role().value() + " -> " + account.role().value());
        return account;
    }

    record NewPassword(String password) {
    }

    @PostMapping("/staff/{username}/password")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<Void> resetPassword(@PathVariable String username, @RequestBody NewPassword request,
                                       Authentication authentication, HttpServletRequest http) {
        accounts.resetPassword(username, request.password());
        String name = StaffAccounts.normalise(username);
        HttpSession own = http.getSession(false);
        endSessionsOf(name, name.equals(authentication.getName()) && own != null ? own.getId() : null);
        audit.record(authentication.getName(), AdminAudit.Action.PASSWORD_RESET, name, null);
        return ResponseEntity.noContent().build();
    }

    /** Deletes the account's session rows, except the one named, so no replica honours them. */
    private void endSessionsOf(String username, String except) {
        sessions.findByPrincipalName(username).keySet().stream()
                .filter(id -> !id.equals(except))
                .forEach(sessions::deleteById);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DuplicateStaffAccountException.class)
    ResponseEntity<Map<String, String>> duplicate(DuplicateStaffAccountException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(StaffAccountNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(StaffAccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(StaffRuleException.class)
    ResponseEntity<Map<String, String>> refused(StaffRuleException e, Authentication authentication) {
        audit.record(authentication.getName(), AdminAudit.Action.REFUSED, e.username(), e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
}
