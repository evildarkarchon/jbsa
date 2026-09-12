package io.github.evildarkarchon.jbsa.build

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.FileCollection

/** Selects shipped external JARs without allowing project artifacts into runtime-input sets. */
internal object ExternalRuntimeArtifacts {
    /** Returns the resolved external artifacts while retaining the configuration's build dependencies. */
    fun from(configuration: Configuration): FileCollection =
        configuration.incoming.artifactView {
            componentFilter { identifier -> identifier !is ProjectComponentIdentifier }
        }.files
}
