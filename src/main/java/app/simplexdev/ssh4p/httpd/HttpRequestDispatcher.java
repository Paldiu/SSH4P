package app.simplexdev.ssh4p.httpd;

import app.simplexdev.ssh4p.SSHLogger;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.scheduler.Schedulers;

/**
 * Terminal Netty channel handler that sits after {@code HttpServerCodec} and
 * {@code HttpObjectAggregator} in the pipeline. Receives fully aggregated
 * {@link FullHttpRequest} objects and dispatches them through {@link HttpRouter}.
 * <p>
 * <b>CORS preflight:</b> {@code OPTIONS} requests are answered immediately with
 * an empty {@code 200 OK} response carrying CORS headers, without touching the
 * router. This satisfies browser pre-flight checks before any other request.
 * <p>
 * <b>Request lifecycle:</b> {@link SimpleChannelInboundHandler} releases the
 * request buffer after {@code channelRead0} returns. Because routing is
 * asynchronous, the request is {@link io.netty.util.ReferenceCounted#retain() retained}
 * before the Reactor subscription begins and released in a {@code doFinally}
 * operator, ensuring the buffer remains live across the async boundary and is
 * freed exactly once regardless of whether the subscription succeeds or fails.
 */
public final class HttpRequestDispatcher extends SimpleChannelInboundHandler<FullHttpRequest> {

    /**
     * @param router the router that resolves each request to a handler
     */
    public HttpRequestDispatcher(HttpRouter router) {
        this.router = router;
    }

    private final HttpRouter router;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() == HttpMethod.OPTIONS) {
            ctx.writeAndFlush(HttpRouter.emptyResponse(HttpResponseStatus.OK))
               .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        request.retain();
        router.route(request)
            .doFinally(signal -> request.release())
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                response -> ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE),
                err -> {
                    SSHLogger.get().warn("HTTP request error: " + err.getMessage(), err);
                    ctx.writeAndFlush(HttpRouter.plainTextResponse(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR, err.getMessage()
                    )).addListener(ChannelFutureListener.CLOSE);
                }
            );
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        SSHLogger.get().warn("HTTP channel error", cause);
        ctx.close();
    }
}
