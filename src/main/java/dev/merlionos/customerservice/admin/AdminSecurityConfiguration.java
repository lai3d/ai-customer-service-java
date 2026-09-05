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
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Staff login for the operations admin, and nothing else. The filter chain is bound to
 * {@code /admin/**}: the public chat endpoints, the demo page, the actuator and the
 * {@code /internal/**} seam (which has its own bearer token) never pass through Spring
 * Security at all. This is staff authentication for a page that shows customer
 * conversations; it is not customer authentication, which the repository still does not have.
 *
 * <h2>Shape</h2>
 *
 * <ul>
 * <li>Form login at {@code /admin/login}, the page being a static file served by
 * {@link AdminPageController}. Success goes to {@code /admin}; failure back to the page with
 * {@code ?error}, which says "wrong username or password" for every cause, so the form
 * cannot be used to enumerate accounts.</li>
 * <li>Sessions in Postgres through Spring Session (see {@code V4__staff_accounts.sql}),
 * because the chat role runs as replicas behind one Service. The session id is rotated on
 * login.</li>
 * <li>CSRF on every mutation, the token in a readable {@code XSRF-TOKEN} cookie that the page
 * copies into an {@code X-XSRF-TOKEN} header or a {@code _csrf} field. The plain request
 * handler rather than the BREACH-masking one: the token is never in a compressible response
 * body here, only in a cookie header, so there is nothing for the masking to protect.</li>
 * <li>{@code /admin/api/**} answers {@code 401} to an anonymous request instead of a redirect,
 * and {@code 403} to one without the role; the page treats both as "sign in again".</li>
 * <li>Roles are enforced on the server twice: any {@code /admin/**} request must be
 * authenticated here, and admin-only operations carry {@code @PreAuthorize} on the method.
 * The page hides what a role cannot do; that is presentation, not the control.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class AdminSecurityConfiguration {

    public static final String ADMIN_PATH = "/admin";
    public static final String LOGIN_PATH = ADMIN_PATH + "/login";
    public static final String LOGOUT_PATH = ADMIN_PATH + "/logout";
    public static final String API_PATH = ADMIN_PATH + "/api";

    @Bean
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, AdminAudit audit) throws Exception {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        RequestMatcher api = path.matcher(API_PATH + "/**");
        // Remember where a person was going only for page requests: an API 401 has no page
        // to return to, and saving one would open a session for every anonymous fetch.
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(new AndRequestMatcher(
                path.matcher(HttpMethod.GET, ADMIN_PATH + "/**"), new NegatedRequestMatcher(api)));

        // Which "not signed in" a request gets is decided by its path, not by its Accept
        // header: the API answers 401, everything else is sent to the login page. Spring
        // Security's own content negotiation would send a fetch() with no Accept header to
        // the page as well.
        DelegatingAuthenticationEntryPoint entryPoint = new DelegatingAuthenticationEntryPoint(
                new LinkedHashMap<>(Map.of(api, new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))));
        entryPoint.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint(LOGIN_PATH));

        http.securityMatcher(ADMIN_PATH + "/**")
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, LOGIN_PATH).permitAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login
                        .loginPage(LOGIN_PATH)
                        .loginProcessingUrl(LOGIN_PATH)
                        .defaultSuccessUrl(ADMIN_PATH, false)
                        .failureUrl(LOGIN_PATH + "?error"))
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_PATH)
                        .logoutSuccessUrl(LOGIN_PATH + "?logout"))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .requestCache(cache -> cache.requestCache(requestCache))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        // A signed-in person refused by role is written to admin_audit before the 403
                        // goes out: a trail that holds only what succeeded is missing exactly the
                        // rows an investigation would open it for. CSRF failures are not refusals of
                        // a person and are not recorded.
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
    AnswerFeedback answerFeedback(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new AnswerFeedback(jdbcTemplate, transactionManager);
    }
}
