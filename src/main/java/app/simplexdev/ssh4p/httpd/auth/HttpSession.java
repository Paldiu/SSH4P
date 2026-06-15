package app.simplexdev.ssh4p.httpd.auth;

import java.time.Instant;

/**
 * Immutable snapshot of an active HTTP session issued by {@link HttpSessionStore}.
 * <p>
 * The frontend carries the {@link #token()} as a {@code Bearer} token in the
 * {@code Authorization} header. Sessions expire 24 hours after creation; callers
 * should check {@link #isExpired()} before treating the session as authoritative,
 * though {@link HttpSessionStore#validate(String)} handles this automatically.
 *
 * @param token     the opaque UUID bearer token sent by the client
 * @param username  the SSH username whose public key was matched at login
 * @param expiresAt the UTC instant at which this session becomes invalid
 */
public record HttpSession(String token, String username, Instant expiresAt) {

    /**
     * Returns {@code true} if this session has passed its expiry time and should
     * no longer be accepted.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
