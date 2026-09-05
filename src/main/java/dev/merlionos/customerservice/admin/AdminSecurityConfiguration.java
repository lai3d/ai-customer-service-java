package dev.merlionos.customerservice.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;


/**
 * Staff login for the operations admin, and nothing else. The filter chain is bound to
 * {@code /admin/api/**}: the public chat endpoints, the demo page, the actuator and the
 * {@code /internal/**} seam (which has its own bearer token) never pass through Spring
 * Security at all. This is staff authentication for an API that shows customer
 * conversations; it is not customer authentication, which the repository still does not have.
 *
 * <p>The UI is a separate deployable ({@code admin-ui/}, its own nginx container) that
 * proxies {@code /admin/api} to this process, so the browser sees one origin and the session
 * stays a cookie and a row in Postgres. Nothing under {@code /admin} is served here any more.
 *
 * <ul>
 * <li>Sign in and out are JSON ({@link AdminSessionController}); an anonymous request to
 * anything else is {@code 401}, never a redirect.</li>
 * <li>Sessions in Postgres through Spring Session (see {@code V4__staff_accounts.sql}),
 * because the chat role runs as replicas behind one Service. The session id is rotated on
 * login.</li>
 * <li>CSRF on every mutation, the token in a readable {@code XSRF-TOKEN} cookie that the UI
 * copies into an {@code X-XSRF-TOKEN} header; {@code GET /admin/api/csrf} exists so a fresh
 * page can obtain one before its first post. The plain request handler rather than the
 * BREACH-masking one: the token is never in a compressible response body here.</li>
 * <li>Roles are enforced on the server twice: any {@code /admin/api/**} request but the
 * login must be authenticated here, and admin-only operations carry {@code @PreAuthorize}.
 * A signed-in person refused by role is written to {@code admin_audit} before the
 * {@code 403} goes out.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class AdminSecurityConfiguration {

    public static final String API_PATH = "/admin/api";
    public static final String LOGIN_PATH = API_PATH + "/login";
    public static final String CSRF_PATH = API_PATH + "/csrf";

    @Bean
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, AdminAudit audit, CsrfTokenRepository csrfTokenRepository)
            throws Exception {
        http.securityMatcher(API_PATH + "/**")
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, LOGIN_PATH).permitAll()
                        .requestMatchers(HttpMethod.GET, CSRF_PATH).permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .securityContext(context -> context.securityContextRepository(securityContextRepository()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, denied) -> {
                            var principal = request.getUserPrincipal();
                            if (principal != null && !(denied instanceof CsrfException)) {
                                audit.record(principal.getName(), AdminAudit.Action.REFUSED,
                                        request.getMethod() + " " + request.getRequestURI(), denied.getMessage());
                            }
                            response.sendError(HttpStatus.FORBIDDEN.value());
                        }));
        return http.build();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    /** Where a signed-in context lives between requests: the session, which Spring Session keeps in Postgres. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /**
     * Bcrypt behind Spring Security's {@code {id}} prefix, so a stored hash says which
     * algorithm made it and the algorithm can change later without rewriting every row.
     */
    @Bean
    PasswordEncoder staffPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    StaffAccounts staffAccounts(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        return new StaffAccounts(jdbcTemplate, passwordEncoder);
    }

    @Bean
    UserDetailsService staffUserDetailsService(StaffAccounts accounts) {
        return new StaffUserDetailsService(accounts);
    }

    @Bean
    StaffSeeder staffSeeder(StaffAccounts accounts, AdminProperties properties) {
        return new StaffSeeder(accounts, properties);
    }

    @Bean
    AdminAudit adminAudit(JdbcTemplate jdbcTemplate) {
        return new AdminAudit(jdbcTemplate);
    }

    @Bean
    ConversationTranscripts conversationTranscripts(JdbcTemplate jdbcTemplate) {
        return new ConversationTranscripts(jdbcTemplate);
    }

    @Bean
    AdminOverview adminOverview(JdbcTemplate jdbcTemplate) {
        return new AdminOverview(jdbcTemplate);
    }

    @Bean
    AnswerFeedback answerFeedback(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new AnswerFeedback(jdbcTemplate, transactionManager);
    }
}
