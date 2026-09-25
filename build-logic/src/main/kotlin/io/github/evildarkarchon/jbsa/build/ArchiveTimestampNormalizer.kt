package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Rewrites ZIP-compatible archives so every entry carries JBSA's fixed reproducibility epoch. */
internal object ArchiveTimestampNormalizer {
    val ARCHIVE_TIMESTAMP: Instant = Instant.parse("2026-09-03T00:00:00Z")

    /**
     * Rewrites [archive] in entry order with unchanged payloads and the fixed archive timestamp.
     * The replacement is created beside the original so a successful move is atomic where supported.
     */
    fun normalize(archive: Path) {
        val replacement = Files.createTempFile(archive.parent, archive.fileName.toString(), ".timestamp")
        try {
            ZipFile(archive.toFile()).use { input ->
                ZipOutputStream(Files.newOutputStream(replacement)).use { output ->
                    input.entries().asSequence().forEach { source ->
                        val target = ZipEntry(source.name)
                        target.comment = source.comment
                        target.method = source.method
                        target.time = ARCHIVE_TIMESTAMP.toEpochMilli()
                        if (source.method == ZipEntry.STORED) {
                            target.size = source.size
                            target.compressedSize = source.size
                            target.crc = source.crc
                        }
                        output.putNextEntry(target)
                        if (!source.isDirectory) input.getInputStream(source).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
            try {
                Files.move(
                    replacement,
                    archive,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Same-directory replacement is still recoverable on filesystems without atomic moves.
                Files.move(replacement, archive, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(replacement)
        }
    }
}
