// server/heartbeat.js
// Detects "dead" connections.
// When a phone locks its screen or leaves Wi-Fi range, its socket often does NOT close cleanly,
// so the server would think it is still online. To catch this, the server sends a WebSocket
// ping frame every 10 seconds; browsers answer with a pong automatically (no client code needed).
// A client that misses 2 pings in a row is disconnected, which triggers the normal 'close' handling.

const PING_INTERVAL_MS = 10_000;
const MAX_MISSED_PINGS = 2;

function startHeartbeat(wss) {
  // Every new connection starts with 0 missed pings; any pong resets the counter.
  wss.on('connection', (ws) => {
    ws.missedPings = 0;
    ws.on('pong', () => { ws.missedPings = 0; });
  });

  const timer = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.missedPings >= MAX_MISSED_PINGS) {
        console.log(`Heartbeat: dropping unresponsive client ${ws.username || '(not logged in)'}`);
        ws.terminate(); // fires the 'close' event, so the user is marked offline
        continue;
      }
      ws.missedPings++;
      ws.ping();
    }
  }, PING_INTERVAL_MS);

  // Stop the timer if the WebSocket server shuts down.
  wss.on('close', () => clearInterval(timer));
}

module.exports = { startHeartbeat };
