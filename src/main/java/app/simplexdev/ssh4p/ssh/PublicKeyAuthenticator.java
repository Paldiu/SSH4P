package app.simplexdev.ssh4p.ssh;

import app.simplexdev.ssh4p.SSHLogger;
import app.simplexdev.ssh4p.security.IpRateLimiter;
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
 * Auth attempts are counted per source IP via {@link IpRateLimiter}. Addresses
 * that exceed the limit are rejected immediately; this is logged as a warning.
 * <p>
 * On success, {@link SshKeysManager#recordLogin(String)} stamps the
 * {@code last_login} field in the file.
 */
public final class PublicKeyAuthenticator implements PublickeyAuthenticator {

    private static final String ED25519_KEY_TYPE = "ssh-ed25519";

    private final SshKeysManager keysManager;
    private final IpRateLimiter rateLimiter;

    /**
     * @param keysManager the manager used to look up authorised key entries
     * @param rateLimiter per-IP limiter; shared with nothing else so its window
     *                    covers only SSH auth attempts
     */
    public PublicKeyAuthenticator(SshKeysManager keysManager, IpRateLimiter rateLimiter) {
        this.keysManager = keysManager;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean authenticate(String username, PublicKey key, ServerSession session) {
        String ip = IpRateLimiter.extractIp(session.getIoSession().getRemoteAddress().toString());

        if (!rateLimiter.tryAcquire(ip)) {
            SSHLogger.get().warn(
                "SSH auth rate limit exceeded for " + ip + " (user '" + username + "'). Rejecting."
            );
            return false;
        }

        if (!ED25519_KEY_TYPE.equals(KeyUtils.getKeyType(key))) {
            SSHLogger.get().warn(
                "Rejected non-Ed25519 key from '" + username + "' at " + ip
                + " (" + KeyUtils.getKeyType(key) + "). Only ssh-ed25519 keys are accepted."
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
                        "Failed to verify key for '" + username + "' at " + ip + ".", e
                    );
                    return false;
                }
            })
            .orElse(false);
    }
}
