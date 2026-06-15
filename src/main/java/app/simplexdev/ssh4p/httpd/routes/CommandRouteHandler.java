package app.simplexdev.ssh4p.httpd.routes;

import app.simplexdev.ssh4p.httpd.HttpRouteHandler;
import app.simplexdev.ssh4p.httpd.HttpRouter;
import app.simplexdev.ssh4p.httpd.auth.HttpSessionStore;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import reactor.core.publisher.Mono;

/**
 * Handles {@code POST /api/command}. Requires a valid bearer session.
 * <p>
 * The request body is the raw command string (not JSON). The command is
 * dispatched to the Bukkit main thread via the plugin scheduler, which is
 * required because {@link Bukkit#dispatchCommand} must run on the main thread.
 * The Reactor {@code Mono} completes once the main thread has dispatched the
 * command, returning {@code 202 Accepted}. Output produced by the command is
 * not captured — use the SSH console if you need interactive output.
 */
public final class CommandRouteHandler implements HttpRouteHandler {

    /**
     * @param plugin       used to schedule the dispatch task on the Bukkit main thread
     * @param sessionStore validates the {@code Authorization: Bearer} token on each request
     */
    public CommandRouteHandler(JavaPlugin plugin, HttpSessionStore sessionStore) {
        this.plugin = plugin;
        this.sessionStore = sessionStore;
    }

    private final JavaPlugin plugin;
    private final HttpSessionStore sessionStore;

    @Override
    public Mono<FullHttpResponse> handle(FullHttpRequest request) {
        if (sessionStore.fromRequest(request).isEmpty()) {
            return Mono.just(HttpRouter.plainTextResponse(
                HttpResponseStatus.UNAUTHORIZED, "Authentication required."));
        }

        String command = request.content().toString(StandardCharsets.UTF_8).strip();
        if (command.isBlank()) {
            return Mono.just(HttpRouter.plainTextResponse(
                HttpResponseStatus.BAD_REQUEST, "Request body must contain the command to dispatch."));
        }

        return Mono.create(sink ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    sink.success(HttpRouter.plainTextResponse(HttpResponseStatus.ACCEPTED, "Dispatched."));
                } catch (Exception e) {
                    sink.error(e);
                }
            })
        );
    }
}
