package app.simplexdev.ssh4p.httpd.endpoint;

import app.simplexdev.ssh4p.httpd.HttpRouteHandler;
import app.simplexdev.ssh4p.httpd.HttpPipelineSettings;
import app.simplexdev.ssh4p.httpd.HttpRouter;
import app.simplexdev.ssh4p.httpd.auth.HttpSessionStore;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Handles {@code GET} and {@code PUT} requests under the {@code /files/} prefix,
 * mapping them to the plugin's {@code endpoints/} data directory.
 * <p>
 * <b>Symlink support:</b> Java NIO follows symlinks by default, so any symlink
 * placed inside {@code endpoints/} is resolved transparently. This lets operators
 * expose arbitrary directories (e.g. a WorldEdit schematics folder) without
 * copying files into the plugin folder.
 * <p>
 * <b>GET /files/&lt;path&gt;:</b>
 * <ul>
 *   <li>If {@code <path>} is a directory, returns a JSON directory listing with
 *       name, type, size, and last-modified timestamp for each entry.</li>
 *   <li>If {@code <path>} is a file whose extension appears in
 *       {@link HttpPipelineSettings#publicExtensions()}, the file content is
 *       returned as JSON without requiring authentication.</li>
 *   <li>If the extension appears in {@link HttpPipelineSettings#privateExtensions()},
 *       a valid bearer session is required; unauthenticated requests receive
 *       {@code 401 Unauthorized}.</li>
 * </ul>
 * <p>
 * <b>PUT /files/&lt;path&gt;:</b> Overwrites the file at {@code <path>} with the
 * raw request body. Always requires authentication and is only permitted for
 * private-extension files. The parent directory must already exist; this handler
 * does not create intermediate directories.
 * <p>
 * <b>Path traversal protection:</b> Any request path containing {@code ..} is
 * rejected with {@code 403 Forbidden} before the path is resolved on disk.
 */
public final class FileSystemRouteHandler implements HttpRouteHandler {

    private static final String ROUTE_PREFIX = "/files";

    /**
     * @param endpointsRoot the {@code endpoints/} directory inside the plugin data folder;
     *                      symlinks within it are followed
     * @param settings      provides the public/private extension lists for access control
     * @param sessionStore  validates bearer tokens on requests that require authentication
     */
    public FileSystemRouteHandler(Path endpointsRoot, HttpPipelineSettings settings, HttpSessionStore sessionStore) {
        this.endpointsRoot = endpointsRoot;
        this.settings = settings;
        this.sessionStore = sessionStore;
    }

    private final Path endpointsRoot;
    private final HttpPipelineSettings settings;
    private final HttpSessionStore sessionStore;

    @Override
    public Mono<FullHttpResponse> handle(FullHttpRequest request) {
        String uri = new QueryStringDecoder(request.uri()).path();
        String relative = uri.substring(ROUTE_PREFIX.length());
        if (relative.startsWith("/")) relative = relative.substring(1);

        if (relative.contains("..")) {
            return Mono.just(HttpRouter.plainTextResponse(HttpResponseStatus.FORBIDDEN, "403 Forbidden"));
        }

        Path target = endpointsRoot.resolve(relative);

        if (request.method() == HttpMethod.GET) {
            return handleGet(request, target);
        }
        if (request.method() == HttpMethod.PUT) {
            return handlePut(request, target);
        }
        return Mono.just(HttpRouter.plainTextResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "405 Method Not Allowed"));
    }

    private Mono<FullHttpResponse> handleGet(FullHttpRequest request, Path target) {
        return Mono.fromCallable(() -> {
            if (!Files.exists(target)) {
                return HttpRouter.plainTextResponse(HttpResponseStatus.NOT_FOUND, "404 Not Found");
            }

            if (!Files.isDirectory(target)) {
                String ext = extension(target.getFileName().toString());
                if (settings.isPrivateExtension(ext) && sessionStore.fromRequest(request).isEmpty()) {
                    return HttpRouter.plainTextResponse(HttpResponseStatus.UNAUTHORIZED, "Authentication required.");
                }
            }

            return Files.isDirectory(target) ? serveDirectory(target) : serveFile(target);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<FullHttpResponse> handlePut(FullHttpRequest request, Path target) {
        return Mono.fromCallable(() -> {
            if (sessionStore.fromRequest(request).isEmpty()) {
                return HttpRouter.plainTextResponse(HttpResponseStatus.UNAUTHORIZED, "Authentication required.");
            }

            String ext = extension(target.getFileName().toString());
            if (!settings.isPrivateExtension(ext)) {
                return HttpRouter.plainTextResponse(HttpResponseStatus.FORBIDDEN,
                    "Only private-extension files may be edited.");
            }

            if (target.getParent() != null && !Files.exists(target.getParent())) {
                return HttpRouter.plainTextResponse(HttpResponseStatus.NOT_FOUND, "Parent path not found.");
            }

            String body = request.content().toString(StandardCharsets.UTF_8);
            Files.writeString(target, body, StandardCharsets.UTF_8);
            return HttpRouter.emptyResponse(HttpResponseStatus.NO_CONTENT);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private FullHttpResponse serveDirectory(Path dir) throws IOException {
        String entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream.map(p -> {
                boolean isDir = Files.isDirectory(p);
                String name = p.getFileName().toString();
                long size = 0;
                String modified = "";
                try {
                    if (!isDir) size = Files.size(p);
                    modified = Files.getLastModifiedTime(p).toInstant().toString();
                } catch (IOException ignored) {}
                return "{\"name\":\"" + esc(name) + "\","
                    + "\"type\":\"" + (isDir ? "directory" : "file") + "\","
                    + "\"size\":" + size + ","
                    + "\"modified\":\"" + modified + "\"}";
            }).collect(Collectors.joining(","));
        }
        return HttpRouter.jsonResponse(HttpResponseStatus.OK,
            "{\"type\":\"directory\",\"entries\":[" + entries + "]}");
    }

    private FullHttpResponse serveFile(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String name = file.getFileName().toString();
        return HttpRouter.jsonResponse(HttpResponseStatus.OK,
            "{\"type\":\"file\",\"name\":\"" + esc(name) + "\",\"content\":" + jsonString(content) + "}");
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t")
               + "\"";
    }
}
