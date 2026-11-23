// server/index.js
// Step 2: a minimal test server, to check that phones on the hotspot can reach the laptop.
const http = require('http'); // Node's built-in web server module
const os = require('os');     // used to find the laptop's IP addresses

const PORT = 3000;

// Every phone that opens the address gets this small page
const server = http.createServer((req, res) => {
  console.log(`Request from ${req.socket.remoteAddress}`);
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(`
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <h1>It works! 🎉</h1>
    <p>This page came from the laptop server.</p>
  `);
});

// '0.0.0.0' = accept connections from other devices, not just this laptop
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Server running on port ${PORT}`);
  // Print the laptop's IP addresses, so you know what to type on the phones
  for (const list of Object.values(os.networkInterfaces())) {
    for (const net of list) {
      if (net.family === 'IPv4' && !net.internal) {
        console.log(`Open on phones: http://${net.address}:${PORT}`);
      }
    }
  }
});