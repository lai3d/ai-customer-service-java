package dev.merlionos.customerservice.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Sign in and out as JSON, for a UI that is deployed on its own. The session is still a
 * cookie and a row in Postgres, and CSRF is still the readable cookie: the UI's nginx
 * proxies {@code /admin/api} to this process, so the browser sees one origin and nothing
 * here had to become a bearer token. What changed is the shape of the two requests: a
 * {@code fetch} cannot follow a form login's redirect anywhere useful.
 *
 * <p>Signing in rotates the session id (fixation) and the CSRF token, as the form login did.
 * A wrong password and a disabled account get the same sentence, so the endpoint cannot be
 * used to tell accounts apart.
 */
@RestController
@RequestMapping(AdminSecurityConfiguration.API_PATH)
class AdminSessionController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    AdminSessionController(AuthenticationManager authenticationManager, SecurityContextRepository securityContextRepository,
                           CsrfTokenRepository csrfTokenRepository) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    /** Nothing but the CSRF cookie: what a fresh page asks for before it can post anything. */
    @GetMapping("/csrf")
    ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }

    record Credentials(String username, String password) {
    }

    /** Who signed in. A record, not a map, so the JSON is always in this order. */
    record Session(String username, String role) {
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@RequestBody Credentials credentials, HttpServletRequest request,
                                              HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                    credentials.username() == null ? "" : credentials.username().strip(),
                    credentials.password() == null ? "" : credentials.password()));
        }
        catch (BadCredentialsException | DisabledException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Wrong username or password."));
        }
        catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Could not sign in."));
        }
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        // A new token for the new session, the way the form login's strategy issued one.
        csrfTokenRepository.saveToken(null, request, response);
        CsrfToken fresh = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(fresh, request, response);
        return ResponseEntity.ok(new Session(authentication.getName(), AdminStaffController.roleOf(authentication).value()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        csrfTokenRepository.saveToken(null, request, response);
        return ResponseEntity.noContent().build();
    }
}
