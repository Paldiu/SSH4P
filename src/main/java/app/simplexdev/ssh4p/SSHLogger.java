package app.simplexdev.ssh4p;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

/**
 * Central logger for SSH4P. Wraps the plugin's SLF4J logger so every class in
 * the plugin can log through one place without needing to carry a JavaPlugin
 * reference around just to call getLogger().
 *
 * Initialise once in SSH4P.onEnable() via {@link #init(SSH4P)}, then call
 * {@link #get()} from anywhere in the plugin.
 *
 * The debug level is off by default. Toggle it at runtime via
 * {@link #setDebugEnabled(boolean)} (e.g. from a future /ssh4p debug command).
 */
public final class SSHLogger {

    private static SSHLogger instance;

    private final Logger logger;
    private volatile boolean debugEnabled;

    private SSHLogger(Logger logger) {
        this.logger = logger;
    }

    /**
     * Initialises the singleton from the plugin's SLF4J logger. Must be called
     * exactly once, in {@code SSH4P.onEnable()}, before any other class calls {@link #get()}.
     *
     * @param plugin the plugin whose logger is adopted
     */
    public static void init(SSH4P plugin) {
        instance = new SSHLogger(plugin.getSLF4JLogger());
    }

    /**
     * Returns the singleton instance.
     *
     * @return the logger
     * @throws IllegalStateException if {@link #init} has not been called yet
     */
    public static SSHLogger get() {
        if (instance == null) {
            throw new IllegalStateException("SSHLogger.init() has not been called.");
        }
        return instance;
    }

    /** Logs an informational message. */
    public void info(String message) {
        logger.info(message);
    }

    /** Logs a warning. */
    public void warn(String message) {
        logger.warn(message);
    }

    /**
     * Logs a warning with a full stack trace appended to the message.
     *
     * @param message human-readable context
     * @param cause   the throwable whose stack trace is included
     */
    public void warn(String message, Throwable cause) {
        logger.warn("{}\n{}", message, ExceptionUtils.getStackTrace(cause));
    }

    /** Logs an error. */
    public void error(String message) {
        logger.error(message);
    }

    /**
     * Logs an error with a full stack trace appended to the message.
     *
     * @param message human-readable context
     * @param cause   the throwable whose stack trace is included
     */
    public void error(String message, Throwable cause) {
        logger.error("{}\n{}", message, ExceptionUtils.getStackTrace(cause));
    }

    /**
     * Logs a debug message. No-op unless {@link #setDebugEnabled(boolean)} has
     * been called with {@code true}.
     *
     * @param message the debug message
     */
    public void debug(String message) {
        if (debugEnabled) {
            logger.debug(message);
        }
    }

    /**
     * Enables or disables debug-level output at runtime.
     *
     * @param enabled {@code true} to emit debug messages; {@code false} to suppress them
     */
    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
    }

    /** Returns {@code true} if debug output is currently enabled. */
    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * Emits a structured audit record at INFO level, prefixed with {@code [AUDIT]}.
     * Every command dispatched via SSH or HTTP writes one line here so there is a
     * single, grep-able trail of who ran what and when.
     *
     * @param message the audit message (typically "SSH [user@ip] > command" or "HTTP [user] > command")
     */
    public void audit(String message) {
        logger.info("[AUDIT] {}", message);
    }
}
