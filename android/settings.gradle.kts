pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VSCodroid"

// Diligent: diligent-link's shared library (Kotlin Multiplatform), a Git
// submodule of the Diligent-Apps repository. The composite build substitutes
// `com.ditarector.diligent:sync-core` with the jvm target built from source.
includeBuild("diligent-sync-core/sync-core")
include(":app")

// On-demand toolchain asset packs (Play Asset Delivery)
include(":toolchain_ruby")
include(":toolchain_java")
include(":toolchain_cpp")
include(":toolchain_php")
include(":toolchain_lua")
include(":toolchain_perl")
include(":toolchain_kotlin")
include(":toolchain_rust")
include(":toolchain_tcl")
include(":toolchain_groovy")
include(":toolchain_scala")
include(":toolchain_erlang")
