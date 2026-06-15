package app.simplexdev.ssh4p.httpd;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import reactor.core.publisher.Mono;

/**
 * Single-method contract for HTTP request handlers registered with {@link HttpRouter}.
 * <p>
 * The {@code request} passed to {@link #handle} has already been retained by
 * {@link HttpRequestDispatcher} and will be released automatically once the
 * returned {@link Mono} terminates (regardless of success or error). Implementations
 * must not retain or release the request themselves.
 * <p>
 * Blocking I/O must be offloaded to {@code Schedulers.boundedElastic()} so the
 * Netty event-loop thread is never held.
 */
@FunctionalInterface
public interface HttpRouteHandler {

    /**
     * Produces a response for the given HTTP request.
     *
     * @param request the fully aggregated inbound request; must not be retained or
     *                released by the implementation
     * @return a {@link Mono} that emits exactly one {@link FullHttpResponse} and
     *         then completes, or emits an error that the dispatcher will convert
     *         to a {@code 500 Internal Server Error}
     */
    Mono<FullHttpResponse> handle(FullHttpRequest request);
}
