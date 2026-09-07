package dev.merlionos.customerservice.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Every {@code /api/v1/**} request carries a tenant's API key as a bearer token, or it is a
 * {@code 401} before any model call. A plain filter, outside Spring Security's admin chain:
 * the public side has no session and no CSRF, and {@code AdminLoginTest} asserts it stays
 * that way. The resolved tenant goes on the request for the controller to read.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String PATH_PREFIX = "/api/v1/";

    private final TenantApiKeys keys;

    public ApiKeyFilter(TenantApiKeys keys) {
        this.keys = keys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        Optional<Tenant> tenant = header != null && header.startsWith("Bearer ")
                ? keys.resolve(header.substring("Bearer ".length()).strip())
                : Optional.empty();
        if (tenant.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Unauthorized","status":401,"detail":"A tenant API key is required: Authorization: Bearer <key>."}""");
            return;
        }
        TenantContext.set(request, tenant.get());
        chain.doFilter(request, response);
    }
}
