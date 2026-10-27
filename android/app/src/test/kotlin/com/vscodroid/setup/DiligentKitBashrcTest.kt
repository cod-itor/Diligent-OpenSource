package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Diligent's `kit` command in `.bashrc`: written into a new file, appended
 * once to a file written before it existed, and never touching the rest.
 */
class DiligentKitBashrcTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var bashrc: File

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
        }
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        bashrc = File(filesDir, "home/.bashrc")
        bashrc.parentFile?.mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    @Test
    fun `the kit function runs the shipped kit js`() {
        assertEquals(
            "# Diligent: kit\nkit() { node \"\$HOME/.diligent/kit/kit.js\" \"\$@\"; }\n",
            KIT_BLOCK,
        )
    }

    @Test
    fun `a new bashrc has the kit function once`() {
        FirstRunSetup::class.java
            .getDeclaredMethod("createBashrc")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))

        val text = bashrc.readText()
        assertTrue(text.contains(KIT_BLOCK), "createBashrc did not write the kit function")
        FirstRunSetup(context).ensureKitFunction()
        assertEquals(text, bashrc.readText(), "the appender added a second copy")
    }

    @Test
    fun `an older bashrc gets it appended, with its own bytes unchanged`() {
        val original = "# caf".toByteArray() + byteArrayOf(0xE9.toByte()) + "\nalias l='ls'\n".toByteArray()
        bashrc.writeBytes(original)

        FirstRunSetup(context).ensureKitFunction()
        val after = bashrc.readBytes()
        assertArrayEquals(original, after.copyOf(original.size))
        assertTrue(String(after).endsWith(KIT_BLOCK))

        FirstRunSetup(context).ensureKitFunction()
        assertArrayEquals(after, bashrc.readBytes(), "appended twice")
    }

    @Test
    fun `no bashrc is left alone`() {
        FirstRunSetup(context).ensureKitFunction()
        assertFalse(bashrc.exists())
    }
}
