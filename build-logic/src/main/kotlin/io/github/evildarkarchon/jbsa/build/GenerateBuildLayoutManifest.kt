package io.github.evildarkarchon.jbsa.build

import java.io.Serializable
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Emits the versioned internal contract for repository-automation build outputs. */
@CacheableTask
abstract class GenerateBuildLayoutManifest : DefaultTask() {
    @get:Input abstract val rootProjectName: Property<String>

    @get:Nested abstract val outputEntries: ListProperty<BuildLayoutEntry>

    @get:Internal abstract val repositoryRoot: DirectoryProperty

    @get:OutputFile abstract val manifestFile: RegularFileProperty

    /** Validates every declared relative path and writes the contract in stable identifier order. */
    @TaskAction
    fun generate() {
        val root = repositoryRoot.get().asFile.toPath().toAbsolutePath().normalize()
        val ownedRoots = OWNED_OUTPUT_ROOTS
        val manifestPath = manifestFile.get().asFile.toPath().toAbsolutePath().normalize()
        val manifestRelativePath =
            try {
                root.relativize(manifestPath).toString().replace(java.io.File.separatorChar, '/')
            } catch (exception: IllegalArgumentException) {
                throw GradleException("Build-layout output must be a contained generated path: $manifestPath", exception)
            }
        validateContainedPath(root, manifestRelativePath, ownedRoots)
        val entries = outputEntries.get().sortedBy(BuildLayoutEntry::id)
        entries.forEach { entry -> validateContainedPath(root, entry.path, ownedRoots) }
        val outputs = entries.map(BuildLayoutEntry::asJsonModel)
        val model =
            linkedMapOf<String, Any>(
                "schemaVersion" to 1,
                "rootProject" to rootProjectName.get(),
                "ownedOutputRoots" to ownedRoots,
                "outputs" to outputs,
            )
        DeterministicJson.write(manifestPath, model)
    }

    /** Rejects absolute, traversing, non-target, and repository-escaping output locations. */
    private fun validateContainedPath(root: Path, relativePath: String, ownedRoots: List<String>) {
        val relative = Path.of(relativePath.replace('/', java.io.File.separatorChar))
        val normalized = relative.normalize()
        val resolved = root.resolve(normalized).normalize()
        val normalizedPath = normalized.toString().replace(java.io.File.separatorChar, '/')
        val isOwnedOutput = ownedRoots.any { owned -> normalizedPath == owned || normalizedPath.startsWith("$owned/") }
        if (
            relative.isAbsolute ||
                relative.any { segment -> segment.toString() == ".." } ||
                normalized.nameCount == 0 ||
                !isOwnedOutput ||
                !resolved.startsWith(root)
        ) {
            throw GradleException("Build-layout output must be a contained generated path: $relativePath")
        }
    }

    private companion object {
        val OWNED_OUTPUT_ROOTS =
            listOf(
                "jbsa-benchmarks/target",
                "jbsa-cli/target",
                "jbsa-conformance-tests/target",
                "jbsa-dist/target",
                "jbsa-test-support/target",
                "jbsa/target",
                "target",
            )
    }
}

/** Typed build-output identity used as the nested input to the layout manifest task. */
data class BuildLayoutEntry(
    @get:Input val id: String,
    @get:Input val kind: String,
    @get:Input val path: String,
    @get:Input val producerTask: String,
) : Serializable {
    /** Converts the typed entry into the schema's deterministic property order. */
    fun asJsonModel(): Map<String, String> =
        linkedMapOf(
            "id" to id,
            "kind" to kind,
            "path" to path,
            "producerTask" to producerTask,
        )
}
