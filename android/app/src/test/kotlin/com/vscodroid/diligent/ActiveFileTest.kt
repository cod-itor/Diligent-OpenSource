package com.vscodroid.diligent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ActiveFileTest {

    @Test
    fun `a file with unsaved changes`() {
        assertEquals(
            ActiveFile.Result.File(ActiveFile("/p/src/app.js", "app.js", dirty = true)),
            ActiveFile.parse("""{"path":"/p/src/app.js","name":"app.js","dirty":true}"""),
        )
    }

    @Test
    fun `an untitled file has no path`() {
        assertEquals(
            ActiveFile.Result.File(ActiveFile(null, "Untitled-1", dirty = false)),
            ActiveFile.parse("""{"path":null,"name":"Untitled-1","dirty":false}"""),
        )
    }

    @Test
    fun `no name means no file in front`() {
        assertEquals(ActiveFile.Result.None, ActiveFile.parse("""{"path":null,"name":null,"dirty":false}"""))
        assertEquals(ActiveFile.Result.None, ActiveFile.parse("""{"name":"  "}"""))
        assertEquals(ActiveFile.Result.None, ActiveFile.parse("""{"name":3}"""))
    }

    @Test
    fun `a missing dirty flag reads as saved`() {
        assertEquals(
            ActiveFile.Result.File(ActiveFile("/a", "a", dirty = false)),
            ActiveFile.parse("""{"path":"/a","name":"a"}"""),
        )
    }

    @Test
    fun `text that is not a JSON object is ignored`() {
        for (junk in listOf("", "null", "[]", "not json", "\"a.js\"")) assertNull(ActiveFile.parse(junk), junk)
    }
}
