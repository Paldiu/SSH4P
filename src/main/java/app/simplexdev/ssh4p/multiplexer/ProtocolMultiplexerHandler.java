package app.simplexdev.ssh4p.multiplexer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Inspects the first four bytes of every new TCP connection on the Minecraft port
 * and routes SSH or HTTP traffic to their respective handlers.
 *
 * SSH clients open with the ASCII banner {@code SSH-}. HTTP clients open with a
 * method verb ({@code GET }, {@code POST}, {@code PUT }, etc.). Everything else
 * (Minecraft protocol) is left untouched — this handler removes itself and
 * {@link ByteToMessageDecoder} replays the buffered bytes to the next handler.
 */
public final class ProtocolMultiplexerHandler extends ByteToMessageDecoder {

    private static final byte[] SSH_MAGIC = {'S', 'S', 'H', '-'};

    private static final byte[][] HTTP_METHODS = {
        {'G', 'E', 'T', ' '},
        {'P', 'O', 'S', 'T'},
        {'P', 'U', 'T', ' '},
        {'H', 'E', 'A', 'D'},
        {'D', 'E', 'L', 'E'},
        {'O', 'P', 'T', 'I'},
        {'P', 'A', 'T', 'C'},
    };

    private final int sshLoopbackPort;
    private final BiConsumer<ChannelPipeline, String> httpInitializer;

    /**
     * @param sshLoopbackPort the loopback port SSHD is listening on; SSH connections are
     *                        proxied here via {@link SshLoopbackBridge}
     * @param httpInitializer a {@link BiConsumer} that installs the HTTP codec chain into the
     *                        pipeline when an HTTP client is detected, or {@code null} to disable
     *                        HTTP multiplexing (traffic falls through to Minecraft)
     */
    public ProtocolMultiplexerHandler(int sshLoopbackPort, BiConsumer<ChannelPipeline, String> httpInitializer) {
        this.sshLoopbackPort = sshLoopbackPort;
        this.httpInitializer = httpInitializer;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) return;

        ChannelPipeline pipeline = ctx.pipeline();

        if (matchesBytes(in, SSH_MAGIC)) {
            pipeline.addAfter(ctx.name(), "ssh4p-bridge", new SshLoopbackBridge(sshLoopbackPort));
        } else if (httpInitializer != null && matchesAny(in, HTTP_METHODS)) {
            httpInitializer.accept(pipeline, ctx.name());
        }

        pipeline.remove(this);
    }

    private static boolean matchesBytes(ByteBuf buf, byte[] magic) {
        int start = buf.readerIndex();
        for (int i = 0; i < magic.length; i++) {
            if (buf.getByte(start + i) != magic[i]) return false;
        }
        return true;
    }

    private static boolean matchesAny(ByteBuf buf, byte[][] candidates) {
        for (byte[] candidate : candidates) {
            if (matchesBytes(buf, candidate)) return true;
        }
        return false;
    }
}
