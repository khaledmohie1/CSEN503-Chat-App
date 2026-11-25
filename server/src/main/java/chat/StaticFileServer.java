package chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Serves the files in the client/ folder (HTML, CSS, JS) to the phones over HTTP.
 * Uses the HTTP server built into Java (com.sun.net.httpserver), so no extra library is needed.
 *
 * Student 4 adds the Protocol B routes (/api/send, /api/events) to this same HTTP server
 * using {@link #getHttpServer()}.
 */
public class StaticFileServer {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "png", "image/png",
            "svg", "image/svg+xml",
            "ico", "image/x-icon");

    private final HttpServer server;
    private final Path root;

    public StaticFileServer(int port, Path clientDir) throws IOException {
        this.root = clientDir.toAbsolutePath().normalize();
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        this.server.createContext("/", this::handle);
        // A thread pool, so one slow request (or a long-lived SSE stream later) doesn't block the others.
        this.server.setExecutor(Executors.newCachedThreadPool());
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    /** For Student 4: add more routes with getHttpServer().createContext("/api/...", handler). */
    public HttpServer getHttpServer() {
        return server;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                sendText(exchange, 405, "Method not allowed");
                return;
            }

            String requested = exchange.getRequestURI().getPath();
            if (requested.equals("/")) requested = "/index.html";

            // Resolve the path inside client/ and refuse anything that escapes it (e.g. "/../pom.xml").
            Path file = root.resolve(requested.substring(1)).normalize();
            if (!file.startsWith(root)) {
                sendText(exchange, 403, "Forbidden");
                return;
            }

            if (!Files.isRegularFile(file)) {
                String hint = requested.equals("/index.html")
                        ? "No index.html yet. Open /dev-console.html to test the server."
                        : "Not found";
                sendText(exchange, 404, hint);
                return;
            }

            byte[] body = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", contentTypeOf(file));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static String contentTypeOf(Path file) {
        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
