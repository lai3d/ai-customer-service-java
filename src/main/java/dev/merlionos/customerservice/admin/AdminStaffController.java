package dev.merlionos.customerservice.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH)
class AdminStaffController {

    private final StaffAccounts accounts;

    AdminStaffController(StaffAccounts accounts) {
        this.accounts = accounts;
    }

    /** The signed-in account, from the session; the page uses it to decide what to show. */
    record Me(String username, String role) {
    }

    @GetMapping("/me")
    Me me(Authentication authentication) {
        StaffRole role = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .map(StaffRole::fromValue)
                .findFirst()
                .orElseThrow();
        return new Me(authentication.getName(), role.value());
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

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DuplicateStaffAccountException.class)
    ResponseEntity<Map<String, String>> duplicate(DuplicateStaffAccountException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
