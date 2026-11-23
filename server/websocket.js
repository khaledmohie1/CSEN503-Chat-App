// server/websocket.js
// Protocol A: JSON messages over WebSocket (see docs/protocol.md).
// Responsibilities:
//   - accept many phones at once and track who is online
//   - validate every incoming message (bad input never crashes the server)
//   - store chat messages, ack them, and deliver them to the recipient
//   - prevent duplicates (same id is stored/forwarded only once)
//   - send missed messages on login/sync (offline sync with lastSeq)
//   - handle disconnects without affecting other clients

const { WebSocketServer, WebSocket } = require('ws');
const store = require('./memory-store'); // Student 3: swap for the SQLite version
const { startHeartbeat } = require('./heartbeat');

const MAX_FRAME_BYTES = 16 * 1024;           // larger frames are rejected by the ws library
const MAX_TEXT_LENGTH = 2000;
const USERNAME_PATTERN = /^[a-zA-Z0-9_]{3,20}$/;
const MIN_PASSWORD_LENGTH = 6;

// username -> Set of open sockets. A Set, because one user may be logged in on several devices.
const online = new Map();

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

function attachWebSocketServer(httpServer) {
  const wss = new WebSocketServer({ server: httpServer, maxPayload: MAX_FRAME_BYTES });
  startHeartbeat(wss);

  wss.on('connection', (ws, req) => {
    ws.username = null; // set after a successful login/register
    console.log(`Connected: ${req.socket.remoteAddress} (total sockets: ${wss.clients.size})`);

    ws.on('message', (data, isBinary) => handleRawMessage(ws, data, isBinary));
    ws.on('close', () => {
      removeSocket(ws);
      console.log(`Disconnected: ${ws.username || '(not logged in)'} (total sockets: ${wss.clients.size})`);
    });
    // Errors on one socket (e.g. frame too large) are logged, never thrown,
    // so they cannot crash the server or affect other clients.
    ws.on('error', (err) => console.warn(`Socket error (${ws.username || 'anonymous'}): ${err.message}`));
  });

  return wss;
}

// ---------------------------------------------------------------------------
// Incoming messages
// ---------------------------------------------------------------------------

function handleRawMessage(ws, data, isBinary) {
  if (isBinary) {
    return sendError(ws, 'INVALID_MESSAGE', 'Binary frames are not supported');
  }

  let msg;
  try {
    msg = JSON.parse(data.toString());
  } catch {
    return sendError(ws, 'INVALID_JSON', 'Message is not valid JSON');
  }

  if (!msg || typeof msg !== 'object' || Array.isArray(msg) || typeof msg.type !== 'string') {
    return sendError(ws, 'INVALID_MESSAGE', 'Message must be a JSON object with a "type" field');
  }

  // Only register/login are allowed before the user is logged in.
  if (!ws.username && msg.type !== 'register' && msg.type !== 'login') {
    return sendError(ws, 'NOT_LOGGED_IN', 'Log in first');
  }

  try {
    switch (msg.type) {
      case 'register':     return handleRegister(ws, msg);
      case 'login':        return handleLogin(ws, msg);
      case 'chat':         return handleChat(ws, msg);
      case 'sync':         return sendHistory(ws, readLastSeq(msg));
      case 'logout':       return handleLogout(ws);
      case 'group_create':
      case 'group_chat':
        // Group chat is added by Student 2 (server/groups.js).
        return sendError(ws, 'INVALID_MESSAGE', 'Group chat is not implemented yet');
      default:
        return sendError(ws, 'INVALID_MESSAGE', `Unknown type: ${msg.type}`);
    }
  } catch (err) {
    // Last safety net: a bug in one handler must not take the server down.
    console.error('Error while handling message:', err);
    sendError(ws, 'INVALID_MESSAGE', 'Server could not process this message');
  }
}

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

function handleRegister(ws, msg) {
  const credentials = readCredentials(ws, msg);
  if (!credentials) return;

  if (!store.createUser(credentials.username, credentials.password)) {
    return sendError(ws, 'USERNAME_TAKEN', 'This username is already taken');
  }
  console.log(`Registered: ${credentials.username}`);
  logIn(ws, credentials.username, 0); // new account: no history yet
}

function handleLogin(ws, msg) {
  const credentials = readCredentials(ws, msg);
  if (!credentials) return;

  if (!store.checkPassword(credentials.username, credentials.password)) {
    return send(ws, { type: 'auth_error', reason: 'Wrong username or password' });
  }
  logIn(ws, credentials.username, readLastSeq(msg));
}

function handleChat(ws, msg) {
  // ---- validate ----
  if (typeof msg.id !== 'string' || msg.id.length < 1 || msg.id.length > 100) {
    return sendError(ws, 'INVALID_MESSAGE', 'Missing or invalid field: id');
  }
  if (typeof msg.to !== 'string') {
    return sendError(ws, 'INVALID_MESSAGE', 'Missing field: to', msg.id);
  }
  if (typeof msg.text !== 'string' || msg.text.length < 1 || msg.text.length > MAX_TEXT_LENGTH) {
    return sendError(ws, 'INVALID_MESSAGE', `Text must be 1-${MAX_TEXT_LENGTH} characters`, msg.id);
  }
  const to = msg.to.toLowerCase();
  if (!store.userExists(to)) {
    return sendError(ws, 'UNKNOWN_USER', `No user named ${msg.to}`, msg.id);
  }

  // ---- store (duplicates are detected here by id) ----
  const { isNew, message } = store.saveMessage({
    type: 'chat',
    id: msg.id,
    from: ws.username,
    to,
    text: msg.text,
    sentAt: typeof msg.sentAt === 'number' ? msg.sentAt : null,
  });

  // ---- ack: always, even for a duplicate, so the sender stops resending ----
  send(ws, { type: 'ack', id: message.id, seq: message.seq, serverTime: message.serverTime });

  // ---- deliver: only the first time, so the recipient never gets a duplicate ----
  if (isNew) {
    // Recipient's devices + the sender's OTHER devices (so all of them show the conversation).
    const targets = new Set([...socketsOf(to), ...socketsOf(ws.username)]);
    targets.delete(ws);
    for (const target of targets) send(target, message);
  }
}

function handleLogout(ws) {
  removeSocket(ws);
  ws.username = null;
}

// ---------------------------------------------------------------------------
// Login / online tracking
// ---------------------------------------------------------------------------

function logIn(ws, username, lastSeq) {
  // If this socket was logged in as someone else, detach it first.
  if (ws.username) removeSocket(ws);

  const wasOffline = socketsOf(username).size === 0;
  ws.username = username;
  if (!online.has(username)) online.set(username, new Set());
  online.get(username).add(ws);
  console.log(`Logged in: ${username} (online users: ${online.size})`);

  const users = store
    .listUsernames()
    .filter((name) => name !== username)
    .map((name) => ({ username: name, online: online.has(name) }));

  send(ws, { type: 'auth_ok', username, users, groups: [] }); // groups: added by Student 2
  sendHistory(ws, lastSeq);

  if (wasOffline) broadcastPresence(username, true);
}

// Removes a socket from the online list; if it was the user's last device, they go offline.
function removeSocket(ws) {
  const username = ws.username;
  if (!username) return;

  const sockets = online.get(username);
  if (!sockets) return;
  sockets.delete(ws);
  if (sockets.size === 0) {
    online.delete(username);
    broadcastPresence(username, false);
  }
}

function broadcastPresence(username, isOnline) {
  for (const [name, sockets] of online) {
    if (name === username) continue;
    for (const ws of sockets) send(ws, { type: 'presence', username, online: isOnline });
  }
}

// Offline sync: every message for this user with seq > lastSeq.
function sendHistory(ws, lastSeq) {
  send(ws, { type: 'history', messages: store.getMessagesFor(ws.username, lastSeq) });
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function socketsOf(username) {
  return online.get(username) || new Set();
}

// Sends one JSON message. Skips sockets that are closing/closed instead of throwing.
function send(ws, obj) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

function sendError(ws, code, message, refId) {
  const error = { type: 'error', code, message };
  if (refId) error.refId = refId;
  send(ws, error);
}

// Validates username/password; sends an error and returns null if invalid.
function readCredentials(ws, msg) {
  if (typeof msg.username !== 'string' || !USERNAME_PATTERN.test(msg.username)) {
    sendError(ws, 'INVALID_MESSAGE', 'Username must be 3-20 letters, digits or _');
    return null;
  }
  if (typeof msg.password !== 'string' || msg.password.length < MIN_PASSWORD_LENGTH) {
    sendError(ws, 'INVALID_MESSAGE', `Password must be at least ${MIN_PASSWORD_LENGTH} characters`);
    return null;
  }
  return { username: msg.username.toLowerCase(), password: msg.password };
}

function readLastSeq(msg) {
  return Number.isInteger(msg.lastSeq) && msg.lastSeq >= 0 ? msg.lastSeq : 0;
}

module.exports = { attachWebSocketServer };
