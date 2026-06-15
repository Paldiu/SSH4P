package app.simplexdev.ssh4p.httpd.auth;

import io.netty.handler.codec.http.FullHttpRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for active HTTP sessions.
 * <p>
 * Sessions are identified by opaque UUID bearer tokens with a fixed 24-hour TTL.
 * Expired entries are evicted lazily on the next access for that token rather than
 * by a background sweep. All public methods are safe to call from any thread.
 */
public final class HttpSessionStore {

    private final ConcurrentHashMap<String, HttpSession> sessions = new ConcurrentHashMap<>();

    /**
     * Creates a new session for {@code username}, stores it, and returns it.
     * The session carries a freshly generated UUID token and expires 24 hours
     * from the moment of this call.
     *
     * @param username the authenticated SSH username to associate with the session
     * @return the new {@link HttpSession}, including the bearer token to send to the client
     */
    public HttpSession issue(String username) {
        String token = UUID.randomUUID().toString();
        var session = new HttpSession(token, username, Instant.now().plus(24, ChronoUnit.HOURS));
        sessions.put(token, session);
        return session;
    }

    /**
     * Returns the session for {@code token} if it exists and has not expired.
     * Expired sessions are evicted from the store on this call.
     *
     * @param token the bearer token to look up; {@code null} returns empty
     * @return the live session, or empty if the token is unknown, null, or expired
     */
    public Optional<HttpSession> validate(String token) {
        if (token == null) return Optional.empty();
        HttpSession session = sessions.get(token);
        if (session == null || session.isExpired()) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * Removes the session for {@code token}, logging the client out immediately.
     * No-op if the token is unknown or {@code null}.
     *
     * @param token the bearer token to invalidate
     */
    public void invalidate(String token) {
        if (token != null) sessions.remove(token);
    }

    /**
     * Extracts the bearer token from the request's {@code Authorization} header
     * and delegates to {@link #validate(String)}.
     * <p>
     * Expects the header to be in the form {@code Bearer <token>}; returns empty
     * for any other format or if the header is absent.
     *
     * @param request the inbound HTTP request to inspect
     * @return the validated session, or empty if authentication is missing or invalid
     */
    public Optional<HttpSession> fromRequest(FullHttpRequest request) {
        String header = request.headers().get("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
        return validate(header.substring(7));
    }
}
