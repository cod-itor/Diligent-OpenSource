// {{label}}: a live chat with Express and Socket.io. Open it in two tabs and talk.
const path = require('path');
const http = require('http');
const express = require('express');
const { Server } = require('socket.io');

const app = express();
const server = http.createServer(app);
const io = new Server(server);
const port = Number(process.env.PORT) || 3000;

app.use(express.static(path.join(__dirname, 'public')));

io.on('connection', (socket) => {
  io.emit('chat', 'Someone joined. ' + io.engine.clientsCount + ' online.');
  socket.on('chat', (text) => {
    io.emit('chat', String(text).slice(0, 500));
  });
  socket.on('disconnect', () => {
    io.emit('chat', 'Someone left.');
  });
});

server.listen(port, '127.0.0.1', () => {
  console.log('Chat on http://127.0.0.1:' + port);
});
