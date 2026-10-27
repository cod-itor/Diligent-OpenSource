package com.vscodroid.setup

import androidx.annotation.StringRes
import com.vscodroid.R

/**
 * Catalog of available on-demand toolchains.
 *
 * Each entry maps to a Play Asset Delivery pack (toolchain_<name>) and a
 * download script (scripts/download-<name>.sh) that populates it at build time.
 */
object ToolchainRegistry {

    data class ToolchainInfo(
        val packName: String,
        val displayName: String,
        /** Short label for the toolchain card (e.g. "Ruby", "Java 21"). */
        val shortLabel: String,
        /**
         * The sentence under the name on the toolchain card, as a resource id.
         *
         * A resource rather than a string because this is the only field here a
         * user reads as language: [displayName] and [shortLabel] are the
         * products' own names and stay the same in every locale, while this is a
         * sentence that has to change with one. Holding it as a `String` made it
         * unreachable to a translator, since nothing between here and the card
         * goes near a resource.
         */
        @StringRes val descriptionRes: Int,
        /**
         * Approximate size on disk once unpacked, in bytes.
         *
         * This is the free-space figure: `downloadViaHttp` gates on about twice
         * it plus a buffer, because the unpacked tree is what has to fit and a
         * fresh HTTP install holds two copies of it at once. It is not what
         * the user is choosing about on mobile data, which is [downloadSize],
         * and telling them this number alone overstated every toolchain by
         * roughly three times.
         *
         * **Understating it is the direction that costs a user something.**
         * Both install pre-flights key off this one number (`packInstallBytes`
         * for Play, `toolchainInstallBytes` for HTTP), and so does every surface
         * that quotes the figure to the user: the native card
         * ([ToolchainCardState]) and the JSON `getAvailableToolchains` hands the
         * web UI. A count is not given here because a count is what rots; the
         * pre-flights are the readers a wrong figure costs someone something. A
         * figure below the real tree eats the 50 MB buffer the reservations are
         * built on, which admits a device the gate exists to refuse and leaves it
         * out of room partway through the copy. The first-run pre-flight is
         * deliberately not among them: it reads what is on disk rather than what
         * a toolchain claims to need, so nothing there consults this. Measure
         * rather than estimate:
         * `du -sk android/toolchain_<name>/src/main/assets/usr`, which is the
         * tree `package-toolchains.sh` archives, and round up.
         */
        val estimatedSize: Long,
        /**
         * Approximate size of the ZIP fetched over HTTP, in bytes.
         *
         * Measured from the release assets rather than computed: a payload is
         * rebuilt whenever its source moves, so both figures here are
         * hand-written and go stale the same way. `downloadFile` deliberately
         * prefers the length the server actually sent and falls back to a
         * constant only when there is none, so nothing depends on this being
         * exact; it exists so the picker can say what a download will cost.
         */
        val downloadSize: Long,
        /** Fallback URL for sideloaded installs (no Play Store). Null = Play-only. */
        val downloadUrl: String? = null,
        /**
         * Diligent: the packs this one runs on, by pack name, which have to be
         * installed before it. Kotlin's compiler is JVM jars started by `java`,
         * so it names `toolchain_java`.
         *
         * [ToolchainManager.install] refuses a pack whose requirement is not
         * installed, with a message naming what to install first, and
         * [inInstallOrder] puts a requirement ahead of its dependant when both
         * are picked together. Removing the requirement later is not prevented:
         * the dependant stays installed and its commands fail until it is back.
         */
        val requires: List<String> = emptyList(),
    )

    /**
     * What the picker and the manage screen offer.
     *
     * Go was here and is not any more. Its card carried the honest sentence,
     * that it runs but cannot compile, and an honest label on 179 MB that does
     * not do the thing people install a compiler for is still 179 MB that does
     * not do it. Android refuses to execute a file under the app's data
     * directory, and `go build` and `go run` fork the compiler, assembler and
     * linker themselves, so those forks are refused however the `go` command is
     * reached. Measured in the app's own SELinux domain: a plain shell script
     * placed there and marked executable is refused too, and `-toolexec`, the
     * one idea that looked like a way round it, never reaches the problem.
     *
     * Diligent: Rust is offered where Go is not because its spawns go through
     * libc. cargo and librustc_driver import `fork` and `execvp` (no
     * `posix_spawn`), which the terminal's exec interceptor wraps and starts
     * through /system/bin/linker64; Go issues the execve system call itself,
     * which nothing in the process can wrap. rustc finds its sysroot with
     * `dladdr` on librustc_driver rather than /proc/self/exe, so starting it
     * through the linker does not lose it. It links through `cc`, so it requires
     * the C/C++ pack, whose libLLVM.so it also loads. Clippy is left out:
     * cargo-clippy finds clippy-driver beside its own current_exe, which under
     * the linker is /system/bin.
     *
     * Removing an entry here is not the whole job. See
     * [ToolchainManager.removeRetiredToolchainsSync]: installs that already
     * have it lose their Remove button along with the entry, so they are swept.
     */
    val available = listOf(
        ToolchainInfo(
            packName = "toolchain_ruby",
            displayName = "Ruby",
            shortLabel = "Ruby",
            descriptionRes = R.string.toolchain_ruby_description,
            // 38,736 KiB measured with `du -sk` over the pack's `usr/` tree,
            // which is 39.7 MB in 4 KiB blocks. 2,597 files, so the block
            // rounding is most of the gap between that and the file contents.
            // Termux moved ruby from 3.4.1 to 4.0.6 and the tree grew past the
            // 36,000,000 recorded then, which package-toolchains.sh refuses
            // rather than shipping a card that admits a device to an install it
            // cannot finish. It grew again by the 1.9 MB of C headers a native
            // gem is built against (`usr/include/ruby-4.0.0`), which left 39.0
            // MB 0.7 MB short. 41,000,000 leaves 1.3 MB for a point release.
            estimatedSize = 41_000_000,
            downloadSize = 11_000_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_ruby.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_java",
            displayName = "Java 21",
            shortLabel = "Java 21",
            descriptionRes = R.string.toolchain_java_description,
            // 175,268 KiB measured with `du -sk` over the pack's `usr/` tree,
            // which is 179.5 MB in 4 KiB blocks, in 334 files. The pack carried
            // OpenJDK 17 until 21 replaced it (the Language Support for Java
            // extension needs 21 to run its server), and 21 is 24 MB larger.
            // `download-java.sh` copies with `-RL`, which dereferences the 200
            // odd symlinks of `legal/`, and `package-toolchains.sh` refuses a
            // tree above this figure rather than shipping a card that admits a
            // device to an install it cannot finish. 186,000,000 leaves 6.5 MB
            // for a point release of openjdk-21, which Termux has made before.
            estimatedSize = 186_000_000,
            downloadSize = 64_000_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_java.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_cpp",
            displayName = "C/C++",
            shortLabel = "C/C++",
            descriptionRes = R.string.toolchain_cpp_description,
            // 248,060 KiB measured with `du -sk` over the pack's `usr/` tree,
            // which is 254,013,440 bytes in 4 KiB blocks and 244.8 MB of file
            // contents, over 3,790 files. libLLVM.so and libclang-cpp.so are
            // 196.6 MB of it, the headers 30.9 MB and the clang resource
            // directory 16 MB. 260,000,000 leaves 6 MB for the next point
            // release of clang: package-toolchains.sh refuses to zip a tree
            // above this, and a release tag is where that would be found.
            //
            // This is also the largest install pre-flight of the three. A first
            // install over HTTP holds the staged copy and the one in `usr/` at
            // once, so it asks for about twice this figure plus the buffer.
            // The ZIP is 73,702,041 bytes.
            estimatedSize = 260_000_000,
            downloadSize = 74_000_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_cpp.zip",
        ),
        // Diligent: the packs below are built by this repository's own release,
        // not VSCodroid's, so their ZIPs are fetched from Diligent's releases.
        ToolchainInfo(
            packName = "toolchain_php",
            displayName = "PHP",
            shortLabel = "PHP",
            descriptionRes = R.string.toolchain_php_description,
            // 41,836 KiB measured with `du -sk` over the pack's `usr/` tree
            // (42,840,064 bytes in 4 KiB blocks, 27 files) after running
            // scripts/download-php.sh against PHP 8.5.12. php itself is 24 MB of
            // it and libcapstone 8.8 MB. 45,000,000 leaves 2.1 MB for a point
            // release. The ZIP measured 9,409,284 bytes.
            estimatedSize = 45_000_000,
            downloadSize = 10_000_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_php.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_lua",
            displayName = "Lua 5.4",
            shortLabel = "Lua 5.4",
            descriptionRes = R.string.toolchain_lua_description,
            // 400 KiB measured with `du -sk` over the pack's `usr/` tree
            // (409,600 bytes in 4 KiB blocks, 4 files) after running
            // scripts/download-lua.sh against lua54 5.4.9: two statically linked
            // binaries and one notice. 1,000,000 is more than double it, which
            // costs nothing at this size. The ZIP measured 192,808 bytes.
            estimatedSize = 1_000_000,
            downloadSize = 200_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_lua.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_perl",
            displayName = "Perl",
            shortLabel = "Perl",
            descriptionRes = R.string.toolchain_perl_description,
            // 63,664 KiB measured with `du -sk` over the pack's `usr/` tree
            // (65,191,936 bytes in 4 KiB blocks, 1,908 files) after running
            // scripts/download-perl.sh against perl 5.44.0. The library tree is
            // most of it; libperl.so is 3.8 MB and ships twice, once in usr/lib
            // and once in CORE/ for linking an XS module. 68,000,000 leaves 2.8
            // MB for a point release. The ZIP measured 17,003,407 bytes.
            estimatedSize = 68_000_000,
            downloadSize = 18_000_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_perl.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_kotlin",
            displayName = "Kotlin",
            shortLabel = "Kotlin",
            descriptionRes = R.string.toolchain_kotlin_description,
            // 95,380 KiB measured with `du -sk` over the pack's `usr/` tree
            // (97,669,120 bytes in 4 KiB blocks, 121 files) after running
            // scripts/download-kotlin.sh against kotlin 2.4.21. All of it is the
            // compiler's jars and their licences; there is no native code.
            // 101,000,000 leaves 3.3 MB for a point release. Jars are already
            // compressed, so the ZIP is nearly as large: 89,645,091 bytes.
            //
            // The Java pack is not counted here. It is a separate install with
            // its own pre-flight, and `requires` makes it come first.
            estimatedSize = 101_000_000,
            downloadSize = 90_000_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_kotlin.zip",
            requires = listOf("toolchain_java"),
        ),
        ToolchainInfo(
            packName = "toolchain_rust",
            displayName = "Rust",
            shortLabel = "Rust",
            descriptionRes = R.string.toolchain_rust_description,
            // 283,376 KiB measured with `du -sk` over the pack's `usr/` tree
            // (290,177,024 bytes in 4 KiB blocks, 102 files) after running
            // scripts/download-rust.sh. Only the aarch64-linux-android target
            // library tree is kept. librustc_driver is most of it. 296,000,000
            // leaves 5.8 MB for a point release. The ZIP measured 101,307,210
            // bytes.
            //
            // libLLVM.so is not counted: librustc_driver loads the one the C/C++
            // pack ships (symbol version LLVM_21.1), and `requires` makes that
            // pack come first. It is also the linker driver rustc calls as `cc`.
            estimatedSize = 296_000_000,
            downloadSize = 102_000_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_rust.zip",
            requires = listOf("toolchain_cpp"),
        ),
        ToolchainInfo(
            packName = "toolchain_tcl",
            displayName = "Tcl",
            shortLabel = "Tcl",
            descriptionRes = R.string.toolchain_tcl_description,
            // 5,004 KiB measured with `du -sk` over the pack's `usr/` tree
            // (5,124,096 bytes in 4 KiB blocks, 256 files) after running
            // scripts/download-tcl.sh against tcl 8.6.16, with Itcl, Thread and
            // TDBC. 6,000,000 leaves 0.9 MB for a point release. The ZIP
            // measured 1,600,668 bytes.
            estimatedSize = 6_000_000,
            downloadSize = 2_000_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_tcl.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_groovy",
            displayName = "Groovy",
            shortLabel = "Groovy",
            descriptionRes = R.string.toolchain_groovy_description,
            // 44,148 KiB measured with `du -sk` over the pack's `usr/` tree
            // (45,207,552 bytes in 4 KiB blocks, 157 files) after running
            // scripts/download-groovy.sh against groovy 6.0.0. Jars and their
            // licences only, like Kotlin; the grooid jars and groovyConsole are
            // left out. 48,000,000 leaves 2.8 MB for a point release. The ZIP
            // measured 40,528,558 bytes.
            estimatedSize = 48_000_000,
            downloadSize = 41_000_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_groovy.zip",
            requires = listOf("toolchain_java"),
        ),
        ToolchainInfo(
            packName = "toolchain_scala",
            displayName = "Scala",
            shortLabel = "Scala",
            descriptionRes = R.string.toolchain_scala_description,
            // 184,220 KiB measured with `du -sk` over the pack's `usr/` tree
            // (188,641,280 bytes in 4 KiB blocks, 155 files) after running
            // scripts/download-scala.sh against scala 3.10.0. scala-cli.jar, the
            // launcher behind `scala`, is 126 MB of it; the compiler is in the
            // bundled maven2/ repository. 193,000,000 leaves 4.4 MB for a point
            // release. Jars are already compressed, so the ZIP is nearly as
            // large: 176,795,380 bytes.
            estimatedSize = 193_000_000,
            downloadSize = 177_000_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_scala.zip",
            requires = listOf("toolchain_java"),
        ),
        ToolchainInfo(
            packName = "toolchain_erlang",
            displayName = "Erlang/OTP",
            shortLabel = "Erlang",
            descriptionRes = R.string.toolchain_erlang_description,
            // 56,116 KiB measured with `du -sk` over the pack's `usr/` tree
            // (57,462,784 bytes in 4 KiB blocks, 1,476 files) after running
            // scripts/download-erlang.sh against erlang 29.1.2. Each
            // application's src/ (52 MB), wx and ODBC are left out. 60,000,000
            // leaves 2.5 MB for a point release. The ZIP measured 40,004,317
            // bytes.
            //
            // erl finds the release root from its own path and every process
            // the runtime starts is an execv or execve through libc, which the
            // terminal's exec interceptor wraps.
            estimatedSize = 60_000_000,
            downloadSize = 41_000_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_erlang.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_elixir",
            displayName = "Elixir",
            shortLabel = "Elixir",
            descriptionRes = R.string.toolchain_elixir_description,
            // 16,668 KiB measured with `du -sk` over the pack's `usr/` tree
            // (17,068,032 bytes in 4 KiB blocks, 711 files) after running
            // scripts/download-elixir.sh against elixir 1.20.4. BEAM bytecode and
            // four sh scripts; there is no native code. 18,000,000 leaves 1 MB
            // for a point release. The ZIP measured 8,658,404 bytes.
            //
            // The Erlang pack is not counted here: `requires` makes it come first.
            estimatedSize = 18_000_000,
            downloadSize = 9_000_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_elixir.zip",
            requires = listOf("toolchain_erlang"),
        ),
        ToolchainInfo(
            packName = "toolchain_sbcl",
            displayName = "Common Lisp (SBCL)",
            shortLabel = "SBCL",
            descriptionRes = R.string.toolchain_sbcl_description,
            // 43,252 KiB measured with `du -sk` over the pack's `usr/` tree
            // (44,290,048 bytes in 4 KiB blocks, 47 files) after running
            // scripts/download-sbcl.sh against sbcl 2.6.9. sbcl.core, the Lisp
            // image, is 34 MB of it. 46,000,000 leaves 1.7 MB for a point
            // release. The ZIP measured 13,409,578 bytes.
            estimatedSize = 46_000_000,
            downloadSize = 14_000_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_sbcl.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_deno",
            displayName = "Deno",
            shortLabel = "Deno",
            descriptionRes = R.string.toolchain_deno_description,
            // 96,152 KiB measured with `du -sk` over the pack's `usr/` tree
            // (98,459,648 bytes in 4 KiB blocks, 2 files) after running
            // scripts/download-deno.sh against deno 2.9.8. It is one binary.
            // 102,000,000 leaves 3.5 MB for a point release. The ZIP measured
            // 40,936,906 bytes.
            //
            // Its subprocesses are fork and execvp through libc, which the exec
            // interceptor wraps; `deno compile` reads /proc/self/exe, which is
            // the linker under a shell function, and is not covered.
            estimatedSize = 102_000_000,
            downloadSize = 41_000_000,
            downloadUrl = "https://github.com/cod-itor/Diligent-OpenSource/releases/latest/download/toolchain_deno.zip",
        ),
    )

    /** Look up toolchain info by pack name (e.g. "toolchain_ruby") or short name (e.g. "ruby"). */
    fun find(nameOrPack: String): ToolchainInfo? =
        available.find { it.packName == nameOrPack || it.packName == "toolchain_$nameOrPack" }

    /**
     * Diligent: the first pack [nameOrPack] requires that is not in [installed]
     * (short names, as `ToolchainManager.getInstalledToolchains` returns them),
     * or null when nothing is missing.
     */
    fun missingRequirement(nameOrPack: String, installed: Collection<String>): ToolchainInfo? {
        val have = installed.mapNotNull { find(it)?.packName }.toSet()
        return find(nameOrPack)?.requires?.firstOrNull { it !in have }?.let { find(it) }
    }

    /**
     * Diligent: [packs] in an order that installs each requirement before the
     * pack that needs it, when both are in the list, and otherwise as given.
     * The first-run queue installs one pack after another in list order, so a
     * Kotlin picked before Java would otherwise reach [ToolchainManager.install]
     * first and be refused.
     */
    fun inInstallOrder(packs: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        // Guards a cycle, which no entry has: without it one would recurse for ever.
        val visiting = mutableSetOf<String>()
        fun add(pack: String) {
            if (pack in out || !visiting.add(pack)) return
            for (required in find(pack)?.requires.orEmpty()) {
                packs.firstOrNull { find(it)?.packName == required }?.let(::add)
            }
            out.add(pack)
        }
        packs.forEach(::add)
        return out.toList()
    }

    // There was a `formatSize` here, dividing by 1,000,000 and writing "MB"
    // while every other byte figure the app showed divided by 1,048,576 and
    // wrote the same word, so "MB" meant one thing on the toolchain cards and
    // another, 4.9% larger, everywhere the user could compare it against. The
    // divisor was right and having its own copy of it was not: the cards go
    // through [com.vscodroid.util.StorageManager.formatSize] now, which is
    // decimal for every screen at once and says why.
}
