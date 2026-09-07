package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.tenancy.Tenant;
import dev.merlionos.customerservice.tenancy.TenantApiKeys;
import dev.merlionos.customerservice.tenancy.Tenants;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tenants and their API keys, for admins (ADR 002). A key is returned once, from the call
 * that issued it, and never again: the table holds its hash. Everything here is recorded in
 * {@code admin_audit}, since issuing a key is handing out access to a customer's data.
 *
 * <p>Staff are not yet scoped to a tenant; that, and the {@code platform} role above tenants,
 * comes with per-tenant knowledge. Until then every admin is a platform admin.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH + "/tenants")
@PreAuthorize("hasRole('ADMIN')")
class AdminTenantController {

    /** Lower-case, digits and hyphens, 2 to 36 characters: an id that survives a URL and a metric label. */
    static final Pattern TENANT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,35}");

    private final Tenants tenants;
    private final TenantApiKeys keys;
    private final AdminAudit audit;

    AdminTenantController(Tenants tenants, TenantApiKeys keys, AdminAudit audit) {
        this.tenants = tenants;
        this.keys = keys;
        this.audit = audit;
    }

    /** Tenants are platform staff's business; a tenant's own admin has no tenant to manage but their own. */
    @org.springframework.web.bind.annotation.ModelAttribute
    void platformOnly(Authentication authentication) {
        StaffScope.of(authentication).requirePlatform();
    }

    @GetMapping
    List<Tenant> list() {
        return tenants.all();
    }

    record NewTenant(String id, String name) {
    }

    @PostMapping
    ResponseEntity<Tenant> create(@RequestBody NewTenant request, Authentication authentication) {
        if (request.id() == null || !TENANT_ID.matcher(request.id()).matches()) {
            throw new IllegalArgumentException("id must be 2 to 36 lower-case letters, digits or hyphens");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        Tenant created = tenants.create(request.id(), request.name().strip());
        audit.record(authentication.getName(), AdminAudit.Action.TENANT_CREATED, created.id(), created.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    record TenantDetail(Tenant tenant, List<TenantApiKeys.Issued> keys) {
    }

    @GetMapping("/{tenantId}")
    TenantDetail detail(@PathVariable String tenantId) {
        Tenant tenant = tenants.find(tenantId).orElseThrow(() -> new NotFound(tenantId));
        return new TenantDetail(tenant, keys.of(tenantId));
    }

    record Enabled(Boolean enabled) {
    }

    @PostMapping("/{tenantId}/enabled")
    Tenant setEnabled(@PathVariable String tenantId, @RequestBody Enabled request, Authentication authentication) {
        if (request.enabled() == null) {
            throw new IllegalArgumentException("enabled is required: true or false");
        }
        tenants.find(tenantId).orElseThrow(() -> new NotFound(tenantId));
        if (Tenant.DEFAULT.equals(tenantId) && !request.enabled()) {
            throw new IllegalArgumentException("the default tenant cannot be disabled: it is the deployment itself");
        }
        tenants.setEnabled(tenantId, request.enabled());
        audit.record(authentication.getName(),
                request.enabled() ? AdminAudit.Action.TENANT_ENABLED : AdminAudit.Action.TENANT_DISABLED, tenantId, null);
        return tenants.find(tenantId).orElseThrow();
    }

    record NewKey(String label) {
    }

    /** The one response that carries a key. */
    record IssuedKey(String keyId, String key, String label) {
    }

    @PostMapping("/{tenantId}/keys")
    ResponseEntity<IssuedKey> issueKey(@PathVariable String tenantId, @RequestBody(required = false) NewKey request,
                                       Authentication authentication) {
        tenants.find(tenantId).orElseThrow(() -> new NotFound(tenantId));
        String label = request == null || request.label() == null || request.label().isBlank()
                ? "issued by " + authentication.getName() : request.label().strip();
        String key = keys.issue(tenantId, label);
        String keyId = TenantApiKeys.keyId(key);
        audit.record(authentication.getName(), AdminAudit.Action.KEY_ISSUED, tenantId, keyId + " " + label);
        return ResponseEntity.status(HttpStatus.CREATED).body(new IssuedKey(keyId, key, label));
    }

    @PostMapping("/{tenantId}/keys/{keyId}/revoke")
    ResponseEntity<Void> revokeKey(@PathVariable String tenantId, @PathVariable String keyId, Authentication authentication) {
        boolean owned = keys.of(tenantId).stream().anyMatch(issued -> issued.keyId().equals(keyId));
        if (!owned) {
            throw new NotFound(tenantId + "/" + keyId);
        }
        if (keys.revoke(keyId) == 1) {
            audit.record(authentication.getName(), AdminAudit.Action.KEY_REVOKED, tenantId, keyId);
        }
        return ResponseEntity.noContent().build();
    }

    static class NotFound extends RuntimeException {
        NotFound(String what) {
            super("No such tenant or key: " + what);
        }
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<Map<String, String>> notFound(NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, String>> duplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "A tenant with that id already exists"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e, Authentication authentication) {
        audit.record(authentication.getName(), AdminAudit.Action.REFUSED, "tenants", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
}
