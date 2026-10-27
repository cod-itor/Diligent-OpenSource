package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Diligent: a pack that runs on another one is refused until that one is
 * installed, and is queued behind it when both are picked.
 *
 * Kotlin is the case: its compiler is JVM jars started by `java`. Installed
 * without Java it would unpack cleanly and then fail on the first `kotlinc`,
 * with a "command not found" for a command the user never typed.
 */
class ToolchainRequirementTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packManager: AssetPackManager
    private val events = mutableListOf<Pair<Int, ToolchainFailure?>>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        packManager = mockk(relaxed = true)
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        val packageManager = mockk<PackageManager>(relaxed = true)
        val source = mockk<InstallSourceInfo>()
        // Play, so a request that gets past the check reaches assetPackManager.fetch.
        every { source.installingPackageName } returns "com.android.vending"
        every { packageManager.getInstallSourceInfo(any()) } returns source

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.vscodroid"
        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun manager() = ToolchainManager(context).apply {
        onStateChange = { _, status, _, why -> events.add(status to why) }
    }

    private fun recordInstalled(vararg names: String) {
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(
            names.joinToString(",", "[", "]") { """{"name":"$it","displayName":"$it"}""" },
        )
    }

    @Test
    fun `Kotlin names Java as what it runs on`() {
        assertEquals(listOf("toolchain_java"), ToolchainRegistry.find("toolchain_kotlin")!!.requires)
        assertEquals(
            "toolchain_java",
            ToolchainRegistry.missingRequirement("kotlin", emptyList())?.packName,
        )
        assertNull(ToolchainRegistry.missingRequirement("kotlin", listOf("java")))
        assertNull(ToolchainRegistry.missingRequirement("ruby", emptyList()))
    }

    @Test
    fun `Kotlin without Java is refused before anything is fetched`() {
        manager().install("toolchain_kotlin")

        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.NEEDS_JAVA), events)
        verify(exactly = 0) { packManager.fetch(any()) }
    }

    @Test
    fun `Kotlin with Java installed goes ahead`() {
        recordInstalled("java")
        manager().install("toolchain_kotlin")

        assertEquals(emptyList<Pair<Int, ToolchainFailure?>>(), events)
        verify(exactly = 1) { packManager.fetch(listOf("toolchain_kotlin")) }
    }

    @Test
    fun `Rust without C and C++ is refused, naming that pack`() {
        assertEquals("toolchain_cpp", ToolchainRegistry.missingRequirement("rust", listOf("java"))?.packName)
        manager().install("toolchain_rust")

        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.NEEDS_CPP), events)
        verify(exactly = 0) { packManager.fetch(any()) }
    }

    @Test
    fun `a requirement picked with its dependant is queued first`() {
        assertEquals(
            listOf("toolchain_ruby", "toolchain_java", "toolchain_kotlin", "toolchain_php"),
            ToolchainRegistry.inInstallOrder(
                listOf("toolchain_ruby", "toolchain_kotlin", "toolchain_php", "toolchain_java"),
            ),
        )
        // Nothing to reorder: the order given is kept.
        assertEquals(
            listOf("toolchain_java", "toolchain_ruby"),
            ToolchainRegistry.inInstallOrder(listOf("toolchain_java", "toolchain_ruby")),
        )
        // A requirement that was not picked is not added; install() refuses instead.
        assertEquals(listOf("toolchain_kotlin"), ToolchainRegistry.inInstallOrder(listOf("toolchain_kotlin")))
    }
}
