package app.simplexdev.ssh4p.ssh;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import app.simplexdev.ssh4p.api.ConsoleStreamPublisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Captures all server console output by attaching a Log4j2 appender to the root
 * logger context. Each SSH session subscribes to {@link #consoleOutput()} and
 * receives lines from the moment it connects forward — this is a hot multicast
 * with no replay of prior output.
 * <p>
 * Uses Log4j2 (not JUL) so that Paper internals, net.minecraft.* output, and
 * command results — all of which bypass java.util.logging — are captured too.
 */
public final class BukkitConsoleStreamPublisher implements ConsoleStreamPublisher {

    private static final String APPENDER_NAME = "SSH4PReactiveConsoleAppender";

    private final Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
    private AbstractAppender appender;

    /**
     * Registers a Log4j2 appender on the root logger context and begins emitting
     * console lines to the reactive sink. Safe to call only once per instance;
     * call {@link #detach()} before re-attaching.
     */
    public void attach() {
        appender = new AbstractAppender(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                sink.tryEmitNext(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();

        LoggerContext context = LoggerContext.getContext(false);
        Configuration config = context.getConfiguration();
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, Level.ALL, null);
        context.updateLoggers();
    }

    /**
     * Removes the appender from the root logger context, stops it, and completes
     * the reactive sink so all active subscribers receive an {@code onComplete} signal.
     * No-op if {@link #attach()} was never called.
     */
    public void detach() {
        if (appender == null) return;

        LoggerContext context = LoggerContext.getContext(false);
        Configuration config = context.getConfiguration();
        config.getRootLogger().removeAppender(APPENDER_NAME);
        context.updateLoggers();
        appender.stop();
        appender = null;

        sink.tryEmitComplete();
    }

    /**
     * Returns the hot multicast stream of console lines. New subscribers only
     * receive lines emitted after the point of subscription; there is no replay
     * of prior output.
     */
    @Override
    public Flux<String> consoleOutput() {
        return sink.asFlux();
    }
}
