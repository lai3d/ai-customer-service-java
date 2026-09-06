package dev.merlionos.customerservice.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies {@link StaffSessionPolicy}'s absolute lifetime. A session signed in longer ago than
 * the lifetime allows is invalidated -- Spring Session deletes the row, so no replica honours
 * it, and expires the cookie -- and the request goes on as an anonymous one: {@code 401} from
 * the authorization filter on anything that needs a sign-in, and a sign-in that succeeds on
 * {@code /login}, in a fresh session. Added to the admin chain only, after the security
 * context filter and before authorization; the public side never sees it.
 */
class StaffSessionLifetimeFilter extends OncePerRequestFilter {

    private final StaffSessionPolicy policy;

    StaffSessionLifetimeFilter(StaffSessionPolicy policy) {
        this.policy = policy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && policy.outlived(session)) {
            session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
