package app.simplexdev.ssh4p.multiplexer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Inspects the first bytes of every new TCP connection on the Minecraft port and
 * routes SSH traffic to the loopback SSHD bridge.
 *
 * SSH clients always open with the ASCII banner "SSH-", so we peek at the first
 * four bytes without consuming them. If they match, a {@link SshLoopbackBridge}
 * is spliced into the pipeline and this handler removes itself — at which point
 * {@link ByteToMessageDecoder} automatically replays the buffered bytes into the
 * bridge. For all other traffic (Minecraft protocol) this handler simply removes
 * itself and the bytes are replayed to the next handler in Paper's pipeline.
 */
public final class ProtocolMultiplexerHandler extends ByteToMessageDecoder {

    private static final byte[] SSH_MAGIC = {'S', 'S', 'H', '-'};

    private final int sshLoopbackPort;

    /**
     * @param sshLoopbackPort the loopback port the SSHD instance is bound to
     */
    public ProtocolMultiplexerHandler(int sshLoopbackPort) {
        this.sshLoopbackPort = sshLoopbackPort;
    }

    /**
     * Waits until at least four bytes are available, then checks for the SSH magic
     * bytes. For SSH traffic a {@link SshLoopbackBridge} is inserted after this
     * handler before it removes itself; for everything else this handler removes
     * itself immediately. In both cases {@link ByteToMessageDecoder} replays the
     * buffered bytes to the next handler automatically via {@code handlerRemoved0}.
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < SSH_MAGIC.length) return;

        ChannelPipeline pipeline = ctx.pipeline();

        if (isSSH(in)) {
            pipeline.addAfter(ctx.name(), "ssh4p-bridge", new SshLoopbackBridge(sshLoopbackPort));
        }

        pipeline.remove(this);
    }

    private static boolean isSSH(ByteBuf buf) {
        int start = buf.readerIndex();
        for (int i = 0; i < SSH_MAGIC.length; i++) {
            if (buf.getByte(start + i) != SSH_MAGIC[i]) return false;
        }
        return true;
    }
}
