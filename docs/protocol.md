# Message Protocol (Protocol A: JSON over WebSocket)

This document is the contract between the server and the clients.
Every member builds on it. **Changes go through Student 1** and must be updated here.

---

## 1. Basics

- **Server:** written in Java. The web page is served over HTTP on port **3000**
  (`http://<laptop-ip>:3000`), and the chat WebSocket runs on port **3001** (`ws://<laptop-ip>:3001`).
  Clients build the WebSocket address from the page's own address: `ws://${location.hostname}:3001`.
- **Encoding:** every message is one JSON object, sent as one WebSocket text frame.
- **Framing:** handled by WebSocket. One frame = one message, so no extra delimiters are needed.
- **Every message has a `type` field.** The other fields depend on the type.
- **Connection health:** the server sends WebSocket-level ping frames every 10 seconds.
  Browsers answer automatically. A client that has not answered for about 15 seconds is disconnected.
  These pings are not JSON messages and need no client code.

---

## 2. IDs: how we prevent loss and duplication

Each chat message has two identifiers:

| Field | Created by | Purpose |
|---|---|---|
| `id` | Sender's client | Unique message id. Used to detect duplicates and match acks. |
| `seq` | Server | Increasing number given when the server stores the message. Used for ordering and offline sync. |

**`id` format:** `<username>-<timestamp ms>-<random 6 chars>`, e.g. `khaled-1732370000000-a8f3k2`.

> Do not use `crypto.randomUUID()` in the client: browsers only allow it on HTTPS,
> and our phones load the page over plain HTTP.

### Rules
1. **Sender** keeps every sent chat message in a "pending" list until the server sends an `ack` for its `id`.
2. **No ack within 3 seconds:** the sender resends the same message (same `id`). Up to 5 tries, then it is marked "failed" in the UI.
3. **On reconnect:** the sender resends everything still pending.
4. **Server** checks the `id`. If it has already stored it, it does **not** store or forward it again; it only re-sends the `ack` with the original `seq`.
5. **Receiver** ignores any message whose `id` it has already shown.
6. **Offline sync:** each client remembers the highest `seq` it has received (`lastSeq`) and sends it on login or `sync`. The server replies with every message for that user with a higher `seq`.

---

## 3. Client → Server messages

### `register`
Create a new account. On success the user is also logged in.
```json
{ "type": "register", "username": "khaled", "password": "secret123" }
```

### `login`
```json
{ "type": "login", "username": "khaled", "password": "secret123", "lastSeq": 42 }
```
`lastSeq` is the highest `seq` this device has (0 on a new device). The server answers with `auth_ok` and then a `history` message.

### `chat` (one-to-one)
```json
{ "type": "chat", "id": "khaled-1732370000000-a8f3k2", "to": "omar", "text": "hi", "sentAt": 1732370000000 }
```

### `group_create`
```json
{ "type": "group_create", "name": "Project team", "members": ["omar", "sara", "ali"] }
```
The creator is added automatically.

### `group_chat`
```json
{ "type": "group_chat", "id": "khaled-1732370005000-p2m9x1", "groupId": 3, "text": "meeting at 5", "sentAt": 1732370005000 }
```

### `sync`
Ask for missed messages without logging in again.
```json
{ "type": "sync", "lastSeq": 57 }
```

### `logout`
```json
{ "type": "logout" }
```

---

## 4. Server → Client messages

### `auth_ok`
```json
{ "type": "auth_ok", "username": "khaled",
  "users":  [ { "username": "omar", "online": true }, { "username": "sara", "online": false } ],
  "groups": [ { "groupId": 3, "name": "Project team", "members": ["khaled", "omar", "sara", "ali"] } ] }
```

### `auth_error`
```json
{ "type": "auth_error", "reason": "Wrong username or password" }
```

### `ack`
Confirms the server has stored the sender's message.
```json
{ "type": "ack", "id": "khaled-1732370000000-a8f3k2", "seq": 58, "serverTime": 1732370000120 }
```

### `chat`
Delivered to the recipient, and also to the sender's other devices.
```json
{ "type": "chat", "id": "khaled-1732370000000-a8f3k2", "seq": 58,
  "from": "khaled", "to": "omar", "text": "hi",
  "sentAt": 1732370000000, "serverTime": 1732370000120 }
```

### `group_chat`
Delivered to every member of the group.
```json
{ "type": "group_chat", "id": "khaled-1732370005000-p2m9x1", "seq": 59,
  "from": "khaled", "groupId": 3, "text": "meeting at 5",
  "sentAt": 1732370005000, "serverTime": 1732370005090 }
```

### `group_created`
Sent to every member when a group is created.
```json
{ "type": "group_created", "groupId": 3, "name": "Project team", "members": ["khaled", "omar", "sara", "ali"] }
```

### `history`
Missed messages, sent after `login` or `sync`, ordered by `seq`.
```json
{ "type": "history", "messages": [ { "type": "chat", "id": "...", "seq": 43, "...": "..." } ] }
```

### `presence`
Sent to everyone when a user comes online or goes offline.
```json
{ "type": "presence", "username": "omar", "online": false }
```

### `error`
```json
{ "type": "error", "code": "INVALID_MESSAGE", "message": "Missing field: to", "refId": "khaled-1732370000000-a8f3k2" }
```
`refId` is included when the error is about a specific message.

| Code | Meaning |
|---|---|
| `INVALID_JSON` | The frame was not valid JSON |
| `INVALID_MESSAGE` | Unknown `type` or a missing/invalid field |
| `NOT_LOGGED_IN` | Message sent before `login`/`register` |
| `UNKNOWN_USER` | Recipient does not exist |
| `UNKNOWN_GROUP` | Group does not exist or sender is not a member |
| `USERNAME_TAKEN` | Registration with an existing username |

---

## 5. Validation rules

- **Username:** 3–20 characters, letters, digits and `_` only. Case-insensitive.
- **Password:** at least 6 characters. Stored hashed (bcrypt), never in plain text.
- **Text:** 1–2000 characters. The client must display it as text, never as HTML (prevents injected code).
- **Message size:** maximum 16 KB. Larger messages get an `error` reply.
- Any message except `register`/`login` sent before logging in gets `NOT_LOGGED_IN`.
- Invalid messages get an `error` reply. They never crash the server or affect other clients.

---

## 6. Example: Khaled sends "hi" to Omar

```
Khaled's phone                     Server                          Omar's phone
     | chat {id: k-1, to: omar} ---->|                                   |
     |                               | stores it, seq = 58               |
     |<---- ack {id: k-1, seq: 58} --|                                   |
     |                               |-- chat {id: k-1, seq: 58} ------->|
```

**If Omar is offline:** the message is stored. When Omar logs in with `lastSeq: 57`,
the server sends it inside `history`.

**If the ack is lost:** Khaled's phone resends `k-1` after 3 seconds. The server sees `k-1`
is already stored, so it only re-sends the ack. Omar never gets a duplicate.

---

## 7. Protocol B (HTTP + Server-Sent Events)

Protocol B uses **the same JSON messages** with a different transport, on the HTTP port (3000):

- **Client → server:** `POST /api/send` with one JSON message as the body. The response body is the reply (`ack`, `auth_ok`, `error`…).
- **Server → client:** `GET /api/events` opens a Server-Sent Events stream. Each event's `data` is one JSON message.
- The same rules apply: same `id`/`seq`, acks, resending and `lastSeq` sync.

Details are documented by Student 4.

---

## 8. Known limitations

- Traffic is not encrypted (plain `ws://` and `http://`), so passwords cross the hotspot in clear text.
  Acceptable on a private hotspot for this course. Would need HTTPS/WSS in production.
