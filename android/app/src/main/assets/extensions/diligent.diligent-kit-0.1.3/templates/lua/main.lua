-- {{label}}: a Lua program.
local fruits = { "apple", "banana", "cherry" }

print("Hello from {{label}} on " .. _VERSION)
for i, fruit in ipairs(fruits) do
  print(i .. ". " .. fruit)
end
