package app.simplexdev.ssh4p;

import app.simplexdev.ssh4p.httpd.HttpPipelineBootstrap;
import app.simplexdev.ssh4p.ssh.SshKeysManager;
import app.simplexdev.ssh4p.ssh.SshPipelineBootstrap;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SSH4P — SSH console and HTTP API plugin for Paper servers.
 * <p>
 * Initialises a shared {@link SshKeysManager} on enable, then starts both the
 * SSH and HTTP pipelines. Either pipeline can run in dedicated-port mode or share
 * the Minecraft port via protocol multiplexing ({@code port: -1}).
 */
public final class SSH4P extends JavaPlugin {

    private SshKeysManager keysManager;
    private HttpPipelineBootstrap httpPipelineBootstrap;
    private SshPipelineBootstrap sshPipelineBootstrap;

    @Override
    public void onEnable() {
        SSHLogger.init(this);
        saveDefaultConfig();
        saveResource("ssh_keys.json", false);

        keysManager = new SshKeysManager(getDataFolder());
        keysManager.load();

        if (getConfig().getBoolean("http.enabled")) {
            httpPipelineBootstrap = new HttpPipelineBootstrap(this);
            httpPipelineBootstrap.start(keysManager);
        }

        if (getConfig().getBoolean("ssh.enabled")) {
            sshPipelineBootstrap = new SshPipelineBootstrap(this, httpPipelineBootstrap != null ? httpPipelineBootstrap.pipelineInitializer() : null);
            sshPipelineBootstrap.start(keysManager);
        }

        new SSH4PCommand(this);

        SSHLogger.get().info("SSH4P bootstrap complete.");
    }

    @Override
    public void onDisable() {
        if (sshPipelineBootstrap != null) sshPipelineBootstrap.stop();
        if (httpPipelineBootstrap != null) httpPipelineBootstrap.stop();
        saveConfig();
        SSHLogger.get().info("SSH4P shutdown complete.");
    }

    /**
     * Returns the active {@link SshPipelineBootstrap}, or {@code null} if SSH is disabled in config.
     * Used by {@link SSH4PCommand} to reach the purge operations.
     */
    public SshPipelineBootstrap getSshPipelineBootstrap() {
        return sshPipelineBootstrap;
    }
}
