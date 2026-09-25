package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Emits exact external artifacts selected by the Gradle production dependency model. */
@DisableCachingByDefault(because = "Resolution metadata is read from live Gradle configurations at execution time.")
abstract class GenerateResolvedProductionDependencies : DefaultTask() {
    @get:Input abstract val scopeNames: ListProperty<String>

    @get:Classpath abstract val artifactFiles: ConfigurableFileCollection

    @get:OutputFile abstract val manifestFile: RegularFileProperty

    @get:Internal
    internal val scopes = mutableListOf<ProductionResolutionScope>()

    /** Adds one production configuration and declares its artifact bytes as task inputs. */
    internal fun productionScope(sourceProject: String, configuration: Configuration) {
        scopes += ProductionResolutionScope(sourceProject, configuration)
        artifactFiles.from(
            configuration.incoming.artifactView {
                componentFilter { identifier -> identifier is ModuleComponentIdentifier }
            }.files
        )
    }

    /** Resolves configured production scopes and writes one stable record per selected external artifact. */
    @TaskAction
    fun generate() {
        val records =
            scopes
                .flatMap(::recordsFor)
                .sortedWith(
                    compareBy(
                        { it.sourceProject },
                        { it.configuration },
                        { it.resolvedGroup },
                        { it.resolvedName },
                        { it.resolvedVersion },
                        { it.classifier ?: "" },
                        { it.artifactFileName },
                        { it.requestedGroup },
                        { it.requestedName },
                        { it.requestedVersion },
                    )
                )
                .map(DependencyManifestRecord::asJsonModel)
        DeterministicJson.write(
            manifestFile.get().asFile.toPath(),
            linkedMapOf<String, Any>("schemaVersion" to 1, "dependencies" to records),
        )
    }

    /** Converts one resolved configuration into artifact-bound records without machine-local paths. */
    private fun recordsFor(scope: ProductionResolutionScope): List<DependencyManifestRecord> {
        val configuration = scope.configuration
        val requestsByComponent =
            configuration.incoming.resolutionResult.allDependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .mapNotNull { dependency ->
                    val selected = dependency.selected.id as? ModuleComponentIdentifier ?: return@mapNotNull null
                    val requested = dependency.requested as? ModuleComponentSelector ?: return@mapNotNull null
                    selected.displayName to RequestedModule(requested.group, requested.module, requested.version)
                }
                .groupBy({ it.first }, { it.second })
        val usage =
            configuration.attributes.keySet()
                .firstOrNull { attribute -> attribute.name == "org.gradle.usage" }
                ?.let { attribute -> configuration.attributes.getAttribute(attribute)?.toString() }
                ?: configuration.name

        return configuration.incoming.artifacts.resolvedArtifacts.get().flatMap { artifact ->
            val selected = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@flatMap emptyList()
            val requests =
                requestsByComponent[selected.displayName]
                    .orEmpty()
                    .distinctBy { request -> "${request.group}:${request.module}:${request.version}" }
                    .ifEmpty {
                        listOf(RequestedModule(selected.group, selected.module, selected.version))
                    }
            val attributes =
                artifact.variant.attributes.keySet().sortedBy { attribute -> attribute.name }.associate { attribute ->
                    attribute.name to artifact.variant.attributes.getAttribute(attribute).toString()
                }
            requests.map { request ->
                DependencyManifestRecord(
                    sourceProject = scope.sourceProject,
                    configuration = configuration.name,
                    usage = usage,
                    requestedGroup = request.group,
                    requestedName = request.module,
                    requestedVersion = request.version,
                    resolvedGroup = selected.group,
                    resolvedName = selected.module,
                    resolvedVersion = selected.version,
                    classifier = classifier(selected, artifact.file.name),
                    variantAttributes = attributes,
                    artifactFileName = artifact.file.name,
                    sha256 = sha256(artifact.file.toPath()),
                )
            }
        }
    }

    /** Extracts a Maven classifier from the selected artifact filename when one is present. */
    private fun classifier(component: ModuleComponentIdentifier, fileName: String): String? {
        val extensionIndex = fileName.lastIndexOf('.')
        val stem = if (extensionIndex < 0) fileName else fileName.substring(0, extensionIndex)
        val base = "${component.module}-${component.version}"
        return if (stem == base) null else stem.removePrefix("$base-").takeIf { it != stem && it.isNotBlank() }
    }

    /** Computes the lowercase SHA-256 of the exact selected artifact bytes. */
    private fun sha256(path: java.nio.file.Path): String =
        Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
}

/** Associates a production source project with one resolvable Gradle configuration. */
internal data class ProductionResolutionScope(
    val sourceProject: String,
    val configuration: Configuration,
)

/** Immutable requested coordinate used to deduplicate resolution edges. */
private data class RequestedModule(
    val group: String,
    val module: String,
    val version: String,
)

/** Stable resolved-production record before conversion to insertion-ordered JSON data. */
private data class DependencyManifestRecord(
    val sourceProject: String,
    val configuration: String,
    val usage: String,
    val requestedGroup: String,
    val requestedName: String,
    val requestedVersion: String,
    val resolvedGroup: String,
    val resolvedName: String,
    val resolvedVersion: String,
    val classifier: String?,
    val variantAttributes: Map<String, String>,
    val artifactFileName: String,
    val sha256: String,
) {
    /** Converts the record into the schema's deterministic key and nested-object order. */
    fun asJsonModel(): Map<String, Any?> =
        linkedMapOf(
            "sourceProject" to sourceProject,
            "configuration" to configuration,
            "usage" to usage,
            "requested" to
                linkedMapOf(
                    "groupId" to requestedGroup,
                    "artifactId" to requestedName,
                    "version" to requestedVersion,
                ),
            "resolved" to
                linkedMapOf(
                    "groupId" to resolvedGroup,
                    "artifactId" to resolvedName,
                    "version" to resolvedVersion,
                ),
            "classifier" to classifier,
            "selectedVariant" to linkedMapOf("attributes" to variantAttributes),
            "artifact" to linkedMapOf("fileName" to artifactFileName, "sha256" to sha256),
        )
}
