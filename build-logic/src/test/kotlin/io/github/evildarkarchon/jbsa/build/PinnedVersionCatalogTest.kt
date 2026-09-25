package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PinnedVersionCatalogTest {
    @TempDir lateinit var directory: Path

    /** Verifies dependency and plugin pins are resolved through version references. */
    @Test
    fun `loads centralized dependency and plugin pins`() {
        val catalog = loadCatalog()

        assertDoesNotThrow { catalog.requireDependency("org.junit.jupiter", "junit-jupiter-api", "6.1.3") }
        assertEquals("8.10.2", catalog.pluginVersion("com.diffplug.spotless"))
    }

    /** Verifies undeclared modules and versions differing from the catalog fail closed. */
    @Test
    fun `rejects dependencies outside catalog ownership`() {
        val catalog = loadCatalog()

        assertThrows(IllegalArgumentException::class.java) {
            catalog.requireDependency("org.junit.jupiter", "junit-jupiter-api", "6.0.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            catalog.requireDependency("example.invalid", "undeclared", "1.0.0")
        }
    }

    /** Writes and loads one representative controlled version catalog. */
    private fun loadCatalog(): PinnedVersionCatalog {
        val path = directory.resolve("libs.versions.toml")
        Files.writeString(
            path,
            """
            [versions]
            junit = "6.1.3"
            spotless = "8.10.2"

            [libraries]
            junit-api = { module = "org.junit.jupiter:junit-jupiter-api", version.ref = "junit" }

            [plugins]
            spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
            """.trimIndent(),
        )
        return PinnedVersionCatalog.load(path)
    }
}
