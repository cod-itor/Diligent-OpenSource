package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Diligent's bundled extensions are the app's own, exactly as VSCodroid's are.
 *
 * Every rule that turns on authorship (re-extract on every update, mark as
 * built in, sweep a superseded or retired copy, follow an update in a named
 * profile) used to ask for the `vscodroid.` prefix alone. A `diligent.*`
 * extension then read as one downloaded from a gallery: edited in place, it
 * would never reach a phone that already had it, and its previous version
 * would stay on disk beside the new one.
 */
class DiligentOwnExtensionTest {

    private val bridge = "diligent.diligent-bridge-0.1.0"
    private val oldBridge = "diligent.diligent-bridge-0.0.9"
    private val python = "ms-python.python-2026.4.0"

    @Test
    fun `both publishers are ours and nothing else is`() {
        assertTrue(isOwnExtension("vscodroid.vscodroid-saf-bridge-1.12.2"))
        assertTrue(isOwnExtension(bridge))
        assertTrue(isOwnExtension("diligent.diligent-bridge"))
        assertFalse(isOwnExtension(python))
        // The publisher alone is not enough: a gallery namespace called
        // "diligent" would publish names that do not start with ours.
        assertFalse(isOwnExtension("diligent.something-else-1.0.0"))
        assertFalse(isOwnExtension("diligentx.diligent-bridge-1.0.0"))
    }

    @Test
    fun `an own extension is extracted again even when its directory is present`() {
        val extract = bundledDirsToExtract(present = listOf(bridge, python), bundled = listOf(bridge, python))
        assertEquals(listOf(bridge), extract)
    }

    @Test
    fun `a dropped own extension is retired and a gallery one is not`() {
        val retired = retiredOwnExtensionDirs(
            present = listOf("diligent.diligent-retired-1.0.0", "someone.else-1.0.0", bridge),
            bundled = listOf(bridge),
        )
        assertEquals(listOf("diligent.diligent-retired-1.0.0"), retired)
    }

    @Test
    fun `an older version of an own extension is not retired, it is superseded`() {
        val retired = retiredOwnExtensionDirs(present = listOf(oldBridge, bridge), bundled = listOf(bridge))
        assertTrue(retired.isEmpty(), "retired $retired")
    }
}
