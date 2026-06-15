package app.simplexdev.ssh4p.httpd;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable configuration record for the HTTP pipeline, loaded from the
 * {@code http:} block in {@code config.yml}.
 * <p>
 * A blank or absent {@code bind-address} is normalised to {@code 0.0.0.0}.
 * Setting {@code port: -1} selects multiplexed mode, where the HTTP server
 * shares the Minecraft port via {@link app.simplexdev.ssh4p.multiplexer.ProtocolMultiplexerHandler}.
 * Any other port value binds a dedicated Netty server on that port.
 */
public record HttpPipelineSettings(
    String bindAddress,
    int port,
    int maxContentLength,
    List<String> publicExtensions,
    List<String> privateExtensions
) {

    /**
     * Reads HTTP settings from the supplied Bukkit configuration.
     * <p>
     * Defaults: bind-address {@code 0.0.0.0}, port {@code -1},
     * max-content-length {@code 1 048 576} bytes, empty extension lists.
     *
     * @param config the plugin's active {@link FileConfiguration}
     * @return a fully populated settings record
     */
    public static HttpPipelineSettings fromConfig(FileConfiguration config) {
        String raw = config.getString("http.bind-address", "").strip();
        return new HttpPipelineSettings(
            raw.isEmpty() ? "0.0.0.0" : raw,
            config.getInt("http.port", -1),
            config.getInt("http.max-content-length", 1_048_576),
            config.getStringList("http.endpoints.public-extensions"),
            config.getStringList("http.endpoints.private-extensions")
        );
    }

    /**
     * Returns {@code true} if {@code ext} appears in the {@code public-extensions}
     * list, meaning the file can be read without authentication.
     *
     * @param ext the file extension without the leading dot (e.g. {@code "txt"})
     */
    public boolean isPublicExtension(String ext) {
        return publicExtensions.contains(ext.toLowerCase());
    }

    /**
     * Returns {@code true} if {@code ext} appears in the {@code private-extensions}
     * list, meaning the file requires a valid bearer session to read or write.
     *
     * @param ext the file extension without the leading dot (e.g. {@code "json"})
     */
    public boolean isPrivateExtension(String ext) {
        return privateExtensions.contains(ext.toLowerCase());
    }
}
