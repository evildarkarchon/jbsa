package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Verifies external runtime dependency filenames and bytes against launch policy. */
@CacheableTask
abstract class VerifyRuntimeDependencies : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeDependencies: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val launchPolicy: RegularFileProperty

    /** Rejects any missing, extra, renamed, or byte-changed external runtime input. */
    @TaskAction
    fun verifyDependencies() {
        val expected = ThinCliLaunchPolicy.load(launchPolicy.get().asFile.toPath()).runtimeArtifacts
        val directory = runtimeDependencies.get().asFile.toPath()
        val actual =
            Files.list(directory).use { paths ->
                paths.iterator().asSequence().associate { path -> path.fileName.toString() to sha256(path) }
            }
        if (actual != expected) {
            throw GradleException("External runtime dependencies changed. Expected $expected but found $actual.")
        }
    }

    /** Computes a lowercase SHA-256 digest without loading the whole artifact into memory. */
    private fun sha256(path: java.nio.file.Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
