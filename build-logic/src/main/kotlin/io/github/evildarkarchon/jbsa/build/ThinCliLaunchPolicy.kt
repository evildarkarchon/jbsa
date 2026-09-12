package io.github.evildarkarchon.jbsa.build

import groovy.json.JsonSlurper
import java.nio.file.Path
import org.gradle.api.GradleException

/** Parsed subset of the checked-in Windows thin-launcher policy needed by Gradle gates. */
internal data class ThinCliLaunchPolicy(
    val mainModule: String,
    val mainClass: String,
    val runtimeArtifacts: Map<String, String>,
) {
    companion object {
        /** Parses and validates the stable launcher fields and runtime-artifact hash entries. */
        fun load(path: Path): ThinCliLaunchPolicy {
            val root = JsonSlurper().parse(path.toFile()) as? Map<*, *>
                ?: throw GradleException("Launch policy must be a JSON object: $path")
            val schemaVersion = (root["schemaVersion"] as? Number)?.toInt()
            if (schemaVersion != 1) throw GradleException("Launch policy schemaVersion must be 1.")
            val mainModule = root["mainModule"] as? String ?: throw GradleException("Launch policy mainModule is missing.")
            val mainClass = root["mainClass"] as? String ?: throw GradleException("Launch policy mainClass is missing.")
            val artifacts = root["runtimeArtifacts"] as? List<*>
                ?: throw GradleException("Launch policy runtimeArtifacts is missing.")
            val entries =
                artifacts.associate { value ->
                    val entry = value as? Map<*, *>
                        ?: throw GradleException("Launch policy runtime artifact must be an object.")
                    val file = entry["file"] as? String
                        ?: throw GradleException("Launch policy runtime artifact file is missing.")
                    val digest = entry["sha256"] as? String
                        ?: throw GradleException("Launch policy runtime artifact sha256 is missing.")
                    if (!digest.matches(Regex("[0-9a-f]{64}"))) {
                        throw GradleException("Launch policy SHA-256 is invalid for $file.")
                    }
                    file to digest
                }
            if (entries.size != artifacts.size) {
                throw GradleException("Launch policy runtime artifact filenames must be unique.")
            }
            return ThinCliLaunchPolicy(mainModule, mainClass, entries)
        }
    }
}
