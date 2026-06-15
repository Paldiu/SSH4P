package app.simplexdev.ssh4p.ssh;

import app.simplexdev.ssh4p.SSHLogger;
import java.security.PublicKey;
import java.util.Collections;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;

/**
 * Authenticates SSH clients using public-key auth against entries stored in
 * {@code ssh_keys.json}. Only Ed25519 keys are accepted; any other key type
 * is rejected immediately without consulting the key store.
 * <p>
 * On success, {@link SshKeysManager#recordLogin(String)} stamps the
 * {@code last_login} field in the file.
 */
public final class PublicKeyAuthenticator implements PublickeyAuthenticator {

    private static final String ED25519_KEY_TYPE = "ssh-ed25519";

    private final SshKeysManager keysManager;

    /**
     * @param keysManager the manager used to look up authorised key entries
     */
    public PublicKeyAuthenticator(SshKeysManager keysManager) {
        this.keysManager = keysManager;
    }

    /**
     * Authenticates the client. Rejects immediately if the key type is not
     * {@code ssh-ed25519}; otherwise compares against the stored entry for
     * {@code username} and records the login timestamp on success.
     *
     * @param username the username presented by the client
     * @param key      the public key offered for authentication
     * @param session  the active server session
     * @return {@code true} if the key matches a stored entry for the user
     */
    @Override
    public boolean authenticate(String username, PublicKey key, ServerSession session) {
        if (!ED25519_KEY_TYPE.equals(KeyUtils.getKeyType(key))) {
            SSHLogger.get().warn(
                "Rejected non-Ed25519 key from '" + username + "' ("
                + KeyUtils.getKeyType(key) + "). Only ssh-ed25519 keys are accepted."
            );
            return false;
        }

        return keysManager.findByUsername(username)
            .map(entry -> {
                try {
                    AuthorizedKeyEntry authorizedEntry = AuthorizedKeyEntry.parseAuthorizedKeyEntry(entry.getPublicKey());
                    PublicKey storedKey = authorizedEntry.resolvePublicKey(
                        null, Collections.emptyMap(), PublicKeyEntryResolver.IGNORING
                    );
                    if (storedKey != null && KeyUtils.compareKeys(key, storedKey)) {
                        keysManager.recordLogin(username);
                        return true;
                    }
                    return false;
                } catch (Exception e) {
                    SSHLogger.get().warn(
                        "Failed to verify key for '" + username + "'.", e
                    );
                    return false;
                }
            })
            .orElse(false);
    }
}
