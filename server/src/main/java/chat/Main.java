package chat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * Entry point. Starts two servers on the laptop:
 * <ul>
 *   <li>HTTP on port 3000: sends the client web page (files in /client) to the phones.</li>
 *   <li>WebSocket on port 3001: the chat connections (Protocol A).</li>
 * </ul>
 * Both listen on 0.0.0.0, meaning they accept connections from any network interface
 * (so phones on the hotspot can connect), not just from this laptop ("localhost").
 *
 * Run from the project root (the folder containing pom.xml and client/).
 */
public class Main {

    public static final int HTTP_PORT = 3000;
    public static final int WS_PORT = 3001;

    public static void main(String[] args) throws Exception {
        Path clientDir = Path.of("client");
        if (!Files.isDirectory(clientDir)) {
            System.err.println("Cannot find the client/ folder. Run the server from the project root.");
            System.exit(1);
        }

        // Temporary in-memory storage. Student 3 replaces this line with the SQLite store.
        Store store = new MemoryStore();

        StaticFileServer httpServer = new StaticFileServer(HTTP_PORT, clientDir);
        httpServer.start();

        ChatServer chatServer = new ChatServer(WS_PORT, store);
        chatServer.start(); // runs on its own thread

        System.out.println("HTTP server running on port " + HTTP_PORT);
        printAddresses();

        // On Ctrl+C: close all connections cleanly before the program exits.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                chatServer.stop(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            httpServer.stop();
            System.out.println("Server stopped.");
        }));
    }

    /** Prints the laptop's IPv4 addresses, so we know what to open on the phones. */
    private static void printAddresses() throws SocketException {
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address) {
                    System.out.println("Open on phones: http://" + address.getHostAddress() + ":" + HTTP_PORT
                            + "   (" + network.getDisplayName() + ")");
                }
            }
        }
    }
}
