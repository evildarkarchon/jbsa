package io.github.evildarkarchon.jbsa.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.base.plugins.LifecycleBasePlugin

/** Provides Java 25 compilation, deterministic archives, and the shared JBSA test lifecycle. */
class JbsaJavaPlugin : Plugin<Project> {
    /** Applies the portable Java and test conventions to one production or build-only Java project. */
    override fun apply(project: Project) {
        project.pluginManager.apply("java-library")
        configureCompilation(project)
        configureArchives(project)
        configureJavadocs(project)
        configureTests(project)
    }

    /** Retains the Java release, parameter metadata, encoding, lint, and module-path contracts. */
    private fun configureCompilation(project: Project) {
        project.extensions.getByType(JavaPluginExtension::class.java).apply {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
            modularity.inferModulePath.set(true)
        }
        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.release.set(25)
            options.isIncremental = true
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
            modularity.inferModulePath.set(true)
        }
    }

    /** Applies stable file order and the project-defined epoch to every ZIP-compatible archive. */
    private fun configureArchives(project: Project) {
        project.tasks.withType(AbstractArchiveTask::class.java).configureEach {
            isReproducibleFileOrder = true
            // Gradle's built-in normalized epoch is not JBSA's approved 2026 migration epoch.
            isPreserveFileTimestamps = false
            doLast { ArchiveTimestampNormalizer.normalize(archiveFile.get().asFile.toPath()) }
        }
    }

    /** Retains UTF-8, US-English, and timestamp-free generated Javadoc content. */
    private fun configureJavadocs(project: Project) {
        project.tasks.withType(Javadoc::class.java).configureEach {
            val standardOptions = options as StandardJavadocDocletOptions
            standardOptions.encoding = "UTF-8"
            standardOptions.charSet = "UTF-8"
            standardOptions.docEncoding = "UTF-8"
            standardOptions.locale = "en_US"
            standardOptions.addBooleanOption("notimestamp", true)
        }
    }

    /** Compiles the existing test source set once and runs Test/IT classes in disjoint tasks. */
    private fun configureTests(project: Project) {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val testSourceSet = sourceSets.named("test")
        project.tasks.named("test", Test::class.java) {
            include("**/*Test.class")
            exclude("**/*IT.class")
        }
        val integrationTest =
            project.tasks.register("integrationTest", Test::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Runs integration tests from the shared test source set."
                testClassesDirs = testSourceSet.get().output.classesDirs
                classpath = testSourceSet.get().runtimeClasspath
                include("**/*IT.class")
                exclude("**/*Test.class")
                shouldRunAfter(project.tasks.named("test"))
            }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(integrationTest) }

        project.tasks.withType(Test::class.java).configureEach {
            useJUnitPlatform()
            maxParallelForks = 1
            forkEvery = 0
            modularity.inferModulePath.set(true)
            systemProperty("user.timezone", "UTC")
            systemProperty("user.language", "en")
            systemProperty("user.country", "US")
            systemProperty("file.encoding", "UTF-8")
            systemProperty("junit.jupiter.execution.parallel.enabled", "false")
            systemProperty(
                "junit.jupiter.testclass.order.default",
                "org.junit.jupiter.api.ClassOrderer${'$'}ClassName",
            )
            systemProperty("jbsa.reactor.root", project.rootDir.absolutePath)
            systemProperty("jbsa.version", project.version.toString())
            forwardEvidenceOptIns(this)
        }
    }

    /** Forwards only the reviewed local-evidence switches from Gradle into its isolated test JVM. */
    private fun forwardEvidenceOptIns(test: Test) {
        EVIDENCE_OPT_IN_PROPERTIES.forEach { propertyName ->
            // Test JVMs do not inherit Gradle's -D properties, so explicit forwarding prevents silent skips.
            System.getProperty(propertyName)?.let { value -> test.systemProperty(propertyName, value) }
        }
    }

    private companion object {
        val EVIDENCE_OPT_IN_PROPERTIES =
            setOf(
                "jbsa.tes3.local",
                "jbsa.bsa.local",
                "jbsa.ba2.local",
                "jbsa.bsa.performance",
                "jbsa.ba2.performance",
                "jbsa.dds.performance",
            )
    }
}
