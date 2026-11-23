// server/memory-store.js
// TEMPORARY storage kept in memory, so the server works before the database exists.
// Everything is lost when the server restarts, and passwords are NOT hashed here.
//
// Student 3 replaces this file with the SQLite version (server/db.js + server/auth.js).
// Keep the SAME function names and return values, and websocket.js will not need to change.

const users = new Map();        // username -> { password }
const messages = [];            // all stored messages, in seq order
const messagesById = new Map(); // id -> stored message (used to detect duplicates)
let nextSeq = 1;

// Returns true if created, false if the username is already taken.
function createUser(username, password) {
  if (users.has(username)) return false;
  users.set(username, { password });
  return true;
}

// Returns true if the username exists and the password matches.
function checkPassword(username, password) {
  const user = users.get(username);
  return Boolean(user && user.password === password);
}

function userExists(username) {
  return users.has(username);
}

function listUsernames() {
  return [...users.keys()];
}

// Stores a message and gives it the next seq number.
// If a message with the same id was already stored, nothing is stored again:
// returns { isNew: false, message: <the original stored message> }.
function saveMessage(msg) {
  const existing = messagesById.get(msg.id);
  if (existing) return { isNew: false, message: existing };

  const stored = { ...msg, seq: nextSeq++, serverTime: Date.now() };
  messages.push(stored);
  messagesById.set(stored.id, stored);
  return { isNew: true, message: stored };
}

// All messages sent by or to this user with seq greater than afterSeq, in seq order.
function getMessagesFor(username, afterSeq) {
  return messages.filter(
    (m) => m.seq > afterSeq && (m.from === username || m.to === username)
  );
}

module.exports = {
  createUser,
  checkPassword,
  userExists,
  listUsernames,
  saveMessage,
  getMessagesFor,
};
