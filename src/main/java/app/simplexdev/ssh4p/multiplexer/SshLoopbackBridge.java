package app.simplexdev.ssh4p.multiplexer;

import app.simplexdev.ssh4p.SSHLogger;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Bidirectional TCP proxy that bridges an SSH client connection (arriving on the
 * Minecraft port) to the SSHD instance listening on loopback.
 *
 * Lifecycle:
 * <ol>
 *   <li>{@link #handlerAdded} — added to the pipeline after SSH is detected;
 *       immediately opens a loopback connection to SSHD.</li>
 *   <li>While connecting — any bytes arriving from the client are queued.</li>
 *   <li>Once connected — queued bytes are flushed to SSHD; subsequent reads
 *       are forwarded directly.</li>
 *   <li>Responses from SSHD — written back to the MC-port client channel.</li>
 *   <li>Either side closes — the other is closed too.</li>
 * </ol>
 */
public final class SshLoopbackBridge extends ChannelInboundHandlerAdapter {

    private final int sshPort;
    private Channel sshChannel;
    private final Queue<Object> pendingWrites = new ArrayDeque<>();
    private boolean sshReady = false;

    /**
     * @param sshPort the loopback port SSHD is listening on
     */
    public SshLoopbackBridge(int sshPort) {
        this.sshPort = sshPort;
    }

    /**
     * Called by Netty when this handler is added to the pipeline. Immediately opens
     * a TCP connection to SSHD on loopback. Any bytes that arrive from the client
     * before the connection completes are queued and flushed once the connection
     * succeeds. If the connection fails the client channel is closed.
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        Channel clientChannel = ctx.channel();

        new Bootstrap()
            .group(clientChannel.eventLoop())
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext sshCtx, Object msg) {
                    clientChannel.writeAndFlush(msg);
                }

                @Override
                public void channelInactive(ChannelHandlerContext sshCtx) {
                    clientChannel.close();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext sshCtx, Throwable cause) {
                    SSHLogger.get().warn("SSH loopback connection error", cause);
                    sshCtx.close();
                }
            })
            .connect("127.0.0.1", sshPort)
            .addListener((ChannelFuture future) -> {
                if (future.isSuccess()) {
                    sshChannel = future.channel();
                    sshReady = true;
                    for (Object msg : pendingWrites) {
                        sshChannel.writeAndFlush(msg);
                    }
                    pendingWrites.clear();
                } else {
                    SSHLogger.get().warn("Could not connect to loopback SSHD on port " + sshPort
                        + ": " + future.cause().getMessage());
                    drainPending();
                    clientChannel.close();
                }
            });
    }

    /**
     * Forwards bytes from the SSH client to SSHD. If the loopback connection is
     * not yet established, the message is queued until it is.
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (sshReady) {
            sshChannel.writeAndFlush(msg);
        } else {
            pendingWrites.add(msg);
        }
    }

    /**
     * Called when the SSH client disconnects. Closes the loopback SSHD connection
     * and releases any queued write buffers.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (sshChannel != null) sshChannel.close();
        drainPending();
    }

    /** Closes the client channel on any unhandled exception. */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        SSHLogger.get().warn("SSH bridge client-side error", cause);
        ctx.close();
    }

    private void drainPending() {
        for (Object msg : pendingWrites) {
            if (msg instanceof ByteBuf buf && buf.refCnt() > 0) buf.release();
        }
        pendingWrites.clear();
    }
}
