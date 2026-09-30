package io.github.evildarkarchon.jbsa.build

import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.module.ModuleFinder
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations

/**
 * Proves that the public library and its runtime module path link with `jlink`.
 *
 * `jlink` rejects automatic modules outright, so every dependency on [modulePath] must carry an
 * explicit descriptor. The task links [libraryJar]'s module into a throwaway runtime and requires
 * each expected module to appear in the linked image. It checks linkability only; the reviewed
 * release image and its options remain the JBSA-DIST-005 packaging step.
 */
abstract class VerifyLinkableRuntime : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryJar: RegularFileProperty

    /** The library's runtime dependencies as `jlink` must see them, with synthesized descriptors. */
    @get:Classpath abstract val modulePath: ConfigurableFileCollection

    /** The Java 25 toolchain whose `jlink` links the image; nested so a toolchain change reruns the check. */
    @get:Nested abstract val javaLauncher: Property<JavaLauncher>

    /** Receives the throwaway linked runtime, replaced on every run. */
    @get:OutputDirectory abstract val linkedRuntime: DirectoryProperty

    @get:Inject abstract val execOperations: ExecOperations

    @get:Inject abstract val fileSystemOperations: FileSystemOperations

    /**
     * Links the library module and checks the image.
     *
     * @throws GradleException when a module-path entry is still automatic, `jlink` fails, or the
     *     linked image lacks an expected module
     */
    @TaskAction
    fun verify() {
        val entries = listOf(libraryJar.get().asFile) + modulePath.files.sortedBy(File::getName)
        // Name the offending JAR up front; jlink's own message is the same fact without the remedy.
        ModuleFinder.of(*entries.map(File::toPath).toTypedArray()).findAll()
            .filter { reference -> reference.descriptor().isAutomatic }
            .forEach { reference ->
                throw GradleException(
                    "Module ${reference.descriptor().name()} from ${reference.location().orElse(null)} is " +
                        "automatic, which jlink rejects; give it an explicit descriptor in JbsaPublicLibraryPlugin."
                )
            }

        val output = linkedRuntime.get().asFile
        // jlink refuses an existing --output directory, so the declared output is emptied and removed.
        fileSystemOperations.delete { delete(output) }
        val log = ByteArrayOutputStream()
        val result =
            execOperations.exec {
                executable = jlink().absolutePath
                args(
                    "--module-path",
                    entries.joinToString(File.pathSeparator) { entry -> entry.absolutePath },
                    "--add-modules",
                    LIBRARY_MODULE,
                    "--output",
                    output.absolutePath,
                )
                standardOutput = log
                errorOutput = log
                isIgnoreExitValue = true
            }
        if (result.exitValue != 0) {
            throw GradleException("jlink failed with exit ${result.exitValue}:\n${log.toString(Charsets.UTF_8)}")
        }

        val release = Properties()
        output.resolve("release").reader(Charsets.UTF_8).use(release::load)
        val linked = release.getProperty("MODULES").orEmpty().trim('"').split(' ').toSet()
        val missing = EXPECTED_MODULES - linked
        if (missing.isNotEmpty()) {
            throw GradleException("Linked runtime lacks modules $missing; it contains $linked.")
        }
    }

    /** Resolves `jlink` beside the toolchain's launcher, which is the same JDK installation. */
    private fun jlink(): File {
        val bin = javaLauncher.get().metadata.installationPath.dir("bin").asFile
        return listOf("jlink.exe", "jlink").map(bin::resolve).firstOrNull(File::isFile)
            ?: throw GradleException("The Java toolchain at ${bin.parentFile} has no jlink executable.")
    }

    private companion object {
        const val LIBRARY_MODULE = "io.github.evildarkarchon.jbsa"

        /** The library, the portable LZ4 provider it always requires, and that provider's JDK need. */
        val EXPECTED_MODULES = setOf(LIBRARY_MODULE, "org.lz4.java", "jdk.unsupported")
    }
}
