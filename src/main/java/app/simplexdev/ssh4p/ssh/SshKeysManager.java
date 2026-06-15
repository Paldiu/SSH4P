package app.simplexdev.ssh4p.ssh;

import app.simplexdev.ssh4p.SSHLogger;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code ssh_keys.json} from the plugin's data folder.
 * <p>
 * Each entry in the file represents one authorised user:
 * <pre>
 * [
 *   {
 *     "username":   "admin",
 *     "last_login": null,
 *     "public_key": "ssh-rsa AAAA..."
 *   }
 * ]
 * </pre>
 * {@code last_login} is updated in-place on every successful authentication.
 * All public methods are {@code synchronized} so concurrent auth attempts
 * (multiple clients connecting at the same moment) never corrupt the list.
 */
public final class SshKeysManager {

    /**
     * Maps directly to one object in {@code ssh_keys.json}.
     * Field names follow {@link FieldNamingPolicy#LOWER_CASE_WITH_UNDERSCORES}:
     * {@code lastLogin → last_login}, {@code publicKey → public_key}.
     */
    public static final class SshKeyEntry {
        private String username;
        private String lastLogin;
        private String publicKey;

        /** Returns the username that identifies this entry. */
        public String getUsername() { return username; }

        /** Returns the ISO-8601 UTC timestamp of the last successful login, or {@code null} if never logged in. */
        public String getLastLogin() { return lastLogin; }

        /** Returns the full authorized-key line, e.g. {@code "ssh-ed25519 AAAA... comment"}. */
        public String getPublicKey() { return publicKey; }
    }

    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<SshKeyEntry>>() {}.getType();

    private final File keysFile;
    private final Gson gson;
    private final List<SshKeyEntry> entries = new ArrayList<>();

    /**
     * @param dataFolder the plugin's data folder; {@code ssh_keys.json} is read from and
     *                   written to this directory
     */
    public SshKeysManager(File dataFolder) {
        this.keysFile = new File(dataFolder, "ssh_keys.json");
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .serializeNulls()
            .create();
    }

    /**
     * Loads entries from {@code ssh_keys.json}. Creates an empty file if none exists.
     * Call this once during {@code SshPipelineBootstrap.start()}.
     */
    public synchronized void load() {
        entries.clear();

        if (!keysFile.exists()) {
            SSHLogger.get().warn(
                "ssh_keys.json not found — creating empty file. "
                + "Replace the placeholder entries with real public keys to grant SSH access."
            );
            save();
            return;
        }

        try (FileReader reader = new FileReader(keysFile, StandardCharsets.UTF_8)) {
            List<SshKeyEntry> loaded = gson.fromJson(reader, ENTRY_LIST_TYPE);
            if (loaded != null) entries.addAll(loaded);
            SSHLogger.get().info("Loaded " + entries.size() + " SSH key entry(ies) from ssh_keys.json.");
        } catch (IOException e) {
            SSHLogger.get().error("Failed to read ssh_keys.json.", e);
        }
    }

    /** Returns an immutable snapshot of all entries currently in the keystore. */
    public synchronized List<SshKeyEntry> getAllEntries() {
        return List.copyOf(entries);
    }

    /** Returns the entry for {@code username}, or empty if no such entry exists. */
    public synchronized Optional<SshKeyEntry> findByUsername(String username) {
        return entries.stream()
            .filter(e -> username.equals(e.username))
            .findFirst();
    }

    /**
     * Stamps {@code last_login} with the current UTC time and persists the file.
     * Called after a successful public-key authentication.
     */
    public synchronized void recordLogin(String username) {
        entries.stream()
            .filter(e -> username.equals(e.username))
            .findFirst()
            .ifPresent(e -> {
                e.lastLogin = Instant.now().toString();
                save();
            });
    }

    private void save() {
        try (FileWriter writer = new FileWriter(keysFile, StandardCharsets.UTF_8)) {
            gson.toJson(entries, writer);
        } catch (IOException e) {
            SSHLogger.get().error("Failed to write ssh_keys.json.", e);
        }
    }
}
