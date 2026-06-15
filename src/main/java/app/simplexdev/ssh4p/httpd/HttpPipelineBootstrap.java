package app.simplexdev.ssh4p.httpd;

import app.simplexdev.ssh4p.SSHLogger;
import app.simplexdev.ssh4p.httpd.auth.AuthRouteHandler;
import app.simplexdev.ssh4p.httpd.auth.HttpSessionStore;
import app.simplexdev.ssh4p.httpd.endpoint.FileSystemRouteHandler;
import app.simplexdev.ssh4p.httpd.routes.CommandRouteHandler;
import app.simplexdev.ssh4p.httpd.routes.StatusRouteHandler;
import app.simplexdev.ssh4p.ssh.SshKeysManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import org.bukkit.plugin.java.JavaPlugin;
import reactor.core.publisher.Mono;

/**
 * Lifecycle manager for the HTTP API server embedded in SSH4P.
 * <p>
 * Supports two operating modes determined by the {@code http.port} config value:
 * <ul>
 *   <li><b>Dedicated mode</b> ({@code port != -1}) — binds a separate
 *       {@link io.netty.channel.socket.nio.NioServerSocketChannel} on the configured
 *       address and port. Useful when the HTTP API must be reachable independently
 *       of the Minecraft port.</li>
 *   <li><b>Multiplexed mode</b> ({@code port == -1}) — HTTP traffic is detected by
 *       {@link app.simplexdev.ssh4p.multiplexer.ProtocolMultiplexerHandler} on the
 *       same port as SSH and Minecraft. In this mode no dedicated socket is opened;
 *       instead {@link #pipelineInitializer()} returns a
 *       {@link BiConsumer BiConsumer&lt;ChannelPipeline, String&gt;} that the multiplexer
 *       installs when it recognises an HTTP client.</li>
 * </ul>
 * <p>
 * Routes registered on startup:
 * <ul>
 *   <li>{@code GET /api/status} — server status (public)</li>
 *   <li>{@code POST /api/command} — dispatch a console command (authenticated)</li>
 *   <li>{@code POST /api/auth/login} — exchange an SSH public key for a bearer token</li>
 *   <li>{@code DELETE /api/auth/session} — invalidate the caller's bearer token</li>
 *   <li>{@code GET /files/**} — browse the {@code endpoints/} directory (auth depends on extension)</li>
 *   <li>{@code PUT /files/**} — overwrite a file (authenticated, private extensions only)</li>
 * </ul>
 */
public final class HttpPipelineBootstrap {

    private final JavaPlugin plugin;
    private HttpPipelineSettings settings;
    private HttpRouter router;
    private HttpSessionStore sessionStore;
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;

    /**
     * @param plugin the owning plugin; used to locate the data folder and schedule tasks
     */
    public HttpPipelineBootstrap(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reads settings from the plugin config, initialises the session store and router,
     * and starts the HTTP server. If {@code http.port} is {@code -1} the server runs
     * in multiplexed mode and no socket is opened here.
     *
     * @param keysManager the shared SSH key store used by the login route for authentication
     */
    public void start(SshKeysManager keysManager) {
        settings = HttpPipelineSettings.fromConfig(plugin.getConfig());
        sessionStore = new HttpSessionStore();
        router = buildRouter(keysManager);

        if (settings.port() != -1) {
            startDedicated();
        } else {
            SSHLogger.get().info("HTTP4P: multiplexed mode active — HTTP routes registered.");
        }
    }

    /**
     * Shuts down the dedicated Netty event loop groups if the server was started
     * in dedicated mode. No-op in multiplexed mode (the Minecraft server owns the
     * channel's event loop).
     */
    public void stop() {
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }

    /**
     * Returns a pipeline initializer for multiplexed mode, or {@code null} if the
     * server is in dedicated mode (or has not been started yet).
     * <p>
     * The returned {@link BiConsumer} accepts a {@link ChannelPipeline} and the name
     * of the handler after which the HTTP codec chain should be inserted. It adds three
     * handlers in sequence: {@code http4p-codec} ({@link io.netty.handler.codec.http.HttpServerCodec}),
     * {@code http4p-aggregator} ({@link io.netty.handler.codec.http.HttpObjectAggregator}),
     * and {@code http4p-dispatcher} ({@link HttpRequestDispatcher}).
     *
     * @return the pipeline initializer, or {@code null} in dedicated or uninitialised mode
     */
    public BiConsumer<ChannelPipeline, String> pipelineInitializer() {
        if (settings == null || settings.port() != -1) return null;
        int maxLen = settings.maxContentLength();
        return (pipeline, afterName) -> {
            pipeline.addAfter(afterName, "http4p-codec", new HttpServerCodec());
            pipeline.addAfter("http4p-codec", "http4p-aggregator", new HttpObjectAggregator(maxLen));
            pipeline.addAfter("http4p-aggregator", "http4p-dispatcher", new HttpRequestDispatcher(router));
        };
    }

    private HttpRouter buildRouter(SshKeysManager keysManager) {
        HttpRouter r = new HttpRouter();

        r.register(HttpMethod.GET, "/api/status", new StatusRouteHandler());
        r.register(HttpMethod.POST, "/api/command", new CommandRouteHandler(plugin, sessionStore));
        r.register(HttpMethod.POST, "/api/auth/login", new AuthRouteHandler(keysManager, sessionStore));
        r.register(HttpMethod.DELETE, "/api/auth/session", request -> {
            String header = request.headers().get("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                sessionStore.invalidate(header.substring(7));
            }
            return Mono.just(HttpRouter.emptyResponse(HttpResponseStatus.NO_CONTENT));
        });

        Path endpointsDir = plugin.getDataFolder().toPath().resolve("endpoints");
        try {
            Files.createDirectories(endpointsDir);
        } catch (Exception e) {
            SSHLogger.get().warn("HTTP4P: could not create endpoints directory: " + e.getMessage());
        }

        FileSystemRouteHandler fsHandler = new FileSystemRouteHandler(endpointsDir, settings, sessionStore);
        r.registerPrefix(HttpMethod.GET, "/files", fsHandler);
        r.registerPrefix(HttpMethod.PUT, "/files", fsHandler);

        return r;
    }

    private void startDedicated() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        int maxLen = settings.maxContentLength();

        new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                @Override
                protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast(new HttpServerCodec());
                    p.addLast(new HttpObjectAggregator(maxLen));
                    p.addLast(new HttpRequestDispatcher(router));
                }
            })
            .bind(settings.bindAddress(), settings.port())
            .addListener((ChannelFuture f) -> {
                if (f.isSuccess()) {
                    SSHLogger.get().info("HTTP4P: listening on "
                        + settings.bindAddress() + ":" + settings.port());
                } else {
                    SSHLogger.get().error("HTTP4P: failed to bind on "
                        + settings.bindAddress() + ":" + settings.port(), f.cause());
                }
            });
    }
}
