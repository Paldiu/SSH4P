package app.simplexdev.ssh4p.httpd;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Maps HTTP method and path combinations to {@link HttpRouteHandler} implementations.
 * <p>
 * Two registration modes are supported:
 * <ul>
 *   <li><b>Exact routes</b> — matched when the decoded request path equals the
 *       registered path exactly (query string stripped).</li>
 *   <li><b>Prefix routes</b> — matched when the decoded request path starts with
 *       the registered prefix. Prefix routes are checked in registration order
 *       after all exact routes have been tried.</li>
 * </ul>
 * All response factory methods ({@link #plainTextResponse}, {@link #jsonResponse},
 * {@link #emptyResponse}) automatically include CORS headers and
 * {@code Connection: close}, so the front-end may call the API from any origin
 * without a proxy.
 */
public final class HttpRouter {
    private static volatile String corsOrigin = "*";

    /**
     * Sets the {@code Access-Control-Allow-Origin} value for all response factory methods.
     * Called by {@link HttpPipelineBootstrap} when (re-)building the router from config.
     *
     * @param origin the origin to allow, e.g. {@code "*"} or {@code "https://admin.example.com"}
     */
    public static void setCorsOrigin(String origin) {
        corsOrigin = (origin != null && !origin.isBlank()) ? origin : "*";
    }

    private record RouteKey(String method, String path) {}
    private record PrefixEntry(String method, String prefix, HttpRouteHandler handler) {}

    private final Map<RouteKey, HttpRouteHandler> exactRoutes = new LinkedHashMap<>();
    private final List<PrefixEntry> prefixRoutes = new ArrayList<>();

    /**
     * Registers an exact-match route. Any existing registration for the same
     * method and path is silently replaced.
     *
     * @param method  the HTTP method that triggers this handler
     * @param path    the exact decoded path (e.g. {@code "/api/status"})
     * @param handler the handler to invoke
     */
    public void register(HttpMethod method, String path, HttpRouteHandler handler) {
        exactRoutes.put(new RouteKey(method.name(), path), handler);
    }

    /**
     * Registers a prefix route. The handler is invoked for any request whose
     * decoded path starts with {@code prefix}. Registration order matters —
     * the first matching prefix wins.
     *
     * @param method  the HTTP method that triggers this handler
     * @param prefix  the path prefix (e.g. {@code "/files"})
     * @param handler the handler to invoke
     */
    public void registerPrefix(HttpMethod method, String prefix, HttpRouteHandler handler) {
        prefixRoutes.add(new PrefixEntry(method.name(), prefix, handler));
    }

    /**
     * Resolves {@code request} to a handler, invokes it, and returns the resulting
     * {@link Mono}. Exact routes are checked before prefix routes. Returns a
     * {@code 404 Not Found} response if no route matches.
     *
     * @param request the fully aggregated inbound request
     * @return a {@link Mono} that emits the response
     */
    public Mono<FullHttpResponse> route(FullHttpRequest request) {
        String path = new QueryStringDecoder(request.uri()).path();
        String method = request.method().name();

        HttpRouteHandler handler = exactRoutes.get(new RouteKey(method, path));
        if (handler != null) return handler.handle(request);

        for (PrefixEntry entry : prefixRoutes) {
            if (entry.method().equals(method)
                    && (path.equals(entry.prefix()) || path.startsWith(entry.prefix() + "/"))) {
                return entry.handler().handle(request);
            }
        }

        return Mono.just(plainTextResponse(HttpResponseStatus.NOT_FOUND, "404 Not Found"));
    }

    /**
     * Builds a {@code text/plain; charset=UTF-8} response with CORS headers and
     * {@code Connection: close}.
     *
     * @param status the HTTP status code
     * @param body   the response body text
     * @return a fully initialised {@link FullHttpResponse}
     */
    public static FullHttpResponse plainTextResponse(HttpResponseStatus status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
            Unpooled.wrappedBuffer(bytes));
        response.headers()
            .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN + "; charset=UTF-8")
            .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
            .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        applyCors(response);
        return response;
    }

    /**
     * Builds an {@code application/json; charset=UTF-8} response with CORS headers
     * and {@code Connection: close}.
     *
     * @param status the HTTP status code
     * @param json   the pre-serialised JSON string
     * @return a fully initialised {@link FullHttpResponse}
     */
    public static FullHttpResponse jsonResponse(HttpResponseStatus status, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
            Unpooled.wrappedBuffer(bytes));
        response.headers()
            .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8")
            .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
            .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        applyCors(response);
        return response;
    }

    /**
     * Builds a body-less response with CORS headers and {@code Connection: close}.
     * Suitable for {@code 204 No Content}, {@code 200 OK} OPTIONS preflight replies,
     * and similar cases where no body is required.
     *
     * @param status the HTTP status code
     * @return a fully initialised {@link FullHttpResponse} with an empty body
     */
    public static FullHttpResponse emptyResponse(HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers()
            .setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
            .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        applyCors(response);
        return response;
    }

    private static void applyCors(FullHttpResponse response) {
        response.headers()
            .set("Access-Control-Allow-Origin", corsOrigin)
            .set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
            .set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
}
