"""{{label}}: a web API with FastAPI. Interactive docs are at /docs."""

from datetime import datetime

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="{{label}}")


class Note(BaseModel):
    text: str


notes: list[Note] = []


@app.get("/")
def home():
    return {"message": "Hello from {{label}}"}


@app.get("/api/time")
def time():
    return {"now": datetime.now().isoformat(timespec="seconds")}


@app.post("/api/notes")
def add_note(note: Note):
    notes.append(note)
    return {"count": len(notes)}
