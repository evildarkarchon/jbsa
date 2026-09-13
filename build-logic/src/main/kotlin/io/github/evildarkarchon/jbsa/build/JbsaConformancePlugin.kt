package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.language.base.plugins.LifecycleBasePlugin

/** Builds the conformance project and exposes portable, tagged, and evidence-producing Gradle gates. */
class JbsaConformancePlugin : Plugin<Project> {
    /** Applies build-only dependencies, deterministic test tasks, and the retained PowerShell process boundary. */
    override fun apply(project: Project) {
        project.pluginManager.apply("jbsa.java")
        configureDependencies(project)
        configureArtifactBackedTests(project)
        configureTaggedTests(project)
        configureAutomatedConformance(project)
    }

    /** Declares all conformance consumers as test-only dependencies so they cannot enter product graphs. */
    private fun configureDependencies(project: Project) {
        val catalog = PinnedVersionCatalog.load(project.rootDir.toPath().resolve("gradle/libs.versions.toml"))
        val junitVersion = catalog.dependencyVersion("org.junit.jupiter", "junit-jupiter")
        val jacksonYamlVersion =
            catalog.dependencyVersion("com.fasterxml.jackson.dataformat", "jackson-dataformat-yaml")
        val snakeYamlVersion = catalog.dependencyVersion("org.yaml", "snakeyaml")
        listOf(JbsaPublicLibraryIdentity.PROJECT_PATH, JbsaThinApplicationIdentity.PROJECT_PATH, ":jbsa-test-support")
            .forEach { path -> project.dependencies.add("testImplementation", project.dependencies.project(path)) }
        project.dependencies.add(
            "testImplementation",
            project.dependencies.platform("org.junit:junit-bom:$junitVersion"),
        )
        project.dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter:$junitVersion")
        project.dependencies.add(
            "testImplementation",
            "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonYamlVersion",
        )
        project.dependencies.add("testImplementation", "org.yaml:snakeyaml:$snakeYamlVersion")
        project.dependencies.add(
            "testRuntimeOnly",
            "org.junit.platform:junit-platform-launcher:$junitVersion",
        )
    }

    /** Supplies exact built artifacts to black-box tests and preserves the conformance-only native setting. */
    private fun configureArtifactBackedTests(project: Project) {
        val testSourceSet = project.extensions.getByType(SourceSetContainer::class.java).named("test")
        val library = project.project(JbsaPublicLibraryIdentity.PROJECT_PATH)
        val cli = project.project(JbsaThinApplicationIdentity.PROJECT_PATH)
        val libraryJar = library.tasks.named("jar", Jar::class.java)
        val sourcesJar = library.tasks.named("sourcesJar", Jar::class.java)
        val javadocJar = library.tasks.named("javadocJar", Jar::class.java)
        val generatePom = library.tasks.named(JbsaPublicLibraryIdentity.generatePomTaskName())
        val consumerPom = library.layout.buildDirectory.file(JbsaPublicLibraryIdentity.publicationPath("pom-default.xml"))
        val cliJar = cli.tasks.named("jar", Jar::class.java)
        val processInputs =
            project.files(
                project.rootProject.fileTree("build") {
                    include("*.ps1", "*.py")
                },
                project.rootProject.fileTree("tests/conformance"),
                project.rootProject.fileTree("tests/fixtures"),
            )

        project.tasks.withType(Test::class.java).configureEach {
            dependsOn(libraryJar, sourcesJar, javadocJar, generatePom, cliJar)
            inputs.files(processInputs).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
            systemProperty("jbsa.library.jar", libraryJar.get().archiveFile.get().asFile.absolutePath)
            systemProperty("jbsa.library.sourcesJar", sourcesJar.get().archiveFile.get().asFile.absolutePath)
            systemProperty("jbsa.library.javadocJar", javadocJar.get().archiveFile.get().asFile.absolutePath)
            systemProperty("jbsa.library.consumerPom", consumerPom.get().asFile.absolutePath)
            systemProperty("jbsa.cli.jar", cliJar.get().archiveFile.get().asFile.absolutePath)
            jvmArgs("--enable-native-access=ALL-UNNAMED")
            doFirst {
                Files.createDirectories(project.rootProject.layout.buildDirectory.get().asFile.toPath())
                // Child validators need the Gradle-owned paths without teaching Java tests about build layout.
                environment("JBSA_LIBRARY_JAR", libraryJar.get().archiveFile.get().asFile.absolutePath)
                environment("JBSA_CONFORMANCE_TEST_CLASSES", testSourceSet.get().output.classesDirs.singleFile.absolutePath)
            }
        }
    }

    /** Maps every retained JUnit tag to a disjoint public Gradle test task. */
    private fun configureTaggedTests(project: Project) {
        val sourceSet = project.extensions.getByType(SourceSetContainer::class.java).named("test")
        val integrationTest = project.tasks.named("integrationTest", Test::class.java)
        integrationTest.configure { useJUnitPlatform { includeTags("contract") } }
        JbsaConformanceIdentity.TAGGED_TESTS
            .forEach { (taskName, tag) ->
                project.tasks.register(taskName, Test::class.java) {
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    description = "Runs the build-only '$tag' integration-test selection."
                    testClassesDirs = sourceSet.get().output.classesDirs
                    classpath = sourceSet.get().runtimeClasspath
                    include("**/*IT.class")
                    exclude("**/*Test.class")
                    useJUnitPlatform { includeTags(tag) }
                    shouldRunAfter(integrationTest)
                }
            }
    }

    /** Orders packaged candidates, harness regression coverage, evidence capture, and final interpretation. */
    private fun configureAutomatedConformance(project: Project) {
        val root = project.rootProject
        val libraryJar =
            project.project(JbsaPublicLibraryIdentity.PROJECT_PATH).tasks.named("jar", Jar::class.java)
        val cliJar =
            project.project(JbsaThinApplicationIdentity.PROJECT_PATH).tasks.named("jar", Jar::class.java)
        val harness = project.tasks.named("conformanceHarnessTest", Test::class.java)
        val evidence = root.layout.buildDirectory.dir(JbsaConformanceIdentity.EVIDENCE_DIRECTORY)
        val exitCode = root.layout.buildDirectory.file(JbsaConformanceIdentity.EXIT_CODE_FILE)
        val capture =
            project.tasks.register(JbsaConformanceIdentity.CAPTURE_TASK, RunAutomatedConformance::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Captures hosted Conformance Case evidence through the retained PowerShell runner."
                dependsOn(harness, libraryJar, cliJar)
                runnerScript.set(root.layout.projectDirectory.file("build/run-conformance.ps1"))
                algorithmInputs.from(
                    root.layout.projectDirectory.files(
                        "build/conformance-catalog.ps1",
                        "build/conformance-evidence.ps1",
                        "build/conformance-adapters.ps1",
                        "build/conformance-execution.ps1",
                        "build/verify-fixture-corpus.ps1",
                    )
                )
                contractInputs.from(
                    root.fileTree("tests/conformance"),
                    root.fileTree("tests/fixtures/synthetic"),
                    root.fileTree("docs/spec"),
                    root.fileTree("docs/reviews"),
                )
                candidateArtifacts.from(
                    libraryJar.flatMap(Jar::getArchiveFile),
                    cliJar.flatMap(Jar::getArchiveFile),
                )
                codecProfile.set(
                    root.layout.projectDirectory.file("jbsa/src/main/resources/META-INF/jbsa-codec-profile.json")
                )
                powershellExecutable.convention("pwsh")
                mode.convention("Hosted")
                evidenceDirectory.set(evidence)
                exitCodeFile.set(exitCode)
                outputs.upToDateWhen { false }
            }
        project.tasks.register(JbsaConformanceIdentity.AUTOMATED_TASK, VerifyAutomatedConformance::class.java) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Interprets retained Automated Conformance evidence without masking infrastructure failure."
            dependsOn(capture)
            exitCodeFile.set(exitCode)
            evidenceDirectory.set(evidence)
        }
    }
}
