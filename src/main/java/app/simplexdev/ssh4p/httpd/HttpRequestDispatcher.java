package app.simplexdev.ssh4p.httpd;

import app.simplexdev.ssh4p.SSHLogger;
import app.simplexdev.ssh4p.security.IpRateLimiter;
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
 * <b>Rate limiting:</b> Each request is checked against a per-IP
 * {@link IpRateLimiter} before routing. Connections that exceed the limit
 * receive {@code 429 Too Many Requests} and are closed immediately.
 * <p>
 * <b>CORS preflight:</b> {@code OPTIONS} requests are answered immediately with
 * an empty {@code 200 OK} carrying CORS headers, without touching the router.
 * <p>
 * <b>Request lifecycle:</b> {@link SimpleChannelInboundHandler} releases the
 * request buffer after {@code channelRead0} returns. Because routing is
 * asynchronous, the request is retained before the Reactor subscription begins
 * and released in a {@code doFinally} operator.
 */
public final class HttpRequestDispatcher extends SimpleChannelInboundHandler<FullHttpRequest> {

    /**
     * @param router      the router that resolves each request to a handler
     * @param rateLimiter per-IP request limiter; shared across all HTTP connections
     */
    public HttpRequestDispatcher(HttpRouter router, IpRateLimiter rateLimiter) {
        this.router = router;
        this.rateLimiter = rateLimiter;
    }

    private final HttpRouter router;
    private final IpRateLimiter rateLimiter;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String ip = IpRateLimiter.extractIp(ctx.channel().remoteAddress().toString());
        if (!rateLimiter.tryAcquire(ip)) {
            ctx.writeAndFlush(HttpRouter.plainTextResponse(
                HttpResponseStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Try again later."
            )).addListener(ChannelFutureListener.CLOSE);
            return;
        }

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
