package com.meowo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The version of SQLite that ships at runtime is written in **two** places, and they have to agree.
 *
 * One of them is `gradle/libs.versions.toml`, which pins the version every module compiles against.
 * The other is the `@RuntimeDependency` annotation on [MeowoCore], which says what TabooLib downloads
 * on first start. Nothing connects them: the annotation needs a compile-time constant, so the string
 * is written out by hand.
 *
 * That is exactly the shape that drifts. A version bumped in the catalogue and forgotten in the
 * annotation compiles cleanly, passes every test, and then downloads a **different** driver at runtime
 * than the one the code was written and tested against — and the failure shows up as a native-library
 * error on somebody's server, not as a build failure.
 *
 * So the two are compared here, in a test, on every build.
 */
class RuntimeDependencyVersionTest {

    /** Gradle runs this module's tests with `core/` as the working directory. */
    private val catalog = File("../gradle/libs.versions.toml")

    private val entryPoint = File("src/main/kotlin/com/meowo/core/MeowoCore.kt")

    @Test fun `the runtime sqlite driver is the version the catalogue pins`() {
        assertTrue(catalog.isFile, "missing version catalogue: ${catalog.absolutePath}")
        assertTrue(entryPoint.isFile, "missing entry point: ${entryPoint.absolutePath}")

        val pinned = Regex("""^\s*sqlite\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(catalog.readText())
            ?.groupValues
            ?.get(1)
            ?: error("the catalogue no longer pins a sqlite version")

        val used = Regex("""RuntimeDependency\("org\.xerial:sqlite-jdbc:([^"]+)"""")
            .find(entryPoint.readText())
            ?.groupValues
            ?.get(1)
            ?: error("MeowoCore no longer declares a runtime sqlite dependency")

        assertEquals(
            pinned, used,
            "the driver downloaded at runtime is not the one the build compiles against. " +
                "Bump both together: gradle/libs.versions.toml and the @RuntimeDependency in MeowoCore.",
        )
    }
}
