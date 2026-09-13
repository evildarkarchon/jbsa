package io.github.evildarkarchon.jbsa.build

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

/** Keeps benchmark code and its standalone artifact outside publication and production input graphs. */
internal object BenchmarkIsolationPolicy {
    private val productionInputs =
        listOf(
            ":jbsa" to "runtimeClasspath",
            ":jbsa-cli" to "runtimeClasspath",
            ":jbsa-dist" to JbsaThinApplicationIdentity.RUNTIME_CONFIGURATION,
        )

    /**
     * Rejects install/publication capabilities and benchmark dependencies from product or staging projects.
     *
     * @throws GradleException when the benchmark enters a publication, production, or staging input graph
     */
    fun verify(root: Project) {
        val benchmark = root.project(JbsaBenchmarkIdentity.PROJECT_PATH)
        val publicationTasks =
            benchmark.tasks.names.filter { name ->
                name.startsWith("install", ignoreCase = true) || name.startsWith("publish", ignoreCase = true)
            }
        if (publicationTasks.isNotEmpty()) {
            throw GradleException(
                "${JbsaBenchmarkIdentity.PROJECT_PATH} is build-only and must not expose install or publication tasks: " +
                    publicationTasks
            )
        }

        val standaloneOutputs =
            benchmark.tasks.named(JbsaBenchmarkIdentity.STANDALONE_TASK).get().outputs.files.files
                .map { file -> file.canonicalFile }
                .toSet()
        val leakedInputs =
            productionInputs.flatMap { (projectPath, configurationName) ->
                val configuration = root.project(projectPath).configurations.getByName(configurationName)
                val projectLeak =
                    configuration.incoming.resolutionResult.allComponents.any { component ->
                        val identifier = component.id
                        identifier is ProjectComponentIdentifier &&
                            identifier.projectPath == JbsaBenchmarkIdentity.PROJECT_PATH
                    }
                val artifactLeak = configuration.files.map { file -> file.canonicalFile }.any(standaloneOutputs::contains)
                if (projectLeak || artifactLeak) {
                    listOf(projectPath to configurationName)
                } else {
                    emptyList()
                }
            }
        if (leakedInputs.isNotEmpty()) {
            throw GradleException(
                leakedInputs.joinToString { (projectPath, configurationName) ->
                    "$projectPath must not consume build-only ${JbsaBenchmarkIdentity.PROJECT_PATH} " +
                        "through $configurationName"
                }
            )
        }

        root.logger.lifecycle("JBSA_BENCHMARK_PUBLICATION_TASKS ${publicationTasks.size}")
        root.logger.lifecycle("JBSA_BENCHMARK_PRODUCTION_INPUTS ${leakedInputs.size}")
    }
}
