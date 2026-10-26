package com.vscodroid.diligent.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunDetectorTest {

    private fun detect(vararg files: Pair<String, String>): DetectedRun? = RunDetector.detect(mapOf(*files)::get)

    private fun pkg(scripts: String, deps: String = "{}", devDeps: String = "{}") =
        "package.json" to """{ "name": "x", "scripts": $scripts, "dependencies": $deps, "devDependencies": $devDeps }"""

    @Test
    fun `a Next js dev script runs with webpack on port 3000`() {
        val found = detect(pkg("""{ "dev": "next dev", "build": "next build" }""", deps = """{ "next": "16.0.0", "react": "19" }"""))!!
        assertEquals(RunProfile("dev", "npm run dev -- --webpack", 3000), found.profile)
        assertEquals(ProjectKind.NextJs, found.kind)
        assertTrue(found.webpackRewrite)
    }

    @Test
    fun `Next js is recognised from the script alone`() {
        val found = detect(pkg("""{ "dev": "next dev --turbopack" }"""))!!
        assertEquals(ProjectKind.NextJs, found.kind)
        assertEquals("npm run dev -- --webpack", found.profile.command)
    }

    @Test
    fun `a script that already asks for webpack is left alone`() {
        val found = detect(pkg("""{ "dev": "next dev --webpack" }""", deps = """{ "next": "16" }"""))!!
        assertEquals("npm run dev", found.profile.command)
        assertFalse(found.webpackRewrite)
    }

    @Test
    fun `dev wins over start`() {
        assertEquals("dev", detect(pkg("""{ "start": "node server.js", "dev": "nodemon server.js" }"""))!!.profile.name)
    }

    @Test
    fun `start is used when there is no dev, and next start is not rewritten`() {
        val found = detect(pkg("""{ "start": "next start", "build": "next build" }""", deps = """{ "next": "16" }"""))!!
        assertEquals(RunProfile("start", "npm start", 3000), found.profile)
        assertFalse(found.webpackRewrite)
    }

    @Test
    fun `Vite is port 5173, vite preview 4173`() {
        val dev = detect(pkg("""{ "dev": "vite" }""", devDeps = """{ "vite": "6" }"""))!!
        assertEquals(RunProfile("dev", "npm run dev", 5173), dev.profile)
        assertEquals(ProjectKind.Vite, dev.kind)
        assertEquals(4173, detect(pkg("""{ "start": "vite preview" }"""))!!.profile.port)
    }

    @Test
    fun `a port flag in the script beats the framework default`() {
        assertEquals(4000, detect(pkg("""{ "dev": "vite --port 4000" }"""))!!.profile.port)
        assertEquals(4001, detect(pkg("""{ "dev": "vite --port=4001 --host" }"""))!!.profile.port)
        assertEquals(3005, detect(pkg("""{ "dev": "next dev -p 3005" }"""))!!.profile.port)
        assertEquals(8081, detect(pkg("""{ "dev": "PORT=8081 node server.js" }"""))!!.profile.port)
    }

    @Test
    fun `a plain node script has no port unless it says one`() {
        val found = detect(pkg("""{ "start": "node index.js" }"""))!!
        assertEquals(RunProfile("start", "npm start", null), found.profile)
        assertEquals(ProjectKind.Node, found.kind)
    }

    @Test
    fun `an out of range port flag is ignored`() {
        assertEquals(5173, detect(pkg("""{ "dev": "vite --port 99999" }"""))!!.profile.port)
    }

    @Test
    fun `package json without dev or start falls through to Python, then to nothing`() {
        assertNull(detect(pkg("""{ "build": "tsc" }""")))
        assertEquals("python3 main.py", detect(pkg("""{ "test": "jest" }"""), "main.py" to "print(1)")!!.profile.command)
    }

    @Test
    fun `a broken package json is not a crash`() {
        assertNull(detect("package.json" to "{ nope"))
        assertNull(detect("package.json" to """{ "scripts": { "dev": "" } }"""))
        assertNull(detect("package.json" to """{ "scripts": { "dev": 1 } }"""))
    }

    @Test
    fun `main py is preferred to app py`() {
        val found = detect("main.py" to "print('hi')", "app.py" to "print('hi')")!!
        assertEquals(RunProfile("main", "python3 main.py", null), found.profile)
        assertEquals(ProjectKind.Python, found.kind)
        assertEquals("python3 app.py", detect("app.py" to "")!!.profile.command)
    }

    @Test
    fun `python ports come only from what the file plainly says`() {
        assertEquals(
            5000,
            RunDetector.pythonPort("from flask import Flask\napp = Flask(__name__)\nif __name__ == '__main__':\n    app.run(debug=True)\n"),
        )
        assertEquals(8080, RunDetector.pythonPort("from flask import Flask\napp.run(host='0.0.0.0', port=8080)"))
        assertEquals(8000, RunDetector.pythonPort("import uvicorn\nuvicorn.run(app, host='127.0.0.1')"))
        assertEquals(9000, RunDetector.pythonPort("import uvicorn\nuvicorn.run(\n  'main:app',\n  port = 9000,\n)"))
        assertNull(RunDetector.pythonPort("from fastapi import FastAPI\napp = FastAPI()"))
        assertNull(RunDetector.pythonPort("print('hello')"))
        assertNull(RunDetector.pythonPort("from flask import Flask\napp = Flask(__name__)"))
        assertNull(RunDetector.pythonPort("import os\nport = int(os.environ['PORT'])"))
    }

    @Test
    fun `nothing at all is nothing`() {
        assertNull(detect())
        assertNull(detect("README.md" to "# hi"))
    }
}
