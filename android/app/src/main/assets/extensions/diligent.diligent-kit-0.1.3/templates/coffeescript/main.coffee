# {{label}}: CoffeeScript, compiled to JavaScript and run with Node.js.
square = (x) -> x * x
numbers = [1..5]

console.log "Hello from {{label}} in CoffeeScript"
console.log "Squares: #{(square n for n in numbers).join ', '}"
