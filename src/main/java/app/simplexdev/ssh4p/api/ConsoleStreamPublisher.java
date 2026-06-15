package app.simplexdev.ssh4p.api;

import reactor.core.publisher.Flux;

/**
 * Publishes server console output as a reactive stream.
 * <p>
 * Implementations attach to the server's logging infrastructure and emit each
 * console line to subscribers. This is a hot stream with no replay — subscribers
 * only receive lines emitted after subscription.
 */
public interface ConsoleStreamPublisher {
    /**
     * Returns a reactive stream of console output lines.
     *
     * @return a Flux emitting each console line as it is output
     */
    Flux<String> consoleOutput();
}
