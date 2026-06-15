package app.simplexdev.ssh4p.ssh;

import app.simplexdev.ssh4p.SSHLogger;
import app.simplexdev.ssh4p.api.MainThreadCommandBridge;
import app.simplexdev.ssh4p.multiplexer.NettyPipelineInjector;
import io.netty.channel.Channel;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.List;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bootstraps and manages the SSH server pipeline.
 * <p>
 * Supports two operating modes selected by the {@code ssh.port} config value:
 * <ul>
 *   <li><b>Dedicated mode</b> (any positive port number) — SSHD binds directly
 *       to the configured host and port, independently of Minecraft.</li>
 *   <li><b>Multiplexed mode</b> ({@code port: -1}) — SSHD binds to a loopback
 *       ephemeral port only. A handler is injected into Paper's Netty accept
 *       pipeline via reflection; connections that open with the SSH banner
 *       ({@code SSH-}) are proxied through to SSHD, while all other connections
 *       flow through the normal Minecraft pipeline untouched.</li>
 * </ul>
 */
public final class SshPipelineBootstrap {

    private final JavaPlugin plugin;
    private SshServer sshServer;
    private BukkitConsoleStreamPublisher streamPublisher;
    /** Server channels we injected into; empty when running in dedicated mode. */
    private List<Channel> injectedChannels = Collections.emptyList();

    public SshPipelineBootstrap(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the SSH server and all supporting components.
     */
    public void start() {
        var settings = SshPipelineSettings.fromConfig(plugin.getConfig());

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            SSHLogger.get().warn("Could not create plugin data folder for SSH assets.");
        }

        var keysManager = new SshKeysManager(plugin.getDataFolder());
        keysManager.load();

        MainThreadCommandBridge commandBridge = new BukkitMainThreadCommandBridge();
        var sessionController = new SshSessionController(settings.maxSessions());

        streamPublisher = new BukkitConsoleStreamPublisher();
        streamPublisher.attach();

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(
            new File(plugin.getDataFolder(), settings.hostKeyFileName()).toPath()
        ));
        sshServer.setPublickeyAuthenticator(new PublicKeyAuthenticator(keysManager));
        sshServer.setShellFactory(
            new MinecraftConsoleShellFactory(plugin, commandBridge, streamPublisher, sessionController)
        );

        if (settings.port() == -1) {
            startMultiplexed(settings);
        } else {
            startDedicated(settings);
        }
    }

    /**
     * Binds SSHD to a loopback-only ephemeral port, then injects a handler into
     * Paper's Netty accept pipeline so SSH connections on the MC port are
     * transparently proxied to SSHD. Minecraft traffic is unaffected.
     */
    private void startMultiplexed(SshPipelineSettings settings) {
        try {
            int loopbackPort = findFreeLoopbackPort();
            sshServer.setHost("127.0.0.1");
            sshServer.setPort(loopbackPort);
            sshServer.start();

            injectedChannels = NettyPipelineInjector.inject(loopbackPort);

            SSHLogger.get().info(
                "SSH multiplexer active: SSH connections on MC port "
                + plugin.getServer().getPort()
                + " → SSHD on loopback:" + loopbackPort
                + " (injected into " + injectedChannels.size() + " server channel(s)"
                + ", max " + settings.maxSessions() + " concurrent sessions)."
            );
        } catch (Exception e) {
            SSHLogger.get().error("Failed to start SSH server in multiplexed mode.", e);
            tearDownPublisher();
        }
    }

    /**
     * Binds SSHD directly to the configured host and port.
     */
    private void startDedicated(SshPipelineSettings settings) {
        sshServer.setHost(settings.bindAddress());
        sshServer.setPort(settings.port());
        try {
            sshServer.start();
            SSHLogger.get().info(
                "SSH console listening on " + settings.bindAddress() + ":" + settings.port()
                + " (max " + settings.maxSessions() + " concurrent sessions)."
            );
        } catch (Exception e) {
            SSHLogger.get().error(
                "Failed to start SSH server on " + settings.bindAddress() + ":" + settings.port() + ".", e);
            tearDownPublisher();
        }
    }

    /**
     * Disconnects all active SSH sessions for the given username.
     *
     * @param username the SSH username to terminate
     * @return the number of sessions that were disconnected
     */
    public int purgeUser(String username) {
        if (sshServer == null || !sshServer.isStarted()) return 0;
        int count = 0;
        for (var session : sshServer.getActiveSessions()) {
            if (username.equalsIgnoreCase(session.getUsername())) {
                try {
                    session.disconnect(SshConstants.SSH2_DISCONNECT_BY_APPLICATION, "Purged by administrator");
                    count++;
                } catch (Exception e) {
                    SSHLogger.get().warn("Failed to disconnect session for user " + username + ": " + e.getMessage());
                }
            }
        }
        return count;
    }

    /**
     * Disconnects all active SSH sessions.
     *
     * @return the number of sessions that were disconnected
     */
    public int purgeAll() {
        if (sshServer == null || !sshServer.isStarted()) return 0;
        int count = 0;
        for (var session : sshServer.getActiveSessions()) {
            try {
                session.disconnect(SshConstants.SSH2_DISCONNECT_BY_APPLICATION, "All sessions purged by administrator");
                count++;
            } catch (Exception e) {
                SSHLogger.get().warn("Failed to disconnect SSH session: " + e.getMessage());
            }
        }
        return count;
    }

    /**
     * Stops the SSH server, ejects the Netty multiplexer if active, and
     * detaches the console stream publisher.
     */
    public void stop() {
        NettyPipelineInjector.eject(injectedChannels);
        injectedChannels = Collections.emptyList();

        if (sshServer != null && sshServer.isStarted()) {
            try {
                sshServer.close(true);
            } catch (Exception e) {
                SSHLogger.get().warn("Failed to close SSH server gracefully.", e);
            }
        }

        tearDownPublisher();
    }

    private void tearDownPublisher() {
        if (streamPublisher != null) {
            streamPublisher.detach();
            streamPublisher = null;
        }
    }

    /**
     * Finds a free port on the loopback interface by briefly binding to port 0
     * and reading the OS-assigned port, then releasing the socket.
     */
    private static int findFreeLoopbackPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
