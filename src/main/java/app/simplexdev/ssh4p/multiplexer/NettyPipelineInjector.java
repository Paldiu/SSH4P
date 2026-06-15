package app.simplexdev.ssh4p.multiplexer;

import app.simplexdev.ssh4p.SSHLogger;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;

/**
 * Injects a Netty channel handler into Paper's server accept pipeline via NMS
 * reflection, so every new TCP connection receives a {@link ProtocolMultiplexerHandler}
 * at the front of its pipeline before Paper's own decoders are added.
 *
 * This approach navigates: {@code CraftServer.getServer()} →
 * {@code MinecraftServer.connection} (ServerConnectionListener) →
 * {@code channels} (List<ChannelFuture>) to reach the bound server sockets.
 * A permanent {@code ChannelInboundHandlerAdapter} on each server socket channel
 * intercepts every accepted client channel and prepends the multiplexer.
 */
public final class NettyPipelineInjector {

    private static final String ACCEPTOR_HANDLER = "ssh4p-acceptor";

    private NettyPipelineInjector() {}

    /**
     * Performs the injection.
     *
     * @param loopbackPort the port SSHD is listening on (loopback only)
     * @return the server {@link Channel}s that were modified, for cleanup via {@link #eject}
     * @throws Exception if reflection fails or the server structure is unrecognised
     */
    public static List<Channel> inject(int loopbackPort, BiConsumer<ChannelPipeline, String> httpInitializer) throws Exception {
        Object craftServer = Bukkit.getServer();

        Method getServerMethod = craftServer.getClass().getDeclaredMethod("getServer");
        getServerMethod.setAccessible(true);
        Object minecraftServer = getServerMethod.invoke(craftServer);

        List<ChannelFuture> futures = findServerChannelFutures(minecraftServer);
        List<Channel> injected = new ArrayList<>(futures.size());

        for (ChannelFuture future : futures) {
            Channel serverChannel = future.channel();
            if (serverChannel.pipeline().get(ACCEPTOR_HANDLER) != null) continue;

            serverChannel.pipeline().addFirst(ACCEPTOR_HANDLER, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof Channel clientChannel) {
                        try {
                            clientChannel.pipeline().addFirst("ssh4p-mux",
                                new ProtocolMultiplexerHandler(loopbackPort, httpInitializer));
                        } catch (Exception ex) {
                            SSHLogger.get().warn(
                                "SSH multiplexer: could not inject into client channel: " + ex.getMessage());
                        }
                    }
                    ctx.fireChannelRead(msg);
                }
            });
            injected.add(serverChannel);
        }

        return injected;
    }

    /**
     * Removes the injected acceptor handler from every server channel returned by {@link #inject}.
     */
    public static void eject(List<Channel> serverChannels) {
        for (Channel ch : serverChannels) {
            try {
                if (ch.pipeline().get(ACCEPTOR_HANDLER) != null) {
                    ch.pipeline().remove(ACCEPTOR_HANDLER);
                }
            } catch (Exception ignored) {}
        }
    }


    private static List<ChannelFuture> findServerChannelFutures(Object minecraftServer) throws Exception {
        Object serverConnection = findServerConnection(minecraftServer);
        return findChannelFutureList(serverConnection);
    }

    private static Object findServerConnection(Object minecraftServer) throws Exception {
        for (String name : List.of("connection", "serverConnection", "networkSystem")) {
            try {
                Field f = fieldInHierarchy(minecraftServer.getClass(), name);
                f.setAccessible(true);
                Object val = f.get(minecraftServer);
                if (val != null && looksLikeServerConnection(val.getClass())) {
                    SSHLogger.get().info("SSH4P: resolved ServerConnection via named field '" + name + "'");
                    return val;
                }
            } catch (NoSuchFieldException ignored) {}
        }

        for (Field f : allFields(minecraftServer.getClass())) {
            if (f.getType().isPrimitive() || f.getType().isArray()) continue;
            if (!looksLikeServerConnection(f.getType())) continue;
            f.setAccessible(true);
            Object val = f.get(minecraftServer);
            if (val != null) {
                SSHLogger.get().info("SSH4P: resolved ServerConnection via structural scan — field '"
                    + f.getName() + "' on " + f.getDeclaringClass().getSimpleName());
                return val;
            }
        }

        throw new IllegalStateException(
            "Could not locate ServerConnectionListener in "
            + minecraftServer.getClass().getName()
            + " — server fork may use different mappings.");
    }

    @SuppressWarnings("unchecked")
    private static List<ChannelFuture> findChannelFutureList(Object serverConnection) throws Exception {
        for (String name : List.of("channels", "listeningChannels")) {
            try {
                Field f = fieldInHierarchy(serverConnection.getClass(), name);
                f.setAccessible(true);
                List<?> list = (List<?>) f.get(serverConnection);
                if (isChannelFutureList(list)) {
                    SSHLogger.get().info("SSH4P: resolved ChannelFuture list via named field '" + name + "'");
                    return (List<ChannelFuture>) list;
                }
            } catch (NoSuchFieldException | ClassCastException ignored) {}
        }

        for (Field f : allFields(serverConnection.getClass())) {
            if (!List.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            Object val = f.get(serverConnection);
            if (val instanceof List<?> list && isChannelFutureList(list)) {
                SSHLogger.get().info("SSH4P: resolved ChannelFuture list via structural scan — field '"
                    + f.getName() + "' on " + f.getDeclaringClass().getSimpleName());
                return (List<ChannelFuture>) list;
            }
        }

        throw new IllegalStateException(
            "Could not locate List<ChannelFuture> in "
            + serverConnection.getClass().getName()
            + " — server fork may use different mappings.");
    }

    private static boolean looksLikeServerConnection(Class<?> clazz) {
        String name = clazz.getSimpleName();
        return name.contains("ServerConnection") || name.equals("NetworkSystem")
            || name.equals("ServerConnectionListener");
    }

    private static boolean isChannelFutureList(List<?> list) {
        if (list == null) return false;
        synchronized (list) {
            return !list.isEmpty() && list.get(0) instanceof ChannelFuture;
        }
    }

    private static Field fieldInHierarchy(Class<?> clazz, String name) throws NoSuchFieldException {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static List<Field> allFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }
}
