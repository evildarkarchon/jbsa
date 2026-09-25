package io.github.evildarkarchon.jbsa.build

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.base.plugins.LifecycleBasePlugin

/** Builds and verifies the retained build-only standalone JMH artifact. */
class JbsaBenchmarkPlugin : Plugin<Project> {
    /** Applies explicit JMH dependencies, Shadow packaging, benchmark tests, and artifact inspection. */
    override fun apply(project: Project) {
        project.pluginManager.apply("jbsa.java")
        project.pluginManager.apply("com.gradleup.shadow")
        configureDependencies(project)
        configureTests(project)
        configureStandaloneJar(project)
    }

    /** Keeps JMH, its annotation processor, project inputs, and tests explicit and build-only. */
    private fun configureDependencies(project: Project) {
        val catalog = PinnedVersionCatalog.load(project.rootDir.toPath().resolve("gradle/libs.versions.toml"))
        val jmhVersion = catalog.dependencyVersion("org.openjdk.jmh", "jmh-core")
        val generatorVersion = catalog.dependencyVersion("org.openjdk.jmh", "jmh-generator-annprocess")
        val junitVersion = catalog.dependencyVersion("org.junit.jupiter", "junit-jupiter")
        project.dependencies.add("implementation", project.dependencies.project(JbsaPublicLibraryIdentity.PROJECT_PATH))
        project.dependencies.add("implementation", project.dependencies.project(":jbsa-test-support"))
        project.dependencies.add("implementation", "org.openjdk.jmh:jmh-core:$jmhVersion")
        project.dependencies.add(
            "annotationProcessor",
            "org.openjdk.jmh:jmh-generator-annprocess:$generatorVersion",
        )
        project.dependencies.add(
            "testImplementation",
            project.dependencies.platform("org.junit:junit-bom:$junitVersion"),
        )
        project.dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter:$junitVersion")
        project.dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:$junitVersion")
    }

    /** Preserves the benchmark tests' unnamed-module native-access grant. */
    private fun configureTests(project: Project) {
        project.tasks.withType(Test::class.java).configureEach {
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }

    /** Produces the qualified standalone path and inspects its observable launch contract. */
    private fun configureStandaloneJar(project: Project) {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val standalone = project.tasks.named(JbsaBenchmarkIdentity.STANDALONE_TASK, ShadowJar::class.java) {
            archiveClassifier.set(JbsaBenchmarkIdentity.STANDALONE_CLASSIFIER)
            destinationDirectory.set(project.layout.buildDirectory)
            manifest.attributes["Main-Class"] = JbsaBenchmarkIdentity.MAIN_CLASS
            exclude("module-info.class", "META-INF/versions/**/module-info.class")
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            mergeServiceFiles()
            // Transformers must see duplicate service resources before the normal first-entry policy applies.
            filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
            failOnDuplicateEntries.set(true)
        }
        project.tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME) { dependsOn(standalone) }

        val requiredMainClasses =
            project.provider {
                sourceSets
                    .named("main")
                    .get()
                    .allJava
                    .matching { include("**/*.java") }
                    .files
                    .map { source ->
                        source.relativeTo(project.file("src/main/java")).invariantSeparatorsPath.removeSuffix(".java") +
                            ".class"
                    }
                    .sorted()
            }
        val requiredBenchmarks =
            requiredMainClasses.map { classEntries ->
                classEntries.filter { classEntry -> classEntry.endsWith("Benchmark.class") }
            }
        val requiredServices =
            project.provider {
                project.fileTree("src/main/resources/META-INF/services").files
                    .sortedBy { service -> service.invariantSeparatorsPath }
                    .associate { service ->
                        "META-INF/services/${service.name}" to
                            service
                                .readLines()
                                .map(String::trim)
                                .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
                    }
            }
        val verification =
            project.tasks.register(
                JbsaBenchmarkIdentity.VERIFY_ARTIFACT_TASK,
                VerifyBenchmarkArtifact::class.java,
            ) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Verifies the standalone benchmark launcher, JMH metadata, services, and JPMS exclusion."
                dependsOn(standalone)
                artifact.set(standalone.flatMap(AbstractArchiveTask::getArchiveFile))
                expectedMainClass.set(JbsaBenchmarkIdentity.MAIN_CLASS)
                requiredProjectClasses.set(requiredMainClasses)
                requiredBenchmarkClasses.set(requiredBenchmarks)
                requiredServiceProviders.set(requiredServices)
            }
        val javaLauncher =
            project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
                JdkPolicy.configureToolchain(this)
            }
        val smokeTest =
            project.tasks.register(JbsaBenchmarkIdentity.SMOKE_TEST_TASK, SmokeTestBenchmarkLauncher::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Launches the standalone JMH artifact and lists its generated benchmarks."
                dependsOn(verification)
                this.javaLauncher.set(javaLauncher)
                artifact.set(standalone.flatMap(AbstractArchiveTask::getArchiveFile))
                launcherArguments.set(listOf("-l"))
            }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(smokeTest) }
    }
}
