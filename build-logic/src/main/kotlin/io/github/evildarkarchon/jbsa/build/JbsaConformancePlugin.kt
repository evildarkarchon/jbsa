package io.github.evildarkarchon.jbsa.build

import groovy.json.JsonSlurper
import java.nio.file.Files
import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaToolchainService
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
        configureAutomatedAssurance(project)
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
                    include("*.ps1", "*.py", "assurance/**/*.py")
                },
                // Plan changes must invalidate the shadow gate even when Java test sources are unchanged.
                project.rootProject.fileTree("tests/assurance"),
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

    /** Aggregates the compact Assurance Plan checks and executable Archive Family scenarios. */
    private fun configureAutomatedAssurance(project: Project) {
        val root = project.rootProject
        val libraryTests = project.project(JbsaPublicLibraryIdentity.PROJECT_PATH).tasks.named("test")
        val cliTests = project.project(JbsaThinApplicationIdentity.PROJECT_PATH).tasks.named("test")
        val contractTests = project.tasks.named("integrationTest")
        val familyTests = selectedFamilyTasks(project)
        val assuranceTests = root.layout.projectDirectory.dir("tests/assurance")
        val javaLauncher =
            project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
                JdkPolicy.configureToolchain(this)
            }
        val planValidation =
            project.tasks.register("assurancePlanValidation", Exec::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Runs fail-closed Assurance Plan, comparison, history, and capsule tests."
                workingDir(root.rootDir)
                inputs.dir(assuranceTests)
                inputs.dir(root.layout.projectDirectory.dir("build/assurance"))
                commandLine(
                    "python",
                    "-m",
                    "unittest",
                    "discover",
                    "-s",
                    "tests/assurance",
                    "-p",
                    "test_*.py",
                )
                onlyIf { assuranceTests.asFile.isDirectory }
            }
        val comparison =
            project.tasks.register("assuranceCoverageComparison", Exec::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Requires the reviewed v1 catalog to map completely to Assurance v2."
                workingDir(root.rootDir)
                inputs.files(
                    root.layout.projectDirectory.file("build/assurance/compare.py"),
                    root.layout.projectDirectory.file("build/assurance/plan.py"),
                    root.layout.projectDirectory.file("tests/assurance/plan.json"),
                    root.layout.projectDirectory.file("tests/conformance/catalog.json"),
                )
                outputs.file(root.layout.buildDirectory.file("assurance/legacy-comparison.json"))
                commandLine(
                    "python",
                    "build/assurance/compare.py",
                    "--legacy",
                    "tests/conformance/catalog.json",
                    "--plan",
                    "tests/assurance/plan.json",
                    "--output",
                    root.layout.buildDirectory.file("assurance/legacy-comparison.json").get().asFile.absolutePath,
                )
                onlyIf { assuranceTests.asFile.isDirectory }
            }
        val history =
            project.tasks.register("assuranceHistoryVerification", Exec::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Verifies frozen conformance and performance v1 evidence against its digest index."
                workingDir(root.rootDir)
                inputs.files(
                    root.layout.projectDirectory.file("build/assurance/history.py"),
                    root.layout.projectDirectory.file("tests/assurance/history.json"),
                )
                commandLine(
                    "python",
                    "build/assurance/history.py",
                    "verify",
                    "--repository",
                    root.rootDir.absolutePath,
                    "--index",
                    root.layout.projectDirectory.file("tests/assurance/history.json").asFile.absolutePath,
                )
                onlyIf { assuranceTests.asFile.isDirectory }
                outputs.upToDateWhen { false }
            }
        val capsule =
            project.tasks.register("assuranceEvidenceCapsule", Exec::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Records selected scenario outcomes from real JUnit reports in one Evidence Capsule."
                dependsOn(
                    libraryTests,
                    cliTests,
                    contractTests,
                    project.tasks.named("assurancePlanTest"),
                    familyTests,
                    planValidation,
                    comparison,
                    history,
                )
                workingDir(root.rootDir)
                inputs.files(
                    root.layout.projectDirectory.file("build/assurance/record.py"),
                    root.layout.projectDirectory.file("build/assurance/capsule.py"),
                    root.layout.projectDirectory.file("tests/assurance/plan.json"),
                )
                outputs.file(root.layout.buildDirectory.file("assurance/capsule.json"))
                val arguments =
                    mutableListOf(
                        "python",
                        "build/assurance/record.py",
                        "--repository",
                        root.rootDir.absolutePath,
                        "--plan",
                        root.layout.projectDirectory.file("tests/assurance/plan.json").asFile.absolutePath,
                        "--tier",
                        "full",
                        "--environment",
                        "hosted",
                        "--java",
                        javaLauncher.get().executablePath.asFile.absolutePath,
                        "--reports",
                        project.project(JbsaPublicLibraryIdentity.PROJECT_PATH).layout.buildDirectory
                            .dir("test-results")
                            .get()
                            .asFile
                            .absolutePath,
                        "--reports",
                        project.project(JbsaThinApplicationIdentity.PROJECT_PATH).layout.buildDirectory
                            .dir("test-results")
                            .get()
                            .asFile
                            .absolutePath,
                        "--reports",
                        project.layout.buildDirectory.dir("test-results").get().asFile.absolutePath,
                        "--output",
                        root.layout.buildDirectory.file("assurance/capsule.json").get().asFile.absolutePath,
                    )
                project.providers.gradleProperty("jbsaAssuranceSelection").orNull?.let { selection ->
                    inputs.file(root.file(selection))
                    arguments.addAll(listOf("--selection", root.file(selection).absolutePath))
                }
                commandLine(arguments)
                onlyIf { assuranceTests.asFile.isDirectory }
                // A capsule binds current candidate and report bytes, so it must never reuse stale output.
                outputs.upToDateWhen { false }
            }
        project.tasks.register(JbsaConformanceIdentity.AUTOMATED_ASSURANCE_TASK) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Runs the compact Assurance v2 plan and currently executable family scenarios."
            // Generated selectors span library, CLI, and black-box projects; one task graph executes
            // their owning suites without multiplying a Gradle process per Assurance Scenario.
            dependsOn(capsule)
        }
    }

    /** Resolves an optional generated affected-tier manifest to its owning family test tasks. */
    private fun selectedFamilyTasks(project: Project) =
        project.providers.gradleProperty("jbsaAssuranceSelection").orNull?.let { selectionPath ->
            val selection = project.rootProject.file(selectionPath)
            if (!selection.isFile) {
                throw GradleException("Assurance selection does not exist: ${selection.absolutePath}")
            }
            val document = JsonSlurper().parse(selection) as? Map<*, *>
                ?: throw GradleException("Assurance selection must contain a JSON object.")
            val scenarios = document["assurance_scenarios"] as? List<*>
                ?: throw GradleException("Assurance selection has no Assurance Scenarios.")
            val families =
                scenarios.map { scenario ->
                    (scenario as? Map<*, *>)?.get("family") as? String
                        ?: throw GradleException("Assurance Scenario has no Archive Family.")
                }
            val taskNames =
                families
                    .map { family ->
                        when {
                            family == "tes3" -> "tes3ConformanceTest"
                            family.startsWith("bsa-") -> "bsaConformanceTest"
                            "gnrl" in family -> "ba2ConformanceTest"
                            "dx10" in family -> "ddsConformanceTest"
                            else -> throw GradleException("Unknown Assurance Plan family: $family")
                        }
                    }
                    .distinct()
            taskNames.map { taskName -> project.tasks.named(taskName) }
        } ?: JbsaConformanceIdentity.ARCHIVE_FAMILY_TASKS.map { taskName -> project.tasks.named(taskName) }

}
