package com.vscodroid.diligent.projects

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitProgressTest {

    @Test
    fun `reads git's progress lines`() {
        assertEquals(GitProgress("Receiving objects", 45), GitProgress.of("Receiving objects:  45% (450/1000), 1.20 MiB | 600.00 KiB/s"))
        assertEquals(GitProgress("Resolving deltas", 100), GitProgress.of("Resolving deltas: 100% (3/3), done."))
        assertEquals(GitProgress("Counting objects", 7), GitProgress.of("remote: Counting objects:   7% (1/14)"))
        assertEquals(GitProgress("Updating files", 0), GitProgress.of("  Updating files:   0% (0/12)"))
    }

    @Test
    fun `other lines are not progress`() {
        assertNull(GitProgress.of("Cloning into 'shop'..."))
        assertNull(GitProgress.of("fatal: repository 'https://h/x' not found"))
        assertNull(GitProgress.of("remote: Enumerating objects: 14, done."))
        assertNull(GitProgress.of(""))
    }
}
