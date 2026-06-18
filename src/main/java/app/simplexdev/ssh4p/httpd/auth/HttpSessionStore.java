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
        HttpSession session = new HttpSession(token, username, Instant.now().plus(24, ChronoUnit.HOURS));
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
     * Resets the TTL of an existing session to 24 hours from now (sliding-window renewal).
     * Returns the updated session, or empty if the token is unknown or already expired.
     * Expired sessions are evicted from the store on this call.
     *
     * @param token the bearer token to refresh; {@code null} returns empty
     * @return the refreshed session with a new {@code expiresAt}, or empty if not found/expired
     */
    public Optional<HttpSession> refresh(String token) {
        if (token == null) return Optional.empty();
        HttpSession old = sessions.get(token);
        if (old == null || old.isExpired()) {
            sessions.remove(token);
            return Optional.empty();
        }
        HttpSession refreshed = new HttpSession(old.token(), old.username(),
            Instant.now().plus(24, ChronoUnit.HOURS));
        sessions.put(token, refreshed);
        return Optional.of(refreshed);
    }

    /**
     * Removes all sessions that have passed their {@code expiresAt} time.
     * Expired sessions are also evicted lazily on individual {@link #validate} calls, so calling
     * this method is optional — it is intended for periodic background sweeps to bound memory use
     * when many sessions have been issued over a long uptime.
     */
    public void sweepExpired() {
        sessions.entrySet().removeIf(e -> e.getValue().isExpired());
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
