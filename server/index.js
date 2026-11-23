// server/index.js
// Entry point: starts one HTTP server that
//   1. serves the client web page (files in /client) to the phones, and
//   2. carries the WebSocket connections (Protocol A) on the same port.
// Run with:  npm start   (or: node server/index.js)

const http = require('http');
const os = require('os');
const path = require('path');
const express = require('express');
const { attachWebSocketServer } = require('./websocket');

const PORT = process.env.PORT || 3000;

// ---- HTTP: serve the client files ----
const app = express();
app.use(express.static(path.join(__dirname, '..', 'client')));

// Express and the WebSocket server share this one HTTP server (one port, one firewall rule).
const server = http.createServer(app);
attachWebSocketServer(server);

// '0.0.0.0' = accept connections from any network interface (so phones on the hotspot can connect),
// not just from this laptop ('localhost').
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Server running on port ${PORT}`);
  printAddresses();
});

// Print the laptop's IPv4 addresses so we know what to open on the phones.
function printAddresses() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const net of list) {
      if (net.family === 'IPv4' && !net.internal) {
        console.log(`Open on phones: http://${net.address}:${PORT}`);
      }
    }
  }
}
