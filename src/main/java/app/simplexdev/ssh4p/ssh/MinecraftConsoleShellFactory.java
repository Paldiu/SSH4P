package app.simplexdev.ssh4p.ssh;

import app.simplexdev.ssh4p.api.ConsoleStreamPublisher;
import app.simplexdev.ssh4p.api.MainThreadCommandBridge;
import java.io.IOException;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.ShellFactory;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Factory for creating MinecraftConsoleShell instances for each SSH session.
 * <p>
 * Passed to the SSHD server to instantiate a new shell command for each channel.
 */
public final class MinecraftConsoleShellFactory implements ShellFactory {

    private final JavaPlugin plugin;
    private final MainThreadCommandBridge commandBridge;
    private final ConsoleStreamPublisher streamPublisher;
    private final SshSessionController sessionController;

    /**
     * Constructs a new shell factory with the required dependencies.
     *
     * @param plugin the Bukkit plugin instance
     * @param commandBridge the command dispatcher
     * @param streamPublisher the console stream source
     * @param sessionController the session capacity manager
     */
    public MinecraftConsoleShellFactory(
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

    /**
     * Creates a new MinecraftConsoleShell for the given SSH channel.
     *
     * @param channel the SSH channel session
     * @return a new console shell instance
     * @throws IOException if channel setup fails
     */
    @Override
    public Command createShell(ChannelSession channel) throws IOException {
        return new MinecraftConsoleShell(plugin, commandBridge, streamPublisher, sessionController);
    }
}
