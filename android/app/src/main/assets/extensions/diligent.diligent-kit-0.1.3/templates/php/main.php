<?php
// {{label}}: a PHP script for the terminal.

$languages = ['PHP', 'JavaScript', 'Python'];

echo "Hello from {{label}} on PHP " . PHP_VERSION . "\n";
foreach ($languages as $i => $name) {
    printf("%d. %s\n", $i + 1, $name);
}
