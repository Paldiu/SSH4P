package app.simplexdev.ssh4p.ssh;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controls access to the SSH console by capping the number of concurrent sessions.
 * <p>
 * Slot acquisition uses a CAS loop so no thread ever blocks waiting for a lock —
 * {@link #tryAcquire} either succeeds immediately or returns empty. Each acquired
 * slot is represented by a {@link SessionInfo} that the holder must pass back to
 * {@link #release} when the session ends. Passing the record object (not just an
 * address string) makes release idempotent and prevents double-counting when the
 * same IP holds multiple concurrent slots.
 */
public final class SshSessionController {

    /**
     * Immutable snapshot of an active session, handed back to the caller on
     * {@link #tryAcquire} and used as the identity token for {@link #release}.
     */
    public record SessionInfo(UUID id, String remoteAddress, Instant connectedAt) {}

    private final int maxSessions;
    private final AtomicInteger count = new AtomicInteger(0);
    private final CopyOnWriteArrayList<SessionInfo> sessions = new CopyOnWriteArrayList<>();

    /**
     * @param maxSessions the maximum number of simultaneously active sessions
     */
    public SshSessionController(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    /**
     * Attempts to acquire a session slot for the given remote address.
     *
     * @return a {@link SessionInfo} if the slot was acquired, or empty if the server is full
     */
    public Optional<SessionInfo> tryAcquire(String remoteAddress) {
        int current;
        do {
            current = count.get();
            if (current >= maxSessions) return Optional.empty();
        } while (!count.compareAndSet(current, current + 1));

        var info = new SessionInfo(UUID.randomUUID(), remoteAddress, Instant.now());
        sessions.add(info);
        return Optional.of(info);
    }

    /**
     * Releases the slot previously acquired for {@code info}.
     * Safe to call more than once — only the first call has any effect.
     */
    public void release(SessionInfo info) {
        if (sessions.remove(info)) {
            count.decrementAndGet();
        }
    }

    /** Returns the maximum number of concurrent sessions allowed. */
    public int getMaxSessions() {
        return maxSessions;
    }

    /** Returns the current number of active sessions. */
    public int getSessionCount() {
        return count.get();
    }

    /** Returns {@code true} if no further sessions can be accepted. */
    public boolean isFull() {
        return count.get() >= maxSessions;
    }

    /**
     * Returns an immutable snapshot of the currently active sessions.
     * The returned list is a copy and is not updated as sessions change.
     */
    public List<SessionInfo> getSessions() {
        return List.copyOf(sessions);
    }
}
