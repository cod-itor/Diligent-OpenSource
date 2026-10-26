package com.vscodroid.diligent.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class RunConfigTest {

    private fun ok(text: String): RunConfig = when (val r = RunConfig.parse(text)) {
        is RunConfigParse.Ok -> r.config
        is RunConfigParse.Invalid -> fail("refused: ${r.problem}")
    }

    private fun problem(text: String): RunConfigProblem = when (val r = RunConfig.parse(text)) {
        is RunConfigParse.Ok -> fail("accepted: ${r.config}")
        is RunConfigParse.Invalid -> r.problem
    }

    @Test
    fun `the guide's example reads as written`() {
        val config = ok("""{ "profiles": [ { "name": "dev", "command": "npm run dev", "port": 3000, "where": "phone", "env": {} } ] }""")
        assertEquals(listOf(RunProfile("dev", "npm run dev", 3000, "phone", emptyMap())), config.profiles)
    }

    @Test
    fun `where defaults to phone and port may be left out or null`() {
        val config = ok("""{ "profiles": [ { "name": "a", "command": "x" }, { "name": "b", "command": "y", "port": null } ] }""")
        assertEquals(RunProfile("a", "x", null, "phone"), config.profiles[0])
        assertEquals(null, config.profiles[1].port)
    }

    @Test
    fun `unknown fields are ignored, at the top and in a profile`() {
        val config = ok(
            """{ "version": 2, "profiles": [ { "name": "dev", "command": "npm run dev", "color": "red", "watch": { "a": 1 } } ], "extra": [] }""",
        )
        assertEquals(1, config.profiles.size)
    }

    @Test
    fun `an unknown where is kept, not refused`() {
        assertEquals("laptop", ok("""{ "profiles": [ { "name": "a", "command": "x", "where": "laptop" } ] }""").profiles[0].where)
    }

    @Test
    fun `env values are strings, numbers and booleans become their text`() {
        val env = ok("""{ "profiles": [ { "name": "a", "command": "x", "env": { "A": "1 2", "B": 3, "C": true, "_D9": "" } } ] }""")
            .profiles[0].env
        assertEquals(mapOf("A" to "1 2", "B" to "3", "C" to "true", "_D9" to ""), env)
    }

    @Test
    fun `a port given as a string of digits is accepted`() {
        assertEquals(8080, ok("""{ "profiles": [ { "name": "a", "command": "x", "port": "8080" } ] }""").profiles[0].port)
    }

    @Test
    fun `names and commands are trimmed`() {
        val p = ok("""{ "profiles": [ { "name": "  dev ", "command": " npm run dev\n" } ] }""").profiles[0]
        assertEquals("dev", p.name)
        assertEquals("npm run dev", p.command)
    }

    @Test
    fun `no profiles at all is an empty config, not an error`() {
        assertEquals(emptyList<RunProfile>(), ok("""{ "profiles": [] }""").profiles)
    }

    @Test
    fun `invalid files are refused with the reason`() {
        assertTrue(problem("not json") is RunConfigProblem.NotJson)
        assertTrue(problem("[1, 2]") is RunConfigProblem.NotJson)
        assertEquals(RunConfigProblem.NoProfiles, problem("{}"))
        assertEquals(RunConfigProblem.NoProfiles, problem("""{ "profiles": {} }"""))
        assertEquals(RunConfigProblem.ProfileNotObject(1), problem("""{ "profiles": [ { "name": "a", "command": "x" }, "b" ] }"""))
        assertEquals(RunConfigProblem.MissingName(0), problem("""{ "profiles": [ { "command": "x" } ] }"""))
        assertEquals(RunConfigProblem.MissingName(0), problem("""{ "profiles": [ { "name": "  ", "command": "x" } ] }"""))
        assertEquals(RunConfigProblem.MissingName(0), problem("""{ "profiles": [ { "name": 5, "command": "x" } ] }"""))
        assertEquals(RunConfigProblem.MissingCommand(0), problem("""{ "profiles": [ { "name": "a" } ] }"""))
        assertEquals(RunConfigProblem.MissingCommand(0), problem("""{ "profiles": [ { "name": "a", "command": ["npm"] } ] }"""))
        assertEquals(RunConfigProblem.DuplicateName("a"), problem("""{ "profiles": [ { "name": "a", "command": "x" }, { "name": "a", "command": "y" } ] }"""))
    }

    @Test
    fun `ports outside 1 to 65535 or not whole are refused`() {
        for (port in listOf("0", "65536", "-1", "3000.5", "\"30x\"", "\"\"", "true", "[3000]", "99999999999")) {
            assertEquals(
                RunConfigProblem.BadPort(0),
                problem("""{ "profiles": [ { "name": "a", "command": "x", "port": $port } ] }"""),
                "port $port",
            )
        }
        assertEquals(65535, ok("""{ "profiles": [ { "name": "a", "command": "x", "port": 65535 } ] }""").profiles[0].port)
        assertEquals(3000, ok("""{ "profiles": [ { "name": "a", "command": "x", "port": 3000.0 } ] }""").profiles[0].port)
    }

    @Test
    fun `a where that is not text is refused`() {
        assertEquals(RunConfigProblem.BadWhere(0), problem("""{ "profiles": [ { "name": "a", "command": "x", "where": 1 } ] }"""))
        assertEquals(RunConfigProblem.BadWhere(0), problem("""{ "profiles": [ { "name": "a", "command": "x", "where": " " } ] }"""))
    }

    @Test
    fun `env names a shell cannot export and nested values are refused`() {
        assertEquals(RunConfigProblem.BadEnv(0, "A-B"), problem("""{ "profiles": [ { "name": "a", "command": "x", "env": { "A-B": "1" } } ] }"""))
        assertEquals(RunConfigProblem.BadEnv(0, "1A"), problem("""{ "profiles": [ { "name": "a", "command": "x", "env": { "1A": "1" } } ] }"""))
        assertEquals(
            RunConfigProblem.BadEnv(0, "X; rm -rf ~"),
            problem("""{ "profiles": [ { "name": "a", "command": "x", "env": { "X; rm -rf ~": "1" } } ] }"""),
        )
        assertEquals(RunConfigProblem.BadEnv(0, "A"), problem("""{ "profiles": [ { "name": "a", "command": "x", "env": { "A": { "b": 1 } } } ] }"""))
        assertEquals(RunConfigProblem.BadEnv(0, null), problem("""{ "profiles": [ { "name": "a", "command": "x", "env": "A=1" } ] }"""))
    }

    @Test
    fun `what is written reads back the same`() {
        val config = RunConfig(
            listOf(
                RunProfile("dev", "npm run dev -- --webpack", 3000),
                RunProfile(
                    "api \"v2\"",
                    "cd server && python3 src/app.py # it's \\ fine",
                    null,
                    "laptop",
                    mapOf("A" to "x\ny\t\u0001", "B" to "it's \"quoted\""),
                ),
            ),
        )
        assertEquals(config, ok(config.toJson()))
    }

    @Test
    fun `the written file is readable, with slashes left alone`() {
        val text = RunConfig(listOf(RunProfile("main", "python3 src/main.py", 8000))).toJson()
        assertEquals(
            """
            {
              "profiles": [
                {
                  "name": "main",
                  "command": "python3 src/main.py",
                  "port": 8000,
                  "where": "phone",
                  "env": {}
                }
              ]
            }

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `an empty config still writes valid JSON`() {
        assertEquals(RunConfig(emptyList()), ok(RunConfig(emptyList()).toJson()))
    }

    @Test
    fun `the port field of the form`() {
        assertEquals(null, RunConfig.parsePortField("  ").getOrThrow())
        assertEquals(3000, RunConfig.parsePortField(" 3000 ").getOrThrow())
        for (bad in listOf("0", "70000", "-3", "+3", "3 000", "abc", "3e3")) {
            assertTrue(RunConfig.parsePortField(bad).isFailure, bad)
        }
    }

    @Test
    fun `env names`() {
        assertTrue(RunConfig.isEnvName("NODE_ENV"))
        assertTrue(RunConfig.isEnvName("_x1"))
        assertFalse(RunConfig.isEnvName(""))
        assertFalse(RunConfig.isEnvName("A B"))
        assertFalse(RunConfig.isEnvName("A=B"))
    }
}
