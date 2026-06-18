package app.simplexdev.ssh4p.httpd.routes;

import app.simplexdev.ssh4p.httpd.HttpRouteHandler;
import app.simplexdev.ssh4p.httpd.HttpRouter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Server;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Handles {@code GET /api/status}. No authentication required.
 * <p>
 * Returns a JSON object with the following fields:
 * <pre>
 * {
 *   "online":  &lt;current player count&gt;,
 *   "max":     &lt;configured max players&gt;,
 *   "version": &lt;Paper version string&gt;,
 *   "motd":    &lt;plain-text MOTD&gt;,
 *   "tps":     [1m, 5m, 15m]
 * }
 * </pre>
 * <strong>Thread-safety note:</strong> {@code Server.getTPS()} is documented as thread-safe
 * in Paper. {@code Server.getOnlinePlayers()} returns an unmodifiable view backed by a
 * concurrent collection, so reading {@code .size()} off the main thread is safe in practice,
 * though the count may lag by one tick under heavy churn. If exact consistency with the
 * main-thread state is required, this handler should schedule a main-thread task instead
 * of using {@code Schedulers.boundedElastic()}.
 */
public final class StatusRouteHandler implements HttpRouteHandler {

    /**
     * {@inheritDoc}
     * <p>
     * Offloads the entire response-building process to {@code Schedulers.boundedElastic()}
     * since it involves multiple Bukkit API calls and string processing.
     * The Netty event loop thread is not blocked at all.
     * See the class-level Javadoc for thread-safety considerations.
     */
    @Override
    public Mono<FullHttpResponse> handle(FullHttpRequest request) {
        return Mono.fromCallable(this::buildJson)
            .subscribeOn(Schedulers.boundedElastic())
            .map(json -> HttpRouter.jsonResponse(HttpResponseStatus.OK, json));
    }

    private String buildJson() {
        Server server = Bukkit.getServer();
        double[] tps = server.getTPS();
        String tpsArray = Arrays.stream(tps)
            .mapToObj(d -> String.format("%.2f", d))
            .collect(Collectors.joining(",", "[", "]"));
        return "{"
            + "\"online\":" + server.getOnlinePlayers().size() + ","
            + "\"max\":" + server.getMaxPlayers() + ","
            + "\"version\":\"" + esc(server.getVersion()) + "\","
            + "\"motd\":\"" + esc(PlainTextComponentSerializer.plainText().serialize(server.motd())) + "\","
            + "\"tps\":" + tpsArray
            + "}";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
