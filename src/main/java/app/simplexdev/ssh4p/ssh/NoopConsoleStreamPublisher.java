package app.simplexdev.ssh4p.ssh;

import app.simplexdev.ssh4p.api.ConsoleStreamPublisher;
import reactor.core.publisher.Flux;

/**
 * No-op implementation of ConsoleStreamPublisher for testing or disabling console capture.
 * Always returns an empty Flux, producing no console output.
 */
public final class NoopConsoleStreamPublisher implements ConsoleStreamPublisher {
    /**
     * Returns an empty stream.
     *
     * @return an empty Flux that emits no lines
     */
    @Override
    public Flux<String> consoleOutput() {
        return Flux.empty();
    }
}
