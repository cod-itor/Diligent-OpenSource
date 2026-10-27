package com.vscodroid.diligent.run

import com.vscodroid.diligent.kit.KitRecipes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Run screen's matcher against the real errors.json, on the same kind
 * of logs scripts/test-diligent-kit.js uses for errors.js. Every entry is
 * covered there; here, one log per entry checks the port agrees.
 */
class ErrorCatalogTest {
    private val catalog: ErrorCatalog = run {
        val root = File("src/main/assets/extensions")
        val dir = KitRecipes.newestExtension(root.list().orEmpty().toList())!!
        ErrorCatalog.parse(File(root, "$dir/${ErrorCatalog.FILE}").readText())!!
    }

    private fun first(text: String, exitCode: Int? = null) = catalog.explain(text, exitCode).firstOrNull()

    @Test
    fun `every entry is read and matches its log`() {
        val cases = mapOf(
            "port-in-use" to "Error: listen EADDRINUSE: address already in use 127.0.0.1:3000",
            "not-in-project" to "npm error code ENOENT\nnpm error path /data/home/package.json",
            "node-missing" to "bash: node: command not found",
            "python-missing" to "zsh: command not found: python3",
            "no-internet" to "npm error network request to https://registry.npmjs.org/vite failed, reason: getaddrinfo ENOTFOUND registry.npmjs.org",
            "blocked-program" to "sh: 1: /p/node_modules/.bin/esbuild: Permission denied",
            "python-module-missing" to "ModuleNotFoundError: No module named 'pandas'",
            "syntax-error" to "SyntaxError: Unexpected token }",
            "killed" to "Killed",
            "no-space" to "ENOSPC: no space left on device, write",
            "out-of-memory" to "FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory",
            "turbopack" to "FATAL: An unexpected Turbopack error occurred.",
            "git-identity" to "*** Please tell me who you are.",
        )
        assertEquals(cases.keys.toList(), catalog.ids)
        for ((id, text) in cases) assertEquals(id, first(text)?.id, text)
    }

    @Test
    fun `combined conditions need both parts`() {
        assertNull(first("Error: ENOENT: no such file or directory, open '/p/data.csv'"))
        assertNull(first("Error: connect ECONNREFUSED 127.0.0.1:5432"))
        assertNull(first("git@github.com: Permission denied (publickey)."))
        assertNull(first("All good.", exitCode = 1))
        assertEquals("killed", first("the server ended", exitCode = 137)?.id)
    }

    @Test
    fun `placeholders, locations and fixes`() {
        val module = first("ModuleNotFoundError: No module named 'yaml.loader'")!!
        assertTrue(module.explanation.contains("The Python package yaml is not installed"), module.explanation)

        val py = first("  File \"/h/p/main.py\", line 3\n    print(\"hi\"\nSyntaxError: '(' was never closed")!!
        assertEquals("/h/p/main.py" to 3, py.file to py.line)
        assertTrue(py.explanation.contains("/h/p/main.py at line 3"))
        assertEquals(listOf(ErrorFix("openFileAtLine", "Open the file at that line", file = "/h/p/main.py", line = 3)), py.fixes)

        val node = first("/h/api/index.js:7\n  app.get(\n\nSyntaxError: Unexpected end of input\n    at wrapSafe (node:internal/modules/cjs/loader:1469:18)")!!
        assertEquals("/h/api/index.js" to 7, node.file to node.line)
        val babel = first("[plugin:vite:react-babel] SyntaxError: /h/app/src/App.jsx: Unexpected token (12:4)")!!
        assertEquals("/h/app/src/App.jsx" to 12, babel.file to babel.line)
        assertEquals(emptyList<ErrorFix>(), first("SyntaxError: Unexpected token }")!!.fixes)

        assertEquals(listOf("lowMemory", "runOnLaptop"), first("JavaScript heap out of memory")!!.fixes.map { it.action })
        assertEquals("node-api", first("bash: node: command not found")!!.fixes.single().recipe)
        assertEquals(listOf("port-in-use", "out-of-memory"), catalog.explain("EADDRINUSE\r\nJavaScript heap out of memory").map { it.id })
    }

    @Test
    fun `a broken catalog is no catalog, and a broken pattern skips its entry`() {
        assertNull(ErrorCatalog.parse(null))
        assertNull(ErrorCatalog.parse("{broken"))
        val partial = ErrorCatalog.parse("""{"errors":[{"id":"bad","patterns":["("]},{"id":"ok","patterns":["boom"],"title":"T","explanation":"E"}]}""")
        assertNotNull(partial)
        assertEquals(listOf("ok"), partial!!.ids)
    }
}
