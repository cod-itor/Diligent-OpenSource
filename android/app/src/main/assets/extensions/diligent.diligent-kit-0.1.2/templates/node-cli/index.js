// {{label}}: a small command-line program in Node.js, with no packages to install.
// Try: node index.js --name Ada --times 3
const { parseArgs } = require('node:util');

const { values } = parseArgs({
  options: {
    name: { type: 'string', default: '{{label}}' },
    times: { type: 'string', default: '1' },
  },
});

const times = Math.max(1, Number(values.times) || 1);
for (let i = 0; i < times; i += 1) {
  console.log('Hello from ' + values.name + ' on Node.js ' + process.version);
}
