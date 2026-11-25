package chat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Protocol A: JSON messages over WebSocket (see docs/protocol.md).
 * Responsibilities:
 * <ul>
 *   <li>accept many phones at once and track who is online</li>
 *   <li>validate every incoming message (bad input never crashes the server)</li>
 *   <li>store chat messages, ack them, and deliver them to the recipient</li>
 *   <li>prevent duplicates (the same id is stored and forwarded only once)</li>
 *   <li>send missed messages on login/sync (offline sync with lastSeq)</li>
 *   <li>detect dead connections (heartbeat) and handle disconnects without affecting others</li>
 * </ul>
 *
 * Threads: the WebSocket library calls onMessage/onClose from several worker threads.
 * Every handler runs inside synchronized(lock), so only one message is processed at a time
 * and the online list can never be changed by two threads at once.
 */
public final class ChatServer extends WebSocketServer {

    private static final int MAX_MESSAGE_CHARS = 16 * 1024;
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,20}$");
    private static final int MIN_PASSWORD_LENGTH = 6;

    /**
     * Heartbeat: every 10 seconds the library pings each client; browsers answer with a pong
     * automatically. A client that has not answered for ~15 seconds is disconnected, which
     * triggers onClose (so the user is marked offline). This catches phones that locked their
     * screen or left Wi-Fi range without closing the connection.
     */
    private static final int HEARTBEAT_SECONDS = 10;

    private final Store store;
    private final Gson gson = new Gson();
    private final Object lock = new Object();

    /** username -> open connections. A Set, because one user may be logged in on several devices. */
    private final Map<String, Set<WebSocket>> online = new HashMap<>();

    public ChatServer(int port, Store store) {
        super(new InetSocketAddress("0.0.0.0", port));
        this.store = store;
        setReuseAddr(true);                          // allow an immediate restart on the same port
        setConnectionLostTimeout(HEARTBEAT_SECONDS); // built-in ping/pong heartbeat
    }

    // -------------------------------------------------------------------------------------------
    // Connection events (called by the library)
    // -------------------------------------------------------------------------------------------

    @Override
    public void onStart() {
        System.out.println("WebSocket server running on port " + getPort());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("Connected: " + conn.getRemoteSocketAddress()
                + " (total sockets: " + getConnections().size() + ")");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String username;
        synchronized (lock) {
            username = usernameOf(conn);
            removeSocket(conn);
        }
        System.out.println("Disconnected: " + (username != null ? username : "(not logged in)")
                + " (code " + code + ", total sockets: " + getConnections().size() + ")");
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            // Error in the server itself, e.g. the port is already in use.
            System.err.println("WebSocket server error: " + ex.getMessage());
        } else {
            // Error on one connection: logged only, so it cannot affect other clients.
            System.err.println("Socket error (" + usernameOf(conn) + "): " + ex.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer bytes) {
        sendError(conn, "INVALID_MESSAGE", "Binary frames are not supported", null);
    }

    @Override
    public void onMessage(WebSocket conn, String raw) {
        synchronized (lock) {
            try {
                handleRawMessage(conn, raw);
            } catch (Exception e) {
                // Last safety net: a bug in one handler must not take the server down.
                System.err.println("Error while handling message: " + e);
                sendError(conn, "INVALID_MESSAGE", "Server could not process this message", null);
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Incoming messages
    // -------------------------------------------------------------------------------------------

    private void handleRawMessage(WebSocket conn, String raw) {
        if (raw.length() > MAX_MESSAGE_CHARS) {
            sendError(conn, "INVALID_MESSAGE", "Message too large", null);
            return;
        }

        JsonObject msg;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                sendError(conn, "INVALID_MESSAGE", "Message must be a JSON object with a \"type\" field", null);
                return;
            }
            msg = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            sendError(conn, "INVALID_JSON", "Message is not valid JSON", null);
            return;
        }

        String type = getString(msg, "type");
        if (type == null) {
            sendError(conn, "INVALID_MESSAGE", "Message must be a JSON object with a \"type\" field", null);
            return;
        }

        // Only register/login are allowed before the user is logged in.
        if (usernameOf(conn) == null && !type.equals("register") && !type.equals("login")) {
            sendError(conn, "NOT_LOGGED_IN", "Log in first", null);
            return;
        }

        switch (type) {
            case "register" -> handleRegister(conn, msg);
            case "login" -> handleLogin(conn, msg);
            case "chat" -> handleChat(conn, msg);
            case "sync" -> sendHistory(conn, readLastSeq(msg));
            case "logout" -> handleLogout(conn);
            // Group chat is added by Student 2.
            case "group_create", "group_chat" ->
                    sendError(conn, "INVALID_MESSAGE", "Group chat is not implemented yet", null);
            default -> sendError(conn, "INVALID_MESSAGE", "Unknown type: " + type, null);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------------------------

    private void handleRegister(WebSocket conn, JsonObject msg) {
        String[] credentials = readCredentials(conn, msg);
        if (credentials == null) return;

        if (!store.createUser(credentials[0], credentials[1])) {
            sendError(conn, "USERNAME_TAKEN", "This username is already taken", null);
            return;
        }
        System.out.println("Registered: " + credentials[0]);
        logIn(conn, credentials[0], 0); // new account: no history yet
    }

    private void handleLogin(WebSocket conn, JsonObject msg) {
        String[] credentials = readCredentials(conn, msg);
        if (credentials == null) return;

        if (!store.checkPassword(credentials[0], credentials[1])) {
            send(conn, json("type", "auth_error", "reason", "Wrong username or password"));
            return;
        }
        logIn(conn, credentials[0], readLastSeq(msg));
    }

    private void handleChat(WebSocket conn, JsonObject msg) {
        // ---- validate ----
        String id = getString(msg, "id");
        if (id == null || id.isEmpty() || id.length() > 100) {
            sendError(conn, "INVALID_MESSAGE", "Missing or invalid field: id", null);
            return;
        }
        String toField = getString(msg, "to");
        if (toField == null) {
            sendError(conn, "INVALID_MESSAGE", "Missing field: to", id);
            return;
        }
        String text = getString(msg, "text");
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            sendError(conn, "INVALID_MESSAGE", "Text must be 1-" + MAX_TEXT_LENGTH + " characters", id);
            return;
        }
        String to = toField.toLowerCase();
        if (!store.userExists(to)) {
            sendError(conn, "UNKNOWN_USER", "No user named " + toField, id);
            return;
        }

        // ---- store (duplicates are detected here by id) ----
        String from = usernameOf(conn);
        Store.SaveResult result = store.saveMessage("chat", id, from, to, text, getLong(msg, "sentAt"));
        ChatMessage message = result.message();

        // ---- ack: always, even for a duplicate, so the sender stops resending ----
        send(conn, json("type", "ack", "id", message.id(), "seq", message.seq(), "serverTime", message.serverTime()));

        // ---- deliver: only the first time, so the recipient never gets a duplicate ----
        if (result.isNew()) {
            // Recipient's devices + the sender's OTHER devices (so all of them show the conversation).
            Set<WebSocket> targets = new LinkedHashSet<>(socketsOf(to));
            targets.addAll(socketsOf(from));
            targets.remove(conn);
            for (WebSocket target : targets) send(target, message);
        }
    }

    private void handleLogout(WebSocket conn) {
        removeSocket(conn);
        conn.setAttachment(null);
    }

    // -------------------------------------------------------------------------------------------
    // Login / online tracking
    // -------------------------------------------------------------------------------------------

    private void logIn(WebSocket conn, String username, long lastSeq) {
        // If this connection was logged in as someone else, detach it first.
        if (usernameOf(conn) != null) removeSocket(conn);

        boolean wasOffline = socketsOf(username).isEmpty();
        conn.setAttachment(username); // remember which user this connection belongs to
        online.computeIfAbsent(username, name -> new HashSet<>()).add(conn);
        System.out.println("Logged in: " + username + " (online users: " + online.size() + ")");

        List<Map<String, Object>> users = new ArrayList<>();
        for (String name : store.listUsernames()) {
            if (!name.equals(username)) users.add(json("username", name, "online", online.containsKey(name)));
        }

        // groups: added by Student 2
        send(conn, json("type", "auth_ok", "username", username, "users", users, "groups", List.of()));
        sendHistory(conn, lastSeq);

        if (wasOffline) broadcastPresence(username, true);
    }

    /** Removes a connection from the online list; if it was the user's last device, they go offline. */
    private void removeSocket(WebSocket conn) {
        String username = usernameOf(conn);
        if (username == null) return;

        Set<WebSocket> sockets = online.get(username);
        if (sockets == null) return;
        sockets.remove(conn);
        if (sockets.isEmpty()) {
            online.remove(username);
            broadcastPresence(username, false);
        }
    }

    private void broadcastPresence(String username, boolean isOnline) {
        Map<String, Object> presence = json("type", "presence", "username", username, "online", isOnline);
        for (Map.Entry<String, Set<WebSocket>> entry : online.entrySet()) {
            if (entry.getKey().equals(username)) continue;
            for (WebSocket conn : entry.getValue()) send(conn, presence);
        }
    }

    /** Offline sync: every message for this user with seq greater than lastSeq. */
    private void sendHistory(WebSocket conn, long lastSeq) {
        send(conn, json("type", "history", "messages", store.getMessagesFor(usernameOf(conn), lastSeq)));
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private static String usernameOf(WebSocket conn) {
        return conn.getAttachment();
    }

    private Set<WebSocket> socketsOf(String username) {
        return online.getOrDefault(username, Set.of());
    }

    /** Sends one JSON message. Skips connections that are closing/closed instead of throwing. */
    private void send(WebSocket conn, Object message) {
        if (!conn.isOpen()) return;
        try {
            conn.send(gson.toJson(message));
        } catch (WebsocketNotConnectedException e) {
            // The connection closed between the check and the send; onClose will clean it up.
        }
    }

    private void sendError(WebSocket conn, String code, String message, String refId) {
        Map<String, Object> error = json("type", "error", "code", code, "message", message);
        if (refId != null) error.put("refId", refId);
        send(conn, error);
    }

    /** Validates username/password. Sends an error and returns null if invalid. */
    private String[] readCredentials(WebSocket conn, JsonObject msg) {
        String username = getString(msg, "username");
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            sendError(conn, "INVALID_MESSAGE", "Username must be 3-20 letters, digits or _", null);
            return null;
        }
        String password = getString(msg, "password");
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            sendError(conn, "INVALID_MESSAGE", "Password must be at least " + MIN_PASSWORD_LENGTH + " characters", null);
            return null;
        }
        return new String[]{username.toLowerCase(), password};
    }

    private static long readLastSeq(JsonObject msg) {
        Long lastSeq = getLong(msg, "lastSeq");
        return (lastSeq != null && lastSeq >= 0) ? lastSeq : 0;
    }

    /** The field's value if it is a JSON string, otherwise null. */
    private static String getString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    /** The field's value if it is a JSON number, otherwise null. */
    private static Long getLong(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isNumber() ? primitive.getAsLong() : null;
    }

    /** Builds a JSON object from key/value pairs, keeping their order: json("type", "ack", "seq", 5). */
    private static Map<String, Object> json(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
