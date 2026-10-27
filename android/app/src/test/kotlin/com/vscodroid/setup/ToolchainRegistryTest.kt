package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Tests for [ToolchainRegistry]: the catalog and its lookups.
 */
class ToolchainRegistryTest {

    // ── Catalog ──────────────────────────────────────────────────────────

    @Nested
    inner class CatalogTest {

        @Test
        fun `has exactly 17 toolchains`() {
            assertEquals(17, ToolchainRegistry.available.size)
        }

        @Test
        fun `all toolchains have valid fields`() {
            for (tc in ToolchainRegistry.available) {
                // Not assertNotNull: these three are non-nullable String, so an
                // assertion that they are not null cannot fail and never could.
                // It read as validation while checking nothing -- with every
                // displayName set to "" the whole suite stayed green, measured.
                // Blank is the failure that can actually reach a user, as an
                // unnamed card in the toolchain picker.
                assertTrue(tc.packName.isNotBlank(), "packName is blank")
                assertTrue(tc.displayName.isNotBlank(), "displayName is blank: ${tc.packName}")
                assertTrue(tc.shortLabel.isNotBlank(), "shortLabel is blank: ${tc.packName}")
                // The description is a resource id now, so blankness is not the
                // shape the failure takes any more: an id that resolves to an
                // empty string is a strings.xml defect, and one that resolves to
                // nothing at all does not compile. What is left to catch here is
                // the id never being set, which leaves the card's second line
                // empty exactly as a blank string did.
                assertNotEquals(0, tc.descriptionRes, "descriptionRes unset: ${tc.packName}")
                assert(tc.estimatedSize > 0) { "estimatedSize must be positive: ${tc.packName}" }
                assert(tc.packName.startsWith("toolchain_")) { "packName must start with 'toolchain_': ${tc.packName}" }
            }
        }

        @Test
        fun `a download is smaller than what it unpacks to`() {
            // The two figures are hand-written and both go stale when a payload
            // is rebuilt, so this cannot check either against reality. What it
            // can check is the one relation that holds by construction: a
            // compressed archive is smaller than its unpacked tree. That is
            // enough to catch the mistake worth catching, which is the two being
            // swapped or one being copied into the other, because the picker
            // shows them side by side and a swap reads as plausible.
            //
            // The single figure was the unpacked size and the picker presented
            // it as the download, so every toolchain was advertised at roughly
            // three times what it costs to fetch.
            for (tc in ToolchainRegistry.available) {
                assert(tc.downloadSize > 0) { "downloadSize must be positive: ${tc.packName}" }
                assert(tc.downloadSize < tc.estimatedSize) {
                    "downloadSize (${tc.downloadSize}) must be under estimatedSize " +
                        "(${tc.estimatedSize}); the ZIP cannot exceed what it unpacks to: ${tc.packName}"
                }
            }
        }

        /**
         * The recorded unpacked size is a floor, measured against the tree the
         * release is built from, not an estimate.
         *
         * An understatement is the direction that costs someone something: both
         * install pre-flights shrink toward the 50 MB buffer they are built on,
         * and both surfaces that quote it, the native card and the JSON
         * `getAvailableToolchains` hands the web UI, tell the user a smaller
         * number than the install writes. It went wrong exactly that
         * way once already: `download-java.sh` stopped deleting OpenJDK's
         * `legal/` and began dereferencing symlinks on copy, and the constant
         * every gate reads stayed at 146,000,000 for a tree that had grown to
         * 154.8 MB of file bytes.
         *
         * ⚠️ This can only run where the packs have been built. `download-*.sh`
         * populates them and they are not part of a source checkout, so a runner
         * that has not built them skips rather than passing over nothing: the
         * assumption below is what says which of the two happened. Read a green
         * here as evidence only when the count it reports is not zero.
         */
        @Test
        fun `no recorded size is under the tree its pack ships`() {
            var measured = 0
            for (tc in ToolchainRegistry.available) {
                // Unit tests run with the module as the working directory, so the
                // pack modules are one level up beside it.
                val usr = File("../${tc.packName}/src/main/assets/usr")
                if (!usr.isDirectory) continue
                measured++
                // Rounded up to 4 KiB blocks, because that is what the constant
                // records: its KDoc says to measure with `du -sk`. A plain sum of
                // file lengths is a smaller and different number, by 5.7 MB over
                // Ruby's 2,279 files, and blocks are what the device spends
                // anyway. Measured: this reproduces `du -sk` exactly for both
                // packs that ship.
                val onDisk = usr.walkTopDown().filter { it.isFile }
                    .sumOf { (it.length() + 4095) / 4096 * 4096 }
                assertTrue(
                    tc.estimatedSize >= onDisk,
                    "${tc.packName} records ${tc.estimatedSize} bytes for a tree of $onDisk; " +
                        "every space gate and the card the user reads are built on that figure, " +
                        "and understating it is the direction that admits a device it should refuse",
                )
            }
            assumeTrue(
                measured > 0,
                "no toolchain pack has been built in this checkout, so nothing was measured; " +
                    "run scripts/download-java.sh, scripts/download-ruby.sh and " +
                    "scripts/download-cpp.sh to reach this",
            )
        }

        /**
         * The catalog and the bundle have to name the same packs.
         *
         * The ZIP direction is gated elsewhere: `release.yml` greps this file for
         * the ZIP names, fails on an empty match, and refuses a release where a
         * named ZIP was not packaged or is missing from the digest manifest. What
         * nothing compared before this test is the catalog against `assetPacks`
         * in the app's build script, and that list is what decides which asset
         * packs the AAB carries.
         *
         * Adding a toolchain to the catalog and forgetting that line attaches the
         * ZIP to the release so sideloads install it, and leaves every Play
         * install calling `assetPackManager.fetch` for a pack the bundle does not
         * contain: a download that fails with PACK_UNAVAILABLE, and nothing
         * anywhere saying why.
         *
         * `settings.gradle.kts` is checked as the second half of the same claim
         * rather than as a separate one: an `assetPacks` entry naming a project
         * that was never `include`d is a Gradle project path resolving to
         * nothing, and whether that fails the build or is quietly dropped is not
         * something this suite can find out. Comparing the text is cheap and does
         * not depend on the answer.
         *
         * The build script is declared as an input to this task, so an edit to it
         * re-runs this rather than answering UP-TO-DATE. ⚠️ `settings.gradle.kts`
         * is not declared, so an edit to that file alone can leave this task up
         * to date over the previous run's results. What that leaves uncovered is
         * narrower than it sounds: the way these lists come apart is a toolchain
         * added to the catalog, and the catalog is compiled into the classpath
         * this task already depends on.
         */
        @Test
        fun `every toolchain offered is an asset pack the bundle carries`() {
            // Unit tests run with the module as the working directory.
            val buildScript = File("build.gradle.kts")
            assertTrue(buildScript.isFile, "app/build.gradle.kts was not where tests run")
            val list = Regex("""(?m)^\s*assetPacks\s*\+=\s*listOf\(([^)]*)\)""")
                .find(buildScript.readText())?.groupValues?.get(1)
            assertNotNull(
                list,
                "build.gradle.kts no longer names the asset packs it packages, so nothing " +
                    "here can tell whether the catalog matches them",
            )
            val packed = Regex(""""\s*:(toolchain_\w+)\s*"""").findAll(list!!)
                .map { it.groupValues[1] }.toSet()
            val offered = ToolchainRegistry.available.map { it.packName }.toSet()
            assertEquals(
                offered, packed,
                "the toolchains this build offers and the asset packs it bundles are not the " +
                    "same set. A pack in neither list is dead weight; a toolchain offered " +
                    "without its pack fetches something the AAB does not carry, on every " +
                    "Play install",
            )
            // One level up from the module, beside the pack modules themselves.
            val settings = File("../settings.gradle.kts")
            assertTrue(settings.isFile, "android/settings.gradle.kts was not where tests run")
            val included = Regex("""(?m)^\s*include\("\s*:(toolchain_\w+)\s*"\)""")
                .findAll(settings.readText()).map { it.groupValues[1] }.toSet()
            assertEquals(
                offered, included,
                "the toolchains this build offers and the pack modules settings.gradle.kts " +
                    "includes are not the same set; an asset pack that is not a project is " +
                    "not in the bundle whatever build.gradle.kts lists",
            )
        }

        @Test
        fun `all toolchains have HTTPS download URLs`() {
            for (tc in ToolchainRegistry.available) {
                assertNotNull(tc.downloadUrl, "downloadUrl must not be null: ${tc.packName}")
                assertTrue(tc.downloadUrl!!.startsWith("https://"), "downloadUrl must be HTTPS: ${tc.packName}")
                assertTrue(tc.downloadUrl!!.endsWith(".zip"), "downloadUrl must end with .zip: ${tc.packName}")
            }
        }

        @Test
        fun `offers nothing it has retired`() {
            // The catalog is what the picker and the manage screen render, so an
            // entry here is an offer. Go left it because it could not compile,
            // and putting it back would need the sweep in ToolchainManager to go
            // too, or a fresh install would download 179 MB that the next launch
            // deletes.
            val offered = ToolchainRegistry.available.map { it.packName }.toSet()
            assertTrue(
                "toolchain_go" !in offered,
                "Go is offered again; ToolchainManager still sweeps it on launch, " +
                    "so an install would be undone by the next start",
            )
        }

        @Test
        fun `contains Ruby toolchain`() {
            val ruby = ToolchainRegistry.available.find { it.shortLabel == "Ruby" }
            assertNotNull(ruby)
            assertEquals("toolchain_ruby", ruby!!.packName)
        }

        @Test
        fun `contains Java toolchain`() {
            val java = ToolchainRegistry.available.find { it.shortLabel == "Java 21" }
            assertNotNull(java)
            assertEquals("toolchain_java", java!!.packName)
        }

        @Test
        fun `contains C and C++ toolchain`() {
            val cpp = ToolchainRegistry.available.find { it.shortLabel == "C/C++" }
            assertNotNull(cpp)
            assertEquals("toolchain_cpp", cpp!!.packName)
            assertEquals("C/C++", cpp.displayName)
            assertEquals(
                "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_cpp.zip",
                cpp.downloadUrl,
            )
        }

        /**
         * Diligent's own packs are fetched from Diligent's releases. VSCodroid's
         * releases carry only the three it builds, so a URL pointing there for a
         * pack it never built fails every sideloaded install with a 404.
         */
        @Test
        fun `contains PHP toolchain, fetched from Diligent's releases`() {
            val php = ToolchainRegistry.available.find { it.shortLabel == "PHP" }
            assertNotNull(php)
            assertEquals("toolchain_php", php!!.packName)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_php.zip",
                php.downloadUrl,
            )
        }

        @Test
        fun `contains Lua toolchain, fetched from Diligent's releases`() {
            val lua = ToolchainRegistry.available.find { it.shortLabel == "Lua 5.4" }
            assertNotNull(lua)
            assertEquals("toolchain_lua", lua!!.packName)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_lua.zip",
                lua.downloadUrl,
            )
        }

        @Test
        fun `contains Perl toolchain, fetched from Diligent's releases`() {
            val perl = ToolchainRegistry.available.find { it.shortLabel == "Perl" }
            assertNotNull(perl)
            assertEquals("toolchain_perl", perl!!.packName)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_perl.zip",
                perl.downloadUrl,
            )
        }

        @Test
        fun `contains Kotlin toolchain, which runs on Java`() {
            val kotlin = ToolchainRegistry.available.find { it.shortLabel == "Kotlin" }
            assertNotNull(kotlin)
            assertEquals("toolchain_kotlin", kotlin!!.packName)
            assertEquals(listOf("toolchain_java"), kotlin.requires)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_kotlin.zip",
                kotlin.downloadUrl,
            )
        }

        @Test
        fun `contains Rust toolchain, which links with C and C++`() {
            val rust = ToolchainRegistry.available.find { it.shortLabel == "Rust" }
            assertNotNull(rust)
            assertEquals("toolchain_rust", rust!!.packName)
            assertEquals(listOf("toolchain_cpp"), rust.requires)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_rust.zip",
                rust.downloadUrl,
            )
        }

        @Test
        fun `contains Tcl toolchain`() {
            val tcl = ToolchainRegistry.available.find { it.shortLabel == "Tcl" }
            assertNotNull(tcl)
            assertEquals("toolchain_tcl", tcl!!.packName)
            assertEquals(emptyList<String>(), tcl.requires)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_tcl.zip",
                tcl.downloadUrl,
            )
        }

        @Test
        fun `contains Groovy toolchain`() {
            val groovy = ToolchainRegistry.available.find { it.shortLabel == "Groovy" }
            assertNotNull(groovy)
            assertEquals("toolchain_groovy", groovy!!.packName)
            assertEquals(listOf("toolchain_java"), groovy.requires)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_groovy.zip",
                groovy.downloadUrl,
            )
        }

        @Test
        fun `contains Scala toolchain`() {
            val scala = ToolchainRegistry.available.find { it.shortLabel == "Scala" }
            assertNotNull(scala)
            assertEquals("toolchain_scala", scala!!.packName)
            assertEquals(listOf("toolchain_java"), scala.requires)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_scala.zip",
                scala.downloadUrl,
            )
        }

        @Test
        fun `contains Erlang toolchain`() {
            val erlang = ToolchainRegistry.available.find { it.shortLabel == "Erlang" }
            assertNotNull(erlang)
            assertEquals("toolchain_erlang", erlang!!.packName)
            assertEquals(emptyList<String>(), erlang.requires)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_erlang.zip",
                erlang.downloadUrl,
            )
        }

        @Test
        fun `contains Elixir toolchain`() {
            val elixir = ToolchainRegistry.available.find { it.shortLabel == "Elixir" }
            assertNotNull(elixir)
            assertEquals("toolchain_elixir", elixir!!.packName)
            assertEquals(listOf("toolchain_erlang"), elixir.requires)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_elixir.zip",
                elixir.downloadUrl,
            )
        }

        @Test
        fun `contains SBCL toolchain`() {
            val sbcl = ToolchainRegistry.available.find { it.shortLabel == "SBCL" }
            assertNotNull(sbcl)
            assertEquals("toolchain_sbcl", sbcl!!.packName)
            assertEquals(emptyList<String>(), sbcl.requires)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_sbcl.zip",
                sbcl.downloadUrl,
            )
        }

        @Test
        fun `contains Deno toolchain`() {
            val deno = ToolchainRegistry.available.find { it.shortLabel == "Deno" }
            assertNotNull(deno)
            assertEquals("toolchain_deno", deno!!.packName)
            assertEquals(emptyList<String>(), deno.requires)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_deno.zip",
                deno.downloadUrl,
            )
        }

        @Test
        fun `contains Gleam toolchain`() {
            val gleam = ToolchainRegistry.available.find { it.shortLabel == "Gleam" }
            assertNotNull(gleam)
            assertEquals("toolchain_gleam", gleam!!.packName)
            assertEquals(listOf("toolchain_erlang"), gleam.requires)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_gleam.zip",
                gleam.downloadUrl,
            )
        }

        @Test
        fun `contains NASM toolchain`() {
            val nasm = ToolchainRegistry.available.find { it.shortLabel == "NASM" }
            assertNotNull(nasm)
            assertEquals("toolchain_nasm", nasm!!.packName)
            assertEquals(emptyList<String>(), nasm.requires)
            assertEquals(
                "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_nasm.zip",
                nasm.downloadUrl,
            )
        }

        @Test
        fun `every requirement is a pack the registry offers`() {
            for (tc in ToolchainRegistry.available) {
                for (required in tc.requires) {
                    assertNotNull(ToolchainRegistry.find(required), "${tc.packName} requires unknown $required")
                    assertNotEquals(tc.packName, required, "${tc.packName} requires itself")
                }
            }
        }

        /**
         * The card is the one place the user is told what this toolchain needs.
         *
         * Its programs start only through the exec interceptor a terminal
         * preloads, so a terminal with `LD_PRELOAD` set to null runs none of
         * them. That is a limit a user meets as "Permission denied" from a
         * compiler that is installed and on PATH, which reads as a broken
         * install, so the sentence on the card is what stands between the two.
         * Every locale carries it, with the variable name left as it is.
         */
        @Test
        fun `the C and C++ card says it needs the exec interceptor`() {
            val res = File("src/main/res")
            assertTrue(res.isDirectory, "app/src/main/res was not where tests run")
            val files = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
                .orEmpty().map { File(it, "strings.xml") }.filter { it.isFile }
            assertTrue(files.size >= 14, "expected the default and thirteen locales, found ${files.size}")
            for (file in files) {
                val line = file.readLines().firstOrNull { "name=\"toolchain_cpp_description\"" in it }
                assertNotNull(line, "${file.parentFile.name} has no toolchain_cpp_description")
                assertTrue(
                    "LD_PRELOAD" in line!! && "null" in line && "exec" in line,
                    "${file.parentFile.name}: the card does not say the toolchain needs the " +
                        "preloaded exec and fails with LD_PRELOAD set to null: $line",
                )
            }
        }
    }

    // ── find() ───────────────────────────────────────────────────────────

    @Nested
    inner class FindTest {

        @ParameterizedTest(name = "find by full pack name: {0}")
        @ValueSource(strings = ["toolchain_ruby", "toolchain_java", "toolchain_cpp", "toolchain_php", "toolchain_lua", "toolchain_perl", "toolchain_kotlin", "toolchain_rust", "toolchain_tcl", "toolchain_groovy", "toolchain_scala", "toolchain_erlang", "toolchain_elixir", "toolchain_sbcl", "toolchain_deno", "toolchain_gleam", "toolchain_nasm"])
        fun `finds by full pack name`(packName: String) {
            val result = ToolchainRegistry.find(packName)
            assertNotNull(result, "Should find toolchain by pack name: $packName")
            assertEquals(packName, result!!.packName)
        }

        @ParameterizedTest(name = "find by short name: {0} → toolchain_{0}")
        @CsvSource("ruby,toolchain_ruby", "java,toolchain_java", "cpp,toolchain_cpp", "php,toolchain_php", "lua,toolchain_lua", "perl,toolchain_perl", "kotlin,toolchain_kotlin", "rust,toolchain_rust", "tcl,toolchain_tcl", "groovy,toolchain_groovy", "scala,toolchain_scala", "erlang,toolchain_erlang", "elixir,toolchain_elixir", "sbcl,toolchain_sbcl", "deno,toolchain_deno", "gleam,toolchain_gleam", "nasm,toolchain_nasm")
        fun `finds by short name`(shortName: String, expectedPack: String) {
            val result = ToolchainRegistry.find(shortName)
            assertNotNull(result, "Should find toolchain by short name: $shortName")
            assertEquals(expectedPack, result!!.packName)
        }

        @ParameterizedTest(name = "find returns null for unknown: {0}")
        @ValueSource(strings = ["swift", "clang", "python", "toolchain_swift", ""])
        fun `returns null for unknown toolchains`(name: String) {
            assertNull(ToolchainRegistry.find(name), "Should return null for unknown: $name")
        }
    }

    // ToolchainRegistry.formatSize was here, and with it a nested class pinning
    // its 1,000,000 divisor. Sizes are formatted by
    // com.vscodroid.util.StorageManager.formatSize now, like every other byte
    // figure the app shows, and the card's use of it is pinned by
    // ToolchainCardStateTest.SizeLine.
}
