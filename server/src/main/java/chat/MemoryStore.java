package chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TEMPORARY storage kept in memory, so the server works before the database exists.
 * Everything is lost when the server restarts, and passwords are NOT hashed here.
 * Student 3 replaces it with an SQLite implementation of {@link Store}.
 *
 * All methods are synchronized, so several threads can use the store safely at the same time.
 */
public class MemoryStore implements Store {

    private final Map<String, String> passwords = new HashMap<>();      // username -> password
    private final List<ChatMessage> messages = new ArrayList<>();       // all messages, in seq order
    private final Map<String, ChatMessage> messagesById = new HashMap<>(); // id -> message (duplicate check)
    private long nextSeq = 1;

    @Override
    public synchronized boolean createUser(String username, String password) {
        if (passwords.containsKey(username)) return false;
        passwords.put(username, password);
        return true;
    }

    @Override
    public synchronized boolean checkPassword(String username, String password) {
        String stored = passwords.get(username);
        return stored != null && stored.equals(password);
    }

    @Override
    public synchronized boolean userExists(String username) {
        return passwords.containsKey(username);
    }

    @Override
    public synchronized List<String> listUsernames() {
        return new ArrayList<>(passwords.keySet());
    }

    @Override
    public synchronized SaveResult saveMessage(String type, String id, String from, String to,
                                               String text, Long sentAt) {
        ChatMessage existing = messagesById.get(id);
        if (existing != null) return new SaveResult(false, existing);

        ChatMessage stored = new ChatMessage(type, id, from, to, text, sentAt, nextSeq++, System.currentTimeMillis());
        messages.add(stored);
        messagesById.put(id, stored);
        return new SaveResult(true, stored);
    }

    @Override
    public synchronized List<ChatMessage> getMessagesFor(String username, long afterSeq) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m.seq() > afterSeq && (m.from().equals(username) || m.to().equals(username))) {
                result.add(m);
            }
        }
        return result;
    }
}
