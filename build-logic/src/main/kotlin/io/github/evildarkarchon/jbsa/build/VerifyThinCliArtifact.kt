package io.github.evildarkarchon.jbsa.build

import java.lang.module.ModuleFinder
import java.util.jar.JarFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Verifies the thin CLI artifact through its packaged JPMS and entry-point contracts. */
@CacheableTask
abstract class VerifyThinCliArtifact : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: RegularFileProperty

    @get:Input abstract val expectedVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val launchPolicy: RegularFileProperty

    /** Requires the exact filename, module boundary, version, and launcher class. */
    @TaskAction
    fun verifyArtifact() {
        val jar = artifact.get().asFile.toPath()
        val version = expectedVersion.get()
        requireContract(jar.fileName.toString() == "jbsa-cli-$version.jar", "Thin CLI JAR name changed: ${jar.fileName}.")
        val policy = ThinCliLaunchPolicy.load(launchPolicy.get().asFile.toPath())
        requireContract(
            policy.mainModule == JbsaThinApplicationIdentity.MODULE_NAME,
            "Thin CLI launch module changed: ${policy.mainModule}.",
        )
        requireContract(
            policy.mainClass == JbsaThinApplicationIdentity.MAIN_CLASS,
            "Thin CLI entry point changed: ${policy.mainClass}.",
        )
        val descriptor =
            ModuleFinder.of(jar).find(JbsaThinApplicationIdentity.MODULE_NAME).orElseThrow {
                GradleException("Thin CLI JAR does not expose module ${JbsaThinApplicationIdentity.MODULE_NAME}.")
            }.descriptor()
        requireContract(!descriptor.isAutomatic, "Thin CLI must remain an explicit module.")
        requireContract(!descriptor.isOpen, "Thin CLI module must not be open.")
        requireContract(descriptor.mainClass().isEmpty, "Thin CLI descriptor must not embed a main class.")
        requireContract(descriptor.exports().isEmpty(), "Thin CLI module must not export packages.")
        requireContract(descriptor.opens().isEmpty(), "Thin CLI module must not open packages.")
        requireContract(
            descriptor.requires().map { it.name() }.toSet() == setOf("java.base", "io.github.evildarkarchon.jbsa"),
            "Thin CLI module requirements changed: ${descriptor.requires().map { it.name() }}.",
        )
        requireContract(
            descriptor.rawVersion().orElse(null) == version,
            "Thin CLI module version must be $version.",
        )
        JarFile(jar.toFile()).use { archive ->
            val entry = policy.mainClass.replace('.', '/') + ".class"
            requireContract(archive.getJarEntry(entry) != null, "Thin CLI entry point is missing: ${policy.mainClass}.")
        }
    }

    /** Converts one failed artifact invariant into an actionable Gradle diagnostic. */
    private fun requireContract(condition: Boolean, message: String) {
        if (!condition) throw GradleException(message)
    }
}
