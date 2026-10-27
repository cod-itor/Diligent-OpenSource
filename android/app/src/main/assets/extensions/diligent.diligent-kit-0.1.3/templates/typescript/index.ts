// {{label}}: TypeScript, compiled with tsc and run with Node.js.

interface Greeting {
  who: string;
  times: number;
}

function greet({ who, times }: Greeting): string[] {
  return Array.from({ length: times }, () => `Hello from ${who} on Node.js ${process.version}`);
}

for (const line of greet({ who: '{{label}}', times: 1 })) {
  console.log(line);
}
