package app.simplexdev.ssh4p.httpd.auth;

import app.simplexdev.ssh4p.SSHLogger;
import app.simplexdev.ssh4p.httpd.HttpRouteHandler;
import app.simplexdev.ssh4p.httpd.HttpRouter;
import app.simplexdev.ssh4p.ssh.SshKeysManager;
import app.simplexdev.ssh4p.ssh.SshKeysManager.SshKeyEntry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Handles {@code POST /api/auth/login}.
 * <p>
 * Expects a JSON body:
 * <pre>
 * {
 *   "publicKey":  "ssh-ed25519 AAAA...",
 *   "timestamp":  "2026-06-15T16:00:00Z",
 *   "signature":  "&lt;base64(sign("ssh4p-auth:" + timestamp, privateKey))&gt;"
 * }
 * </pre>
 * The server:
 * <ol>
 *   <li>Looks up the submitted public key in {@link SshKeysManager}.</li>
 *   <li>Rejects timestamps older than five minutes or more than 30 seconds in the future.</li>
 *   <li>Checks that the signature has not been seen before (replay protection).</li>
 *   <li>Verifies the Ed25519 signature over {@code "ssh4p-auth:" + timestamp} using
 *       the stored public key — proving the client holds the corresponding private key.</li>
 *   <li>On success, records the signature as used and issues a 24-hour bearer token via
 *       {@link HttpSessionStore}.</li>
 * </ol>
 * <p>
 * <strong>Replay protection:</strong> each signature is accepted at most once within the
 * five-minute skew window. The seen-signature cache is self-expiring — entries older than
 * {@code MAX_SKEW_BEHIND_SECONDS} are swept on every successful login attempt.
 */
public final class AuthRouteHandler implements HttpRouteHandler {

    private static final long MAX_SKEW_BEHIND_SECONDS = 300;
    private static final long MAX_SKEW_AHEAD_SECONDS  = 30;

    private static final class ParsedBody {
        final String submittedKey;
        final String timestamp;
        final String signatureB64;
        ParsedBody(String k, String t, String s) { submittedKey = k; timestamp = t; signatureB64 = s; }
    }

    private final SshKeysManager keysManager;
    private final HttpSessionStore sessionStore;
    private final ConcurrentHashMap<String, Instant> seenSignatures = new ConcurrentHashMap<>();

    /**
     * @param keysManager source of authorised key entries read from {@code ssh_keys.json}
     * @param sessionStore where the new session is stored on a successful login
     */
    public AuthRouteHandler(SshKeysManager keysManager, HttpSessionStore sessionStore) {
        this.keysManager = keysManager;
        this.sessionStore = sessionStore;
    }

    @Override
    public Mono<FullHttpResponse> handle(FullHttpRequest request) {
        String body = request.content().toString(StandardCharsets.UTF_8).strip();
        return Mono.fromCallable(() -> authenticate(body))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private FullHttpResponse authenticate(String body) {
        ParsedBody parsed;
        try {
            parsed = parseBody(body);
        } catch (Exception e) {
            return HttpRouter.plainTextResponse(HttpResponseStatus.BAD_REQUEST,
                "Body must be JSON with 'publicKey', 'timestamp', and 'signature' fields.");
        }

        Instant ts;
        try {
            ts = Instant.parse(parsed.timestamp);
        } catch (Exception e) {
            return HttpRouter.plainTextResponse(HttpResponseStatus.BAD_REQUEST,
                "Invalid timestamp. Use ISO-8601 UTC, e.g. 2026-06-15T16:00:00Z.");
        }
        if (!isTimestampValid(ts, Instant.now())) {
            return HttpRouter.plainTextResponse(HttpResponseStatus.UNAUTHORIZED,
                "Timestamp out of acceptable range. Ensure your clock is synchronised.");
        }

        Instant now = Instant.now();
        sweepSeenSignatures(now);
        if (seenSignatures.containsKey(parsed.signatureB64)) {
            SSHLogger.get().warn("HTTP auth: replayed signature rejected.");
            return HttpRouter.plainTextResponse(HttpResponseStatus.UNAUTHORIZED,
                "Request already seen (replay rejected).");
        }

        Optional<SshKeyEntry> match = findMatchingKey(parsed.submittedKey);
        if (match.isEmpty()) {
            return HttpRouter.plainTextResponse(HttpResponseStatus.UNAUTHORIZED, "Key not recognised.");
        }

        if (!verifySignature(match.get(), parsed.timestamp, parsed.signatureB64)) {
            return HttpRouter.plainTextResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid signature.");
        }

        if (seenSignatures.putIfAbsent(parsed.signatureB64, now) != null) {
            SSHLogger.get().warn("HTTP auth: replayed signature rejected (concurrent).");
            return HttpRouter.plainTextResponse(HttpResponseStatus.UNAUTHORIZED,
                "Request already seen (replay rejected).");
        }

        HttpSession session = sessionStore.issue(match.get().getUsername());
        String json = "{"
            + "\"token\":\"" + session.token() + "\","
            + "\"username\":\"" + esc(session.username()) + "\","
            + "\"expiresAt\":\"" + session.expiresAt() + "\""
            + "}";
        return HttpRouter.jsonResponse(HttpResponseStatus.OK, json);
    }

    private static ParsedBody parseBody(String body) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        String key = json.get("publicKey").getAsString().strip();
        String ts  = json.get("timestamp").getAsString().strip();
        String sig = json.get("signature").getAsString().strip();
        return new ParsedBody(key, ts, sig);
    }

    private static boolean isTimestampValid(Instant ts, Instant now) {
        return !ts.isBefore(now.minus(MAX_SKEW_BEHIND_SECONDS, ChronoUnit.SECONDS))
            && !ts.isAfter(now.plus(MAX_SKEW_AHEAD_SECONDS, ChronoUnit.SECONDS));
    }

    private Optional<SshKeyEntry> findMatchingKey(String submittedKey) {
        String normalized = normalizeKey(submittedKey);
        return keysManager.getAllEntries().stream()
            .filter(e -> normalizeKey(e.getPublicKey()).equals(normalized))
            .findFirst();
    }

    private boolean verifySignature(SshKeyEntry entry, String timestamp, String signatureB64) {
        try {
            AuthorizedKeyEntry authorizedEntry = AuthorizedKeyEntry.parseAuthorizedKeyEntry(entry.getPublicKey());
            PublicKey storedKey = authorizedEntry.resolvePublicKey(null, Collections.emptyMap(),
                PublicKeyEntryResolver.IGNORING);
            if (storedKey == null) {
                SSHLogger.get().warn("HTTP auth: could not load stored public key for " + entry.getUsername());
                return false;
            }
            byte[] sigBytes = Base64.getDecoder().decode(signatureB64);
            byte[] message  = ("ssh4p-auth:" + timestamp).getBytes(StandardCharsets.UTF_8);
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(storedKey);
            sig.update(message);
            if (!sig.verify(sigBytes)) {
                String norm = normalizeKey(entry.getPublicKey());
                SSHLogger.get().warn("HTTP auth: signature mismatch for key "
                    + norm.substring(0, Math.min(norm.length(), 40)) + "...");
                return false;
            }
            return true;
        } catch (Exception e) {
            SSHLogger.get().warn("HTTP auth: signature verification error: " + e.getMessage());
            return false;
        }
    }

    /** Removes entries whose acceptance time is older than the allowed skew window. */
    private void sweepSeenSignatures(Instant now) {
        Instant cutoff = now.minus(MAX_SKEW_BEHIND_SECONDS, ChronoUnit.SECONDS);
        seenSignatures.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    private static String normalizeKey(String key) {
        if (key == null) return "";
        String[] parts = key.trim().split("\\s+");
        return parts.length >= 2 ? parts[0] + " " + parts[1] : key.trim();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
