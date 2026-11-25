package chat;

/**
 * One stored chat message, exactly as it is sent to clients (see docs/protocol.md).
 * Gson turns this record into JSON using these field names.
 *
 * @param type       "chat" (Student 2 adds "group_chat")
 * @param id         unique id created by the sender's client; used to detect duplicates
 * @param from       sender's username
 * @param to         recipient's username
 * @param text       message text
 * @param sentAt     sender's clock when sent (ms), may be null
 * @param seq        number given by the server when stored; used for ordering and offline sync
 * @param serverTime server's clock when stored (ms)
 */
public record ChatMessage(
        String type,
        String id,
        String from,
        String to,
        String text,
        Long sentAt,
        long seq,
        long serverTime) {
}
