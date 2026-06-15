package app.simplexdev.ssh4p.httpd.auth;

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
import java.util.Optional;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Handles {@code POST /api/auth/login}.
 * <p>
 * Expects a JSON body with a single {@code publicKey} field containing the
 * client's SSH public key in OpenSSH authorized-keys format
 * (e.g. {@code "ssh-ed25519 AAAA... optional-comment"}). The key type and
 * base64 material are matched against every entry in {@link SshKeysManager};
 * the trailing comment field is ignored so keys generated with different
 * comments on different machines still authenticate successfully.
 * <p>
 * On a successful match a {@link HttpSession} is issued and the response body
 * carries the bearer token, username, and expiry time as JSON. On failure the
 * response is {@code 401 Unauthorized}.
 * <p>
 * This validates key <em>membership</em>, not cryptographic possession — the
 * client proves they know a key that is in the authorised set, not that they
 * hold the corresponding private key. This is intentional for an admin-facing
 * dashboard where the key list itself is the access-control boundary.
 */
public final class AuthRouteHandler implements HttpRouteHandler {

    /**
     * @param keysManager source of authorised key entries read from {@code ssh_keys.json}
     * @param sessionStore where the new session is stored on a successful login
     */
    public AuthRouteHandler(SshKeysManager keysManager, HttpSessionStore sessionStore) {
        this.keysManager = keysManager;
        this.sessionStore = sessionStore;
    }

    private final SshKeysManager keysManager;
    private final HttpSessionStore sessionStore;

    /**
     * {@inheritDoc}
     * <p>
     * Reads the body synchronously (already buffered by {@code HttpObjectAggregator})
     * then offloads key lookup to {@code Schedulers.boundedElastic()} to avoid
     * blocking the Netty event loop.
     */
    @Override
    public Mono<FullHttpResponse> handle(FullHttpRequest request) {
        String body = request.content().toString(StandardCharsets.UTF_8).strip();
        return Mono.fromCallable(() -> authenticate(body))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private FullHttpResponse authenticate(String body) {
        String submittedKey;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            submittedKey = json.get("publicKey").getAsString().strip();
        } catch (Exception e) {
            return HttpRouter.plainTextResponse(HttpResponseStatus.BAD_REQUEST,
                "Body must be JSON with a 'publicKey' field.");
        }

        String normalized = normalizeKey(submittedKey);
        Optional<SshKeyEntry> match = keysManager.getAllEntries().stream()
            .filter(e -> normalizeKey(e.getPublicKey()).equals(normalized))
            .findFirst();

        if (match.isEmpty()) {
            return HttpRouter.plainTextResponse(HttpResponseStatus.UNAUTHORIZED, "Key not recognized.");
        }

        HttpSession session = sessionStore.issue(match.get().getUsername());
        String json = "{"
            + "\"token\":\"" + session.token() + "\","
            + "\"username\":\"" + esc(session.username()) + "\","
            + "\"expiresAt\":\"" + session.expiresAt() + "\""
            + "}";
        return HttpRouter.jsonResponse(HttpResponseStatus.OK, json);
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
