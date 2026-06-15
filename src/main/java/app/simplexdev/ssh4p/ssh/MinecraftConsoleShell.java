package app.simplexdev.ssh4p.ssh;

import app.simplexdev.ssh4p.SSHLogger;
import app.simplexdev.ssh4p.api.ConsoleStreamPublisher;
import app.simplexdev.ssh4p.api.MainThreadCommandBridge;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * SSH shell command that bridges client input/output to Bukkit's main thread console.
 * <p>
 * All SSH output is funnelled through {@link #sessionOutput} and {@link #sessionErrors}
 * sinks — each with a single subscriber that writes to their respective PrintWriter.
 * This design eliminates concurrent-write corruption and maintains clear separation
 * between stdout and stderr streams.
 * <p>
 * Session slot management is thread-safe via {@link #released} atomic flag, preventing
 * double-release when both {@code onSessionEnd()} and {@code destroy()} are called.
 */
public final class MinecraftConsoleShell implements Command {

    private final JavaPlugin plugin;
    private final MainThreadCommandBridge commandBridge;
    private final ConsoleStreamPublisher streamPublisher;
    private final SshSessionController sessionController;
    private final Sinks.Many<String> sessionOutput = Sinks.many().multicast().onBackpressureBuffer();
    private final Sinks.Many<String> sessionErrors = Sinks.many().multicast().onBackpressureBuffer();
    private final AtomicBoolean released = new AtomicBoolean(false);

    private InputStream inputStream;
    private OutputStream outputStream;
    private OutputStream errorStream;
    private ExitCallback exitCallback;
    private PrintWriter writer;
    private PrintWriter errorWriter;

    private Disposable sessionSubscription;
    private Disposable outputSubscription;
    private Disposable errorSubscription;
    private Disposable drainSubscription;
    private Disposable errorDrainSubscription;
    private SshSessionController.SessionInfo sessionInfo;

    /**
     * @param plugin            the plugin, used to obtain the Bukkit main-thread executor
     * @param commandBridge     dispatches user input to the server console on the main thread
     * @param streamPublisher   source of server console lines forwarded to the SSH client
     * @param sessionController gate-keeper for the maximum concurrent session count
     */
    public MinecraftConsoleShell(
        JavaPlugin plugin,
        MainThreadCommandBridge commandBridge,
        ConsoleStreamPublisher streamPublisher,
        SshSessionController sessionController
    ) {
        this.plugin = plugin;
        this.commandBridge = commandBridge;
        this.streamPublisher = streamPublisher;
        this.sessionController = sessionController;
    }

    /** Called by the SSHD framework before {@link #start} to provide the client's input stream. */
    @Override
    public void setInputStream(InputStream in) {
        this.inputStream = in;
    }

    /** Called by the SSHD framework before {@link #start} to provide the client's stdout stream. */
    @Override
    public void setOutputStream(OutputStream out) {
        this.outputStream = out;
    }

    /** Called by the SSHD framework before {@link #start} to provide the client's stderr stream. */
    @Override
    public void setErrorStream(OutputStream err) {
        this.errorStream = err;
    }

    /** Called by the SSHD framework before {@link #start} to register the exit signal callback. */
    @Override
    public void setExitCallback(ExitCallback callback) {
        this.exitCallback = callback;
    }

    /**
     * Starts the SSH shell session by initializing output streams and setting up reactive pipelines.
     * <p>
     * Creates drain subscriptions for both stdout and stderr (if available). Attempts to acquire a
     * session slot from {@link #sessionController}; on failure, sends capacity-full message and exits.
     * On success, wires console output and client input to their respective reactive streams, publishing
     * client commands to the Bukkit main thread via {@link MainThreadCommandBridge#dispatchAsConsole}.
     *
     * @param channel the SSH channel session
     * @param env the SSH environment
     * @throws IOException if stream setup fails
     */
    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
        writer = new PrintWriter(outputStream, true);
        if (errorStream != null) {
            errorWriter = new PrintWriter(errorStream, true);
        }

        drainSubscription = sessionOutput.asFlux()
            .publishOn(Schedulers.boundedElastic())
            .subscribe(
                line -> { writer.println(line); writer.flush(); },
                err -> SSHLogger.get().warn("SSH output drain error: " + err.getMessage(), err)
            );

        if (errorWriter != null) {
            errorDrainSubscription = sessionErrors.asFlux()
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                    line -> { errorWriter.println(line); errorWriter.flush(); },
                    err -> SSHLogger.get().warn("SSH error drain failure: " + err.getMessage(), err)
                );
        }

        var remoteAddress = channel.getSession().getIoSession().getRemoteAddress().toString();
        var acquired = sessionController.tryAcquire(remoteAddress);

        if (acquired.isEmpty()) {
            sessionOutput.tryEmitNext(
                "Connection refused: server is at maximum capacity ("
                + sessionController.getMaxSessions() + " concurrent sessions)."
            );
            sessionOutput.tryEmitComplete();
            if (exitCallback != null) exitCallback.onExit(1);
            return;
        }

        sessionInfo = acquired.get();

        var mainThread = Schedulers.fromExecutor(
            Bukkit.getScheduler().getMainThreadExecutor(plugin)
        );

        sessionOutput.tryEmitNext("SSH4P — connected. Type commands or 'exit' to disconnect.");
        sessionOutput.tryEmitNext("mc-console> ");

        outputSubscription = streamPublisher.consoleOutput()
            .subscribe(line -> sessionOutput.tryEmitNext(line));

        sessionSubscription = Flux.<String>create(sink -> {
            try (var reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null && !sink.isCancelled()) {
                    sink.next(line.trim());
                }
            } catch (IOException e) {
                if (!sink.isCancelled()) sink.error(e);
            } finally {
                sink.complete();
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .takeWhile(line -> !line.equalsIgnoreCase("exit") && !line.equalsIgnoreCase("disconnect"))
        .filter(line -> !line.isBlank())
        .doOnNext(line -> sessionOutput.tryEmitNext("mc-console> "))
        .doOnComplete(() -> {
            sessionOutput.tryEmitNext("Disconnecting.");
            sessionOutput.tryEmitComplete();
        })
        .publishOn(mainThread)
        .subscribe(
            commandBridge::dispatchAsConsole,
            err -> {
                SSHLogger.get().warn("SSH shell session error: " + err.getMessage(), err);
                sessionErrors.tryEmitNext("[Error] " + err.getMessage());
            },
            this::onSessionEnd
        );
    }

    private void onSessionEnd() {
        if (outputSubscription != null) outputSubscription.dispose();
        if (errorSubscription != null) errorSubscription.dispose();
        sessionErrors.tryEmitComplete();
        releaseSlot();
        if (exitCallback != null) exitCallback.onExit(0);
    }

    /**
     * Called by SSH server on channel termination. Disposes all subscriptions,
     * completes both output streams, and releases the session slot.
     *
     * @param channel the closing SSH channel
     */
    @Override
    public void destroy(ChannelSession channel) {
        if (sessionSubscription != null) sessionSubscription.dispose();
        if (outputSubscription != null) outputSubscription.dispose();
        if (errorSubscription != null) errorSubscription.dispose();
        if (drainSubscription != null) drainSubscription.dispose();
        if (errorDrainSubscription != null) errorDrainSubscription.dispose();
        sessionOutput.tryEmitComplete();
        sessionErrors.tryEmitComplete();
        releaseSlot();
    }

    private void releaseSlot() {
        if (sessionInfo != null && released.compareAndSet(false, true)) {
            sessionController.release(sessionInfo);
        }
    }
}
