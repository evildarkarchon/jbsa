package io.github.evildarkarchon.jbsa.build

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Builds the production aggregate after validating the pinned CycloneDX generator's schema output. */
@CacheableTask
abstract class GenerateProductionSbom : DefaultTask() {
    @get:Input abstract val componentGroup: Property<String>

    @get:Input abstract val componentVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rawCycloneDx: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val resolvedDependencies: RegularFileProperty

    @get:OutputFile abstract val sbomFile: RegularFileProperty

    /** Validates the tool output and emits the deterministic production-only CycloneDX 1.6 graph. */
    @TaskAction
    fun generate() {
        val raw = parseObject(rawCycloneDx.get().asFile)
        if (raw["bomFormat"] != "CycloneDX" || raw["specVersion"] != "1.6") {
            throw GradleException("The pinned CycloneDX generator did not produce a CycloneDX 1.6 document.")
        }
        val manifest = parseObject(resolvedDependencies.get().asFile)
        if ((manifest["schemaVersion"] as? Number)?.toInt() != 1) {
            throw GradleException("Unsupported resolved-production-dependency schema: ${manifest["schemaVersion"]}")
        }
        @Suppress("UNCHECKED_CAST")
        val dependencyRecords = manifest["dependencies"] as? List<Map<String, Any?>>
            ?: throw GradleException("Resolved-production-dependency manifest has no dependencies array.")
        val external =
            dependencyRecords
                .map(::externalComponent)
                .associateBy { component -> component.getValue("bom-ref") as String }
                .values
                .sortedBy { component -> component.getValue("bom-ref") as String }
        if (external.isEmpty()) {
            throw GradleException("The production SBOM cannot omit every resolved external artifact.")
        }

        val group = componentGroup.get()
        val version = componentVersion.get()
        val cycloneDxVersion = validateCycloneDxProductionGraph(raw, external, group, version)
        val rootRef = purl(group, BuildIdentity.ROOT_NAME, version, "pom", null)
        val libraryRef = purl(group, "jbsa", version, "jar", null)
        val cliRef = purl(group, "jbsa-cli", version, "jar", null)
        val lwjglRefs = external.map { it.getValue("bom-ref") as String }
        val coreRef = lwjglRefs.singleOrNull { ref -> ref.contains("/lwjgl@") && !ref.contains("classifier=") }
            ?: throw GradleException("The production graph must contain one unclassified LWJGL core artifact.")

        val projectComponents =
            listOf(
                component(group, "jbsa", version, libraryRef),
                component(group, "jbsa-cli", version, cliRef),
            )
        val relationships =
            buildList {
                add(relationship(rootRef, listOf(cliRef, libraryRef)))
                add(relationship(cliRef, listOf(libraryRef)))
                add(relationship(libraryRef, lwjglRefs))
                external.forEach { component ->
                    val ref = component.getValue("bom-ref") as String
                    val children = if (component.getValue("name") == "lwjgl-lz4") listOf(coreRef) else emptyList()
                    add(relationship(ref, children))
                }
            }.sortedBy { relationship -> relationship.getValue("ref") as String }
        val rootComponent = component(group, BuildIdentity.ROOT_NAME, version, rootRef)
        val model =
            linkedMapOf<String, Any>(
                "\$schema" to "https://cyclonedx.org/schema/bom-1.6.schema.json",
                "bomFormat" to "CycloneDX",
                "specVersion" to "1.6",
                "version" to 1,
                "metadata" to
                    linkedMapOf(
                        "tools" to
                            linkedMapOf(
                                "components" to
                                    listOf(
                                        linkedMapOf(
                                            "type" to "application",
                                            "author" to "CycloneDX",
                                            "name" to "cyclonedx-gradle-plugin",
                                            "version" to cycloneDxVersion,
                                        )
                                    )
                            ),
                        "component" to rootComponent,
                    ),
                "components" to (projectComponents + external).sortedBy { it.getValue("bom-ref") as String },
                "dependencies" to relationships,
            )
        DeterministicJson.write(sbomFile.get().asFile.toPath(), model)
    }

    /** Reconciles the plugin's production component, hashes, and relationships with resolved artifact bytes. */
    private fun validateCycloneDxProductionGraph(
        raw: Map<String, Any?>,
        external: List<Map<String, Any>>,
        group: String,
        version: String,
    ): String {
        @Suppress("UNCHECKED_CAST")
        val metadata = raw["metadata"] as? Map<String, Any?>
            ?: throw GradleException("The CycloneDX production graph has no metadata object.")
        @Suppress("UNCHECKED_CAST")
        val root = metadata["component"] as? Map<String, Any?>
            ?: throw GradleException("The CycloneDX production graph has no root component.")
        if (root["group"] != group || root["name"] != "jbsa" || root["version"] != version) {
            throw GradleException("The CycloneDX production graph does not describe the JBSA library.")
        }
        val rawRootRef = root["bom-ref"] as? String
            ?: throw GradleException("The CycloneDX production root has no component reference.")

        @Suppress("UNCHECKED_CAST")
        val tools = metadata["tools"] as? Map<String, Any?>
            ?: throw GradleException("The CycloneDX production graph has no generator identity.")
        @Suppress("UNCHECKED_CAST")
        val toolComponents = tools["components"] as? List<Map<String, Any?>>
            ?: throw GradleException("The CycloneDX production graph has no generator components.")
        val generator = toolComponents.singleOrNull { tool -> tool["name"] == "cyclonedx-gradle-plugin" }
            ?: throw GradleException("The production graph was not produced by the pinned CycloneDX plugin.")
        val generatorVersion = generator["version"] as? String
            ?: throw GradleException("The CycloneDX generator has no version.")
        if (generatorVersion != "3.4.1") {
            throw GradleException("Unexpected CycloneDX Gradle plugin version: $generatorVersion")
        }

        @Suppress("UNCHECKED_CAST")
        val rawComponents = raw["components"] as? List<Map<String, Any?>>
            ?: throw GradleException("The CycloneDX production graph has no components array.")
        val actualByRef =
            rawComponents.associateBy { component ->
                component["bom-ref"] as? String
                    ?: throw GradleException("CycloneDX component has no component reference.")
            }
        val expectedUnclassified =
            external.filterNot { component -> (component.getValue("bom-ref") as String).contains("classifier=") }
        val expectedRefs = expectedUnclassified.map { component -> component.getValue("bom-ref") as String }.toSet()
        if (actualByRef.keys != expectedRefs) {
            throw GradleException("CycloneDX components differ from unclassified production resolution.")
        }
        expectedUnclassified.forEach { expected ->
            val ref = expected.getValue("bom-ref") as String
            @Suppress("UNCHECKED_CAST")
            val expectedHash =
                ((expected.getValue("hashes") as List<Map<String, String>>).single()).getValue("content")
            @Suppress("UNCHECKED_CAST")
            val rawHashes = actualByRef.getValue(ref)["hashes"] as? List<Map<String, String>> ?: emptyList()
            val actualHash = rawHashes.singleOrNull { hash -> hash["alg"] == "SHA-256" }?.get("content")
            if (actualHash != expectedHash) {
                throw GradleException("CycloneDX artifact hash disagrees with production resolution: $ref")
            }
        }

        val coreRef = expectedRefs.single { ref -> ref.contains("/lwjgl@") }
        val lz4Ref = expectedRefs.single { ref -> ref.contains("/lwjgl-lz4@") }
        val expectedRelationships =
            mapOf(
                rawRootRef to expectedRefs,
                coreRef to emptySet(),
                lz4Ref to setOf(coreRef),
            )
        @Suppress("UNCHECKED_CAST")
        val rawRelationships = raw["dependencies"] as? List<Map<String, Any?>>
            ?: throw GradleException("The CycloneDX production graph has no relationships array.")
        val actualRelationships =
            rawRelationships.associate { relationship ->
                val ref = relationship["ref"] as? String
                    ?: throw GradleException("CycloneDX relationship has no source reference.")
                @Suppress("UNCHECKED_CAST")
                val dependsOn = relationship["dependsOn"] as? List<String> ?: emptyList()
                ref to dependsOn.toSet()
            }
        if (actualRelationships != expectedRelationships) {
            throw GradleException("CycloneDX relationships differ from production resolution.")
        }
        return generatorVersion
    }

    /** Reads one JSON object with a stable error when a producer emits the wrong shape. */
    private fun parseObject(file: java.io.File): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return JsonSlurper().parse(file) as? Map<String, Any?>
            ?: throw GradleException("Expected a JSON object in ${file.path}")
    }

    /** Converts one manifest row to a CycloneDX component with the selected artifact hash. */
    private fun externalComponent(record: Map<String, Any?>): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        val resolved = record["resolved"] as? Map<String, String>
            ?: throw GradleException("Resolved dependency row has no resolved coordinates.")
        @Suppress("UNCHECKED_CAST")
        val artifact = record["artifact"] as? Map<String, String>
            ?: throw GradleException("Resolved dependency row has no artifact identity.")
        val classifier = record["classifier"] as? String
        val ref =
            purl(
                resolved.getValue("groupId"),
                resolved.getValue("artifactId"),
                resolved.getValue("version"),
                "jar",
                classifier,
            )
        return linkedMapOf(
            "type" to "library",
            "bom-ref" to ref,
            "group" to resolved.getValue("groupId"),
            "name" to resolved.getValue("artifactId"),
            "version" to resolved.getValue("version"),
            "hashes" to listOf(linkedMapOf("alg" to "SHA-256", "content" to artifact.getValue("sha256"))),
            "purl" to ref,
        )
    }

    /** Creates a project component matching the preserved Maven logical identity. */
    private fun component(group: String, name: String, version: String, ref: String): Map<String, Any> =
        linkedMapOf(
            "type" to "library",
            "bom-ref" to ref,
            "group" to group,
            "name" to name,
            "version" to version,
            "purl" to ref,
        )

    /** Creates one sorted CycloneDX dependency relationship. */
    private fun relationship(ref: String, dependsOn: List<String>): Map<String, Any> =
        linkedMapOf("ref" to ref, "dependsOn" to dependsOn.distinct().sorted())

    /** Builds the stable Maven package URL form used by the captured parity baseline. */
    private fun purl(group: String, name: String, version: String, type: String, classifier: String?): String {
        val qualifiers =
            buildList {
                if (classifier != null) add("classifier=$classifier")
                add("type=$type")
            }
        return "pkg:maven/$group/$name@$version?${qualifiers.joinToString("&")}"
    }
}
