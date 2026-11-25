package chat;

import java.util.List;

/**
 * Everything the chat server needs from storage.
 * MemoryStore implements it for now; Student 3 writes an SQLite version implementing the same
 * interface, and the rest of the server does not need to change.
 */
public interface Store {

    /** Result of saving a message: isNew is false when a message with the same id already existed. */
    record SaveResult(boolean isNew, ChatMessage message) {
    }

    /** @return true if created, false if the username is already taken. */
    boolean createUser(String username, String password);

    /** @return true if the user exists and the password matches. */
    boolean checkPassword(String username, String password);

    boolean userExists(String username);

    List<String> listUsernames();

    /**
     * Stores a new message and gives it the next seq number.
     * If a message with the same id was already stored, nothing is stored again and the
     * original stored message is returned with isNew = false.
     */
    SaveResult saveMessage(String type, String id, String from, String to, String text, Long sentAt);

    /** All messages sent by or to this user with seq greater than afterSeq, in seq order. */
    List<ChatMessage> getMessagesFor(String username, long afterSeq);
}
