package com.vscodroid.diligent.laptop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MachinesTest {

    private val mac = Machine("mac", "192.168.1.20", "me", MachineOs.MacOs)

    @Test
    fun `reads machines json`() {
        val text = """{ "machines": [
            { "name": "mac", "host": "192.168.1.20", "user": "me", "os": "macos" },
            { "name": "box", "host": "box.tail1234.ts.net", "user": "dev_1", "os": "linux", "extra": true }
        ] }"""
        assertEquals(
            listOf(mac, Machine("box", "box.tail1234.ts.net", "dev_1", MachineOs.Linux)),
            Machines.parse(text),
        )
    }

    @Test
    fun `skips entries that would put an option or a shell word on the ssh line`() {
        val text = """{ "machines": [
            { "name": "a", "host": "-oProxyCommand=evil", "user": "me" },
            { "name": "b", "host": "ok.local", "user": "-l" },
            { "name": "c", "host": "a b", "user": "me" },
            { "name": "d", "host": "ok", "user": "x;rm" },
            { "name": "two\nlines", "host": "ok", "user": "me" },
            { "name": "mac", "host": "192.168.1.20", "user": "me" },
            { "name": "mac", "host": "other", "user": "me" },
            "not an object",
            { "name": 3, "host": "ok", "user": "me" }
        ] }"""
        assertEquals(listOf(mac), Machines.parse(text))
    }

    @Test
    fun `garbage reads as no machines`() {
        assertEquals(emptyList<Machine>(), Machines.parse("nope"))
        assertEquals(emptyList<Machine>(), Machines.parse("{}"))
        assertEquals(emptyList<Machine>(), Machines.parse("""{"machines": 5}"""))
    }

    @Test
    fun `a name may hold quotes and spaces`() {
        val m = Machine("Dita's \"Mac\" \$(x)", "mac.local", "dita", MachineOs.MacOs)
        assertNull(Machines.problem(m, emptyList()))
        assertEquals(listOf(m), Machines.parse(Machines.toJson(listOf(m))))
    }

    @Test
    fun `form problems`() {
        assertEquals(MachineProblem.NoName, Machines.problem(mac.copy(name = ""), emptyList()))
        assertEquals(MachineProblem.BadHost, Machines.problem(mac.copy(host = "-x"), emptyList()))
        assertEquals(MachineProblem.BadUser, Machines.problem(mac.copy(user = "a@b"), emptyList()))
        assertEquals(MachineProblem.Duplicate, Machines.problem(mac, listOf(mac.copy(host = "x"))))
        assertFalse(Machines.isHost("a:b"))
        assertTrue(Machines.isHost("100.101.102.103"))
    }

    @Test
    fun `save and load round trip`(@TempDir home: File) {
        assertEquals(emptyList<Machine>(), Machines.load(home))
        val list = listOf(mac, Machine("box", "box", "root", MachineOs.Linux))
        Machines.save(home, list)
        assertTrue(File(home, ".diligent/machines.json").isFile)
        assertEquals(list, Machines.load(home))
    }
}
