package io.github.evildarkarchon.jbsa.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

/** Builds and exercises the compatible thin `jbsa-cli` modular application. */
class JbsaThinApplicationPlugin : Plugin<Project> {
    /** Applies application identity, dependencies, packaged tests, and artifact verification. */
    override fun apply(project: Project) {
        project.pluginManager.apply("jbsa.java")
        project.pluginManager.apply("application")

        project.extensions.getByType(JavaApplication::class.java).apply {
            mainModule.set(JbsaThinApplicationIdentity.MODULE_NAME)
            mainClass.set(JbsaThinApplicationIdentity.MAIN_CLASS)
        }
        project.tasks.named("compileJava", JavaCompile::class.java) {
            options.javaModuleVersion.set(project.provider { project.version.toString() })
            // The Maven artifact used launch-policy entry points and carried no ModuleMainClass attribute.
            options.javaModuleMainClass.unsetConvention()
        }

        configureDependencies(project)
        disableIncidentalDistributions(project)
        configureApplicationOutputVerification(project)
        configureArtifactBackedTests(project)
        configureArtifactVerification(project)
        configureSmokeTest(project)
    }

    /** Declares the public-library seam and deterministic JUnit runtime. */
    private fun configureDependencies(project: Project) {
        val catalog = PinnedVersionCatalog.load(project.rootDir.toPath().resolve("gradle/libs.versions.toml"))
        val junitVersion = catalog.dependencyVersion("org.junit.jupiter", "junit-jupiter")
        project.dependencies.add(
            "implementation",
            project.dependencies.project(JbsaPublicLibraryIdentity.PROJECT_PATH),
        )
        project.dependencies.add(
            "testImplementation",
            project.dependencies.platform("org.junit:junit-bom:$junitVersion"),
        )
        project.dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter:$junitVersion")
        project.dependencies.add(
            "testRuntimeOnly",
            "org.junit.platform:junit-platform-launcher:$junitVersion",
        )
    }

    /** Prevents application-plugin launchers and distributions from becoming build outputs. */
    private fun disableIncidentalDistributions(project: Project) {
        val prohibitedTasks = setOf("startScripts", "installDist", "distZip", "distTar")
        project.tasks.configureEach {
            if (name in prohibitedTasks) enabled = false
        }
    }

    /** Verifies that disabled application-plugin outputs cannot survive into release inputs. */
    private fun configureApplicationOutputVerification(project: Project) {
        val verification =
            project.tasks.register(
                JbsaThinApplicationIdentity.VERIFY_APPLICATION_OUTPUTS_TASK,
                VerifyThinApplicationOutputs::class.java,
            ) {
                group = "verification"
                description = "Rejects incidental application launchers, install trees, and archives."
                prohibitedOutputs.from(
                    project.fileTree(project.layout.buildDirectory) {
                        include("distributions/**", "install/**", "scripts/**")
                    }
                )
            }
        project.tasks.named("check") { dependsOn(verification) }
    }

    /** Supplies actual packaged artifacts and external runtime inputs to every CLI test process. */
    private fun configureArtifactBackedTests(project: Project) {
        val cliJar = project.tasks.named("jar", Jar::class.java)
        val libraryProject = project.project(JbsaPublicLibraryIdentity.PROJECT_PATH)
        val libraryJar = libraryProject.tasks.named("jar", Jar::class.java)
        val externalRuntime = externalRuntimeArtifacts(project)
        project.tasks.withType(Test::class.java).configureEach {
            dependsOn(cliJar, libraryJar)
            inputs.files(externalRuntime)
            systemProperty("jbsa.cli.jar", cliJar.get().archiveFile.get().asFile.absolutePath)
            systemProperty("jbsa.library.jar", libraryJar.get().archiveFile.get().asFile.absolutePath)
            systemProperty(
                ThinCliLaunchArguments.COUNT_SYSTEM_PROPERTY,
                ThinCliLaunchArguments.JVM_ARGUMENTS.size,
            )
            ThinCliLaunchArguments.JVM_ARGUMENTS.forEachIndexed { index, argument ->
                systemProperty(ThinCliLaunchArguments.systemProperty(index), argument)
            }
            // Test.systemProperty stringifies Provider values, so resolve the locked graph only at execution time.
            doFirst {
                systemProperty(
                    "jbsa.cli.runtimeClasspath",
                    externalRuntime.get().files.joinToString(java.io.File.pathSeparator) { it.absolutePath },
                )
            }
        }
    }

    /** Registers packaged-byte verification for module identity and the retained entry point. */
    private fun configureArtifactVerification(project: Project) {
        val cliJar = project.tasks.named("jar", Jar::class.java)
        project.tasks.register(
            JbsaThinApplicationIdentity.VERIFY_ARTIFACT_TASK,
            VerifyThinCliArtifact::class.java,
        ) {
            group = "verification"
            description = "Verifies the thin CLI descriptor, version, entry point, and archive identity."
            dependsOn(cliJar)
            artifact.set(cliJar.flatMap(Jar::getArchiveFile))
            expectedVersion.set(project.provider { project.version.toString() })
            launchPolicy.set(project.rootProject.layout.projectDirectory.file("build/windows-runtime/launch-policy.json"))
        }
    }

    /** Launches the produced thin JAR with the preserved module and native-access arguments. */
    private fun configureSmokeTest(project: Project) {
        val cliJar = project.tasks.named("jar", Jar::class.java)
        val libraryJar =
            project
                .project(JbsaPublicLibraryIdentity.PROJECT_PATH)
                .tasks
                .named("jar", Jar::class.java)
        val externalRuntime = externalRuntimeArtifacts(project)
        project.tasks.register(JbsaThinApplicationIdentity.SMOKE_TEST_TASK, JavaExec::class.java) {
            group = "verification"
            description = "Launches the packaged thin CLI with the retained JPMS and native arguments."
            dependsOn(cliJar, libraryJar)
            classpath(project.files(cliJar.flatMap(Jar::getArchiveFile), libraryJar.flatMap(Jar::getArchiveFile)))
            classpath(externalRuntime)
            modularity.inferModulePath.set(true)
            mainModule.set(JbsaThinApplicationIdentity.MODULE_NAME)
            mainClass.set(JbsaThinApplicationIdentity.MAIN_CLASS)
            jvmArgs(ThinCliLaunchArguments.JVM_ARGUMENTS)
            args("--version")
        }
    }

    /** Returns only external artifacts from the CLI runtime graph, excluding both project JARs. */
    private fun externalRuntimeArtifacts(project: Project) =
        project.configurations.named("runtimeClasspath").map { configuration ->
            ExternalRuntimeArtifacts.from(configuration)
        }
}
