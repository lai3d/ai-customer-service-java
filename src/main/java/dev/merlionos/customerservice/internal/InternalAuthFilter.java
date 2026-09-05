package dev.merlionos.customerservice.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Every {@code /internal/**} request must carry the shared token. A ticket-creating endpoint
 * reachable on a network without a credential is a write anyone on that network can perform;
 * the token is the minimum, and a NetworkPolicy in the cluster is the other half.
 *
 * <p>Compared in constant time. The routes themselves are not registered in an {@code all}
 * process, so there this filter has nothing to guard and is not installed.
 */
public class InternalAuthFilter extends OncePerRequestFilter {

    public static final String PATH_PREFIX = "/internal/";

    private final byte[] expected;

    public InternalAuthFilter(String token) {
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        byte[] presented = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, presented)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return;
        }
        chain.doFilter(request, response);
    }
}
