package com.vscodroid.diligent.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BundledShellQuoteTest {

    @Test
    fun `plain text is wrapped`() {
        assertEquals("'hello world'", BundledShell.quote("hello world"))
    }

    @Test
    fun `a single quote cannot end the word`() {
        assertEquals("'it'\\''s'", BundledShell.quote("it's"))
    }

    @Test
    fun `shell syntax stays literal`() {
        val nasty = "\$(rm -rf ~); `id` && echo \"x\" | cat > /dev/null; \\n"
        assertEquals("'$nasty'", BundledShell.quote(nasty))
    }

    @Test
    fun `the empty string is still one word`() {
        assertEquals("''", BundledShell.quote(""))
    }
}
