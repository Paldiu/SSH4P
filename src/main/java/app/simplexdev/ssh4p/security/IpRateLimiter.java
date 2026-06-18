package app.simplexdev.ssh4p.security;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window rate limiter keyed by IP address.
 * Each IP gets {@code maxPerWindow} tokens; the window resets after
 * {@code windowSeconds} seconds. All operations are lock-free.
 * <p>
 * <strong>Eviction / memory bound:</strong> the internal bucket map is capped at
 * {@value #MAX_BUCKETS} entries. When the cap is reached, {@link #sweepExpired()}
 * is called automatically to remove stale buckets. If the map is still at capacity
 * after the sweep, the request is rate-limited rather than adding a new entry —
 * this protects against a memory-exhaustion DoS from a flood of distinct source IPs.
 * Callers may also schedule {@link #sweepExpired()} on a background timer for
 * proactive eviction.
 */
public final class IpRateLimiter {

    /** Maximum number of distinct IP buckets before automatic eviction kicks in. */
    private static final int MAX_BUCKETS = 10_000;

    private static final class Bucket {
        final AtomicInteger remaining;
        final Instant resetAt;

        Bucket(int max, Instant resetAt) {
            this.remaining = new AtomicInteger(max);
            this.resetAt = resetAt;
        }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int maxPerWindow;
    private final long windowSeconds;

    /**
     * @param maxPerWindow  maximum requests allowed per IP within each window
     * @param windowSeconds duration of each window in seconds
     */
    public IpRateLimiter(int maxPerWindow, long windowSeconds) {
        this.maxPerWindow = maxPerWindow;
        this.windowSeconds = windowSeconds;
    }

    /**
     * Attempts to consume one token for {@code ip}.
     *
     * @param ip the remote IP address
     * @return {@code true} if the request is within the limit; {@code false} if rate-limited
     */
    public boolean tryAcquire(String ip) {
        Instant now = Instant.now();

        Bucket existing = buckets.get(ip);
        if (existing != null && !now.isAfter(existing.resetAt)) {
            return existing.remaining.getAndDecrement() > 0;
        }

        if (buckets.size() >= MAX_BUCKETS) {
            sweepExpired();
            if (buckets.size() >= MAX_BUCKETS && !buckets.containsKey(ip)) {
                return false;
            }
        }

        Bucket bucket = buckets.compute(ip, (k, ex) -> {
            if (ex == null || now.isAfter(ex.resetAt)) {
                return new Bucket(maxPerWindow, now.plusSeconds(windowSeconds));
            }
            return ex;
        });
        return bucket.remaining.getAndDecrement() > 0;
    }

    /**
     * Removes all buckets whose rate-limit window has already expired.
     * Safe to call from any thread and from a background scheduler for proactive eviction.
     */
    public void sweepExpired() {
        Instant now = Instant.now();
        buckets.entrySet().removeIf(e -> now.isAfter(e.getValue().resetAt));
    }

    /**
     * Extracts the host portion from a Netty remote-address string.
     * <p>
     * Handles:
     * <ul>
     *   <li>IPv4 with port: {@code "/192.168.1.5:49320"} → {@code "192.168.1.5"}</li>
     *   <li>Bracketed IPv6 with port: {@code "/[::1]:49320"} → {@code "[::1]"}</li>
     *   <li>Bare IPv6 (no port): {@code "::1"} → {@code "::1"}</li>
     * </ul>
     * Using {@code lastIndexOf(':')} alone mishandles unbracketed IPv6 literals because
     * the last {@code :} in {@code "2001:db8::1:49320"} is part of the address, not the
     * port separator. Bracketed form is detected first to avoid this ambiguity.
     */
    public static String extractIp(String remoteAddress) {
        if (remoteAddress == null) return "unknown";
        String s = remoteAddress.replace("/", "");
        if (s.startsWith("[")) {
            int bracket = s.indexOf(']');
            return bracket >= 0 ? s.substring(0, bracket + 1) : s;
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            return s.substring(0, colon);
        }
        return s;
    }
}
