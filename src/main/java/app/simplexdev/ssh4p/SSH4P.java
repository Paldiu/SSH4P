package app.simplexdev.ssh4p;

import app.simplexdev.ssh4p.ssh.SshPipelineBootstrap;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SSH4P — SSH console plugin for Bukkit servers.
 * <p>
 * Provides secure SSH access to the server console with public-key authentication.
 * Initializes the SSH server pipeline on enable and cleanly shuts it down on disable.
 */
public final class SSH4P extends JavaPlugin {

    private SshPipelineBootstrap sshPipelineBootstrap;

    /**
     * Called when the plugin is enabled.
     * Initializes logging, loads config, and starts the SSH server.
     */
    @Override
    public void onEnable() {
        SSHLogger.init(this);
        saveDefaultConfig();
        saveResource("ssh_keys.json", false);
        sshPipelineBootstrap = new SshPipelineBootstrap(this);
        sshPipelineBootstrap.start();
        SSHLogger.get().info("SSH4P bootstrap complete.");
    }

    /**
     * Called when the plugin is disabled.
     * Gracefully shuts down the SSH server.
     */
    @Override
    public void onDisable() {
        if (sshPipelineBootstrap != null) {
            sshPipelineBootstrap.stop();
        }

        this.saveConfig();

        SSHLogger.get().info("SSH4P shutdown complete.");
    }

    public SshPipelineBootstrap getSshPipelineBootstrap() {
        return sshPipelineBootstrap;
    }
}
