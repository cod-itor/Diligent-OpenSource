const express = require('express');

const app = express();
const port = Number(process.env.PORT) || 3000;

app.use(express.json());

app.get('/', (req, res) => {
  res.json({ message: 'Hello from {{label}}' });
});

app.get('/api/time', (req, res) => {
  res.json({ now: new Date().toISOString() });
});

app.listen(port, '127.0.0.1', () => {
  console.log('Listening on http://127.0.0.1:' + port);
});
