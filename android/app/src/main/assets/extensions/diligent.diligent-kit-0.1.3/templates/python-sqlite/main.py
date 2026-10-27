"""{{label}}: a small database with Python's built-in sqlite3. The data stays in notes.db."""

import sqlite3
from datetime import datetime


def main() -> None:
    db = sqlite3.connect("notes.db")
    db.execute("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY, text TEXT NOT NULL, added TEXT NOT NULL)")
    db.execute("INSERT INTO notes (text, added) VALUES (?, ?)", ("Hello from {{label}}", datetime.now().isoformat(timespec="seconds")))
    db.commit()

    rows = db.execute("SELECT id, text, added FROM notes ORDER BY id").fetchall()
    for row_id, text, added in rows:
        print(f"{row_id:>3}  {added}  {text}")
    print(f"{len(rows)} notes saved with SQLite {sqlite3.sqlite_version}")
    db.close()


if __name__ == "__main__":
    main()
