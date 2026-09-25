package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ArchiveTimestampNormalizerTest {
    @TempDir lateinit var temporaryDirectory: Path

    /** Verifies that every archive entry receives the qualified fixed reproducibility epoch. */
    @Test
    fun `normalizes every archive entry to the fixed timestamp`() {
        val archive = temporaryDirectory.resolve("sample.jar")
        JarOutputStream(Files.newOutputStream(archive)).use { output ->
            listOf("META-INF/", "META-INF/MANIFEST.MF", "example/Type.class").forEachIndexed { index, name ->
                val entry = JarEntry(name)
                entry.time = Instant.parse("2020-01-0${index + 1}T00:00:00Z").toEpochMilli()
                output.putNextEntry(entry)
                if (!entry.isDirectory) output.write(name.toByteArray())
                output.closeEntry()
            }
        }

        ArchiveTimestampNormalizer.normalize(archive)

        JarFile(archive.toFile()).use { jar ->
            assertEquals(
                setOf(ArchiveTimestampNormalizer.ARCHIVE_TIMESTAMP.toEpochMilli()),
                jar.entries().asSequence().map(JarEntry::getTime).toSet(),
            )
        }
    }
}
