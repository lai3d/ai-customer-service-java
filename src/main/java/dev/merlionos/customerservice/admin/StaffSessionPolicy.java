package dev.merlionos.customerservice.admin;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The two bounds on a staff session besides the idle timeout, both read from the rows in
 * {@code spring_session} so every replica applies them alike.
 *
 * <ul>
 * <li><b>An absolute lifetime</b>, measured from the session's creation time. A session that
 * is used every few minutes never idles out; without this bound a cookie taken once would
 * work for as long as its owner kept working. Spring Session keeps {@code creationTime}
 * across the sign-in's session-id rotation (the rotation is an {@code UPDATE} of the id on
 * the same row), so the clock starts at the sign-in and nothing later restarts it.</li>
 * <li><b>A concurrent-session limit</b> per account, applied at sign-in: when the account
 * already holds the limit, the least recently used sessions are ended so that, with the new
 * one, the count is the limit. Newest wins, deliberately: refusing the sign-in instead would
 * let whoever holds a stolen cookie keep the owner out, and the owner signing in is the one
 * act that must always succeed. The sessions ended are the ones nobody has used for
 * longest, which are the ones most likely to be forgotten or not the owner's.</li>
 * </ul>
 */
class StaffSessionPolicy {

    private static final Logger log = LoggerFactory.getLogger(StaffSessionPolicy.class);

    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final Duration maxLifetime;
    private final int limit;
    private final Clock clock;

    StaffSessionPolicy(FindByIndexNameSessionRepository<? extends Session> sessions, Duration maxLifetime, int limit) {
        this(sessions, maxLifetime, limit, Clock.systemUTC());
    }

    StaffSessionPolicy(FindByIndexNameSessionRepository<? extends Session> sessions, Duration maxLifetime, int limit,
                       Clock clock) {
        this.sessions = sessions;
        this.maxLifetime = maxLifetime;
        this.limit = limit;
        this.clock = clock;
    }

    Duration maxLifetime() {
        return maxLifetime;
    }

    int limit() {
        return limit;
    }

    /** Whether the session was signed in longer ago than the absolute lifetime allows. */
    boolean outlived(HttpSession session) {
        return Instant.ofEpochMilli(session.getCreationTime()).plus(maxLifetime).isBefore(clock.instant());
    }

    /**
     * Ends the least recently used of the account's sessions so that, counting the one being
     * opened, no more than the limit remain. {@code own} names the signing-in request's own
     * session under every id it has had during the request -- the id before the rotation is
     * still the row's id until the request commits -- so it is neither counted nor ended.
     * Returns the ids ended, oldest first.
     */
    List<String> makeRoom(String username, Set<String> own) {
        List<? extends Session> others = sessions.findByPrincipalName(username).values().stream()
                .filter(session -> !own.contains(session.getId()))
                .sorted(Comparator.comparing(Session::getLastAccessedTime))
                .toList();
        int excess = others.size() - (limit - 1);
        if (excess <= 0) {
            return List.of();
        }
        List<String> ended = others.subList(0, excess).stream().map(Session::getId).toList();
        ended.forEach(sessions::deleteById);
        log.info("Ended {} least recently used session(s) of '{}' at sign-in; the limit is {}", ended.size(), username, limit);
        return ended;
    }
}
