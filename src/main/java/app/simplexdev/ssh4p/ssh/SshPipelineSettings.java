package app.simplexdev.ssh4p.ssh;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * SSH pipeline configuration record loaded from plugin.yml.
 * <p>
 * Holds the port, host key file name, and max session configuration.
 * Load settings via {@link #fromConfig(FileConfiguration)}.
 */
public record SshPipelineSettings(
	String bindAddress,
	int port,
	String hostKeyFileName,
	int maxSessions
) {
	/**
	 * Loads SSH settings from the plugin's FileConfiguration.
	 *
	 * @param config the Bukkit file configuration
	 * @return settings loaded from config with defaults as fallback
	 */
	public static SshPipelineSettings fromConfig(FileConfiguration config) {
		String rawBind = config.getString("ssh.bind-address", "").strip();
		return new SshPipelineSettings(
			rawBind.isEmpty() ? "0.0.0.0" : rawBind,
			config.getInt("ssh.port", 2222),
			config.getString("ssh.host-key-file", "hostkey.ser"),
			config.getInt("ssh.max-sessions", 3)
		);
	}
}
