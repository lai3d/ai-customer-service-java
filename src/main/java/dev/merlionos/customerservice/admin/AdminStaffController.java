package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.tenancy.Tenant;
import dev.merlionos.customerservice.tenancy.Tenants;
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
import org.springframework.web.bind.annotation.RequestParam;
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
 * when an admin resets their own password or anyone changes theirs. The rules
 * ({@link StaffRuleException}) are the account store's; refusals are recorded like every
 * other refusal.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH)
class AdminStaffController {

    private final StaffAccounts accounts;
    private final AdminAudit audit;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final Tenants tenants;

    AdminStaffController(StaffAccounts accounts, AdminAudit audit, FindByIndexNameSessionRepository<? extends Session> sessions,
                         Tenants tenants) {
        this.accounts = accounts;
        this.audit = audit;
        this.sessions = sessions;
        this.tenants = tenants;
    }

    /** A tenant as the page names it. */
    record TenantRef(String id, String name) {
    }

    /**
     * The signed-in account, from the session; the page uses it to decide what to show.
     *
     * @param tenant the account's tenant, or null for platform staff, who see every tenant
     */
    record Me(String username, String role, TenantRef tenant) {
    }

    Me me(Authentication authentication) {
        StaffScope scope = StaffScope.of(authentication);
        TenantRef tenant = scope.platform() ? null : tenants.find(scope.tenantId())
                .map(t -> new TenantRef(t.id(), t.name())).orElse(new TenantRef(scope.tenantId(), scope.tenantId()));
        return new Me(scope.username(), scope.role().value(), tenant);
    }

    @GetMapping("/me")
    Me whoAmI(Authentication authentication) {
        return me(authentication);
    }

    static StaffRole roleOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .map(StaffRole::fromValue)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Whose accounts: a tenant admin sees their tenant's; platform staff see every account,
     * or one tenant's with {@code ?tenant=}, or the platform accounts with {@code ?tenant=platform}.
     */
    @GetMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    List<StaffAccount> list(@RequestParam(required = false) String tenant, Authentication authentication) {
        StaffScope scope = StaffScope.of(authentication);
        if (scope.platform() && PLATFORM.equals(tenant)) {
            return accounts.list((String) null);
        }
        String narrowed = scope.listTenant(tenant);
        return narrowed == null ? accounts.list() : accounts.list(narrowed);
    }

    /** The value of {@code tenantId} that means a platform account, on the wire. */
    static final String PLATFORM = "platform";

    /** @param tenantId the account's tenant; {@code platform} or null for a platform account, platform staff only */
    record NewStaffAccount(String username, String password, String role, String tenantId) {
    }

    @PostMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<StaffAccount> create(@RequestBody NewStaffAccount request, Authentication authentication) {
        StaffScope scope = StaffScope.of(authentication);
        String tenant;
        StaffRole role = StaffRole.fromValue(request.role());
        if (scope.platform()) {
            // Silent means: an admin is a platform account, a support member joins the default tenant,
            // which is what the single-tenant install did before tenants existed.
            boolean silent = request.tenantId() == null || request.tenantId().isBlank();
            tenant = PLATFORM.equals(request.tenantId()) ? null
                    : silent ? (role == StaffRole.ADMIN ? null : Tenant.DEFAULT) : request.tenantId().strip();
        }
        else {
            // A tenant admin creates only in their own tenant; naming another is refused, not redirected.
            tenant = scope.listTenant(request.tenantId() == null || PLATFORM.equals(request.tenantId()) ? null : request.tenantId());
        }
        StaffAccount created = accounts.create(request.username(), request.password(), role, tenant, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** The account, if the caller may manage it: their tenant's, or any for platform staff. */
    private StaffAccount managed(String username, Authentication authentication) {
        StaffScope scope = StaffScope.of(authentication);
        return accounts.find(username)
                .filter(account -> scope.platform() || (!account.platform() && scope.covers(account.tenantId())))
                .orElseThrow(() -> new StaffAccountNotFoundException(username));
    }

    record Enabled(Boolean enabled) {
    }

    @PostMapping("/staff/{username}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    StaffAccount setEnabled(@PathVariable String username, @RequestBody Enabled request, Authentication authentication) {
        if (request.enabled() == null) {
            throw new IllegalArgumentException("enabled is required: true or false");
        }
        managed(username, authentication);
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
        StaffAccount before = managed(username, authentication);
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
        managed(username, authentication);
        accounts.resetPassword(username, request.password());
        String name = StaffAccounts.normalise(username);
        HttpSession own = http.getSession(false);
        endSessionsOf(name, name.equals(authentication.getName()) && own != null ? own.getId() : null);
        audit.record(authentication.getName(), AdminAudit.Action.PASSWORD_RESET, name, null);
        return ResponseEntity.noContent().build();
    }

    record ChangePassword(String currentPassword, String newPassword) {
    }

    /**
     * The signed-in account changing its own password, any role. The current password is
     * asked for because a session is not proof of knowing it -- a browser left signed in
     * must not be enough to lock the owner out. Every other session of the account ends;
     * the one this was done from stays.
     */
    @PostMapping("/me/password")
    ResponseEntity<Void> changeOwnPassword(@RequestBody ChangePassword request, Authentication authentication,
                                           HttpServletRequest http) {
        String name = authentication.getName();
        accounts.changePassword(name, request.currentPassword(), request.newPassword());
        HttpSession own = http.getSession(false);
        endSessionsOf(name, own != null ? own.getId() : null);
        audit.record(name, AdminAudit.Action.PASSWORD_CHANGED, name, null);
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
