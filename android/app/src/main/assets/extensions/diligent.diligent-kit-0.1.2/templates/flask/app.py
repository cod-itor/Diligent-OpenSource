"""{{label}}: a small web app with Flask."""

from datetime import datetime

from flask import Flask, jsonify, render_template_string

app = Flask(__name__)

PAGE = """<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{{ title }}</title></head>
<body style="font-family: system-ui, sans-serif; padding: 1rem">
<h1>{{ title }}</h1>
<p>Edit app.py, then reload this page.</p>
<p><a href="/api/time">/api/time</a> answers with JSON.</p>
</body>
</html>"""


@app.get("/")
def home():
    return render_template_string(PAGE, title="Hello from {{label}}")


@app.get("/api/time")
def time():
    return jsonify(now=datetime.now().isoformat(timespec="seconds"))


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=5000, debug=True)
