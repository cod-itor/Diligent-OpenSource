<?php
// {{label}}: a web page made with PHP, served by PHP's own web server.
$now = date('H:i:s');
$name = htmlspecialchars($_GET['name'] ?? 'there');
?>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{{label}}</title>
</head>
<body style="font-family: system-ui, sans-serif; padding: 1rem">
  <h1>Hello <?= $name ?>, from {{label}}</h1>
  <p>The server's clock says <?= $now ?>. Reload to see it change.</p>
  <form><input name="name" placeholder="Your name"> <button>Say hello</button></form>
</body>
</html>
