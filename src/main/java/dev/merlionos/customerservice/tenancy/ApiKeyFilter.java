package dev.merlionos.customerservice.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 *
 * <p>A widget key is used from a browser on the tenant's own site, so its request carries an
 * {@code Origin}, and the key names the origins it may come from: any other origin, or none,
 * is a {@code 403}. The CORS answer is given only for an allowed origin and only for widget
 * keys; a secret key from a browser gets no CORS headers, which is the browser refusing it
 * for the tenant. The preflight is answered for any origin without a key -- a preflight
 * carries no {@code Authorization}, and it grants nothing by itself; the actual request is
 * still checked.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String PATH_PREFIX = "/api/v1/";
    static final String ALLOWED_HEADERS = "Authorization, Content-Type, Accept";
    static final String EXPOSED_HEADERS = "X-Conversation-Id";
    static final String PREFLIGHT_MAX_AGE = "3600";

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
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (HttpMethod.OPTIONS.matches(request.getMethod()) && origin != null
                && request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null) {
            response.setStatus(HttpStatus.NO_CONTENT.value());
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "POST, OPTIONS");
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, ALLOWED_HEADERS);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, PREFLIGHT_MAX_AGE);
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        Optional<TenantApiKeys.ApiKey> key = header != null && header.startsWith("Bearer ")
                ? keys.resolveKey(header.substring("Bearer ".length()).strip())
                : Optional.empty();
        if (key.isEmpty()) {
            problem(response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                    "A tenant API key is required: Authorization: Bearer <key>.");
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return;
        }
        if (key.get().isWidget()) {
            if (!key.get().allowsOrigin(origin)) {
                problem(response, HttpStatus.FORBIDDEN, "Forbidden",
                        origin == null ? "This key is a widget key: it is used from a browser on one of its origins, not from here."
                                : "This widget key may not be used from " + origin + ".");
                return;
            }
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, EXPOSED_HEADERS);
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        }
        TenantContext.set(request, key.get().tenant());
        chain.doFilter(request, response);
    }

    private static void problem(HttpServletResponse response, HttpStatus status, String title, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
