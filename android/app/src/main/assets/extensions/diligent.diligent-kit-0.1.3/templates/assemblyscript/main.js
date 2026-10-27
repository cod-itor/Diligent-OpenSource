// Loads the WebAssembly module that asc built and calls into it.
import { add, fib } from './build/module.js';

console.log('Hello from {{label}} in WebAssembly: 2 + 3 = ' + add(2, 3) + ', fib(20) = ' + fib(20));
