package io.github.evildarkarchon.jbsa.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.create

/** Builds and locally publishes the compatible `io.github.evildarkarchon:jbsa` public library. */
class JbsaPublicLibraryPlugin : Plugin<Project> {
    /** Applies the library, dependency, publication-metadata, and artifact-backed test contracts. */
    override fun apply(project: Project) {
        project.pluginManager.apply("jbsa.java")
        project.pluginManager.apply("maven-publish")

        val java = project.extensions.getByType(JavaPluginExtension::class.java)
        java.withSourcesJar()
        java.withJavadocJar()
        project.tasks.named("compileJava", JavaCompile::class.java) {
            options.javaModuleVersion.set(project.provider { project.version.toString() })
        }
        configureDependencies(project)
        configurePublication(project)
        configureWhiteboxModuleTesting(project)
        configureArtifactBackedTests(project)
        configureArtifactVerification(project)
    }

    /**
     * Generates the test module descriptor required for Gradle module-path inference and patches the
     * existing white-box tests into the production module without relocating their sources.
     */
    private fun configureWhiteboxModuleTesting(project: Project) {
        val generatedSources = project.layout.buildDirectory.dir("generated/sources/testModuleInfo")
        val descriptor = generatedSources.map { it.file("module-info.java") }
        val generateDescriptor =
            project.tasks.register("generateTestModuleInfo") {
                description = "Generates the white-box JPMS descriptor used by the shared test source set."
                outputs.file(descriptor)
                doLast {
                    val output = descriptor.get().asFile
                    output.parentFile.mkdirs()
                    output.writeText(
                        """
                        open module io.github.evildarkarchon.jbsa {
                            requires jdk.unsupported;
                            requires org.junit.jupiter.api;
                            requires org.junit.jupiter.params;
                            requires org.lwjgl;
                            requires org.lwjgl.lz4;
                        }
                        """.trimIndent() + System.lineSeparator(),
                        Charsets.UTF_8,
                    )
                }
            }
        val sourceSets = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
        val mainSourceSet = sourceSets.named("main")
        val testSourceSet = sourceSets.named("test")
        testSourceSet.get().java.srcDir(generatedSources)

        val mainClasses = project.layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath
        project.tasks.named("compileTestJava", JavaCompile::class.java) {
            dependsOn(generateDescriptor)
            options.compilerArgs.addAll(listOf("--patch-module", "io.github.evildarkarchon.jbsa=$mainClasses"))
        }
        val assembledTestModule = project.layout.buildDirectory.dir("classes/java/test-module")
        val assembleTestModule =
            project.tasks.register("assembleTestModule", Sync::class.java) {
                description = "Assembles production and white-box test classes into one inferred JPMS module."
                dependsOn(project.tasks.named("testClasses"))
                from(mainSourceSet.map { it.output }) { exclude("module-info.class") }
                from(testSourceSet.map { it.output })
                into(assembledTestModule)
            }
        project.tasks.withType(Test::class.java).configureEach {
            dependsOn(assembleTestModule)
            testClassesDirs = project.files(assembledTestModule)
            classpath =
                project.files(assembledTestModule) +
                (testSourceSet.get().runtimeClasspath - testSourceSet.get().output - mainSourceSet.get().output)
        }
    }

    /** Declares exact API and runtime classifier semantics from the central version catalog. */
    private fun configureDependencies(project: Project) {
        val catalog = PinnedVersionCatalog.load(project.rootDir.toPath().resolve("gradle/libs.versions.toml"))
        val lwjglVersion = catalog.dependencyVersion("org.lwjgl", "lwjgl")
        val lwjglLz4Version = catalog.dependencyVersion("org.lwjgl", "lwjgl-lz4")
        val junitVersion = catalog.dependencyVersion("org.junit.jupiter", "junit-jupiter")
        project.dependencies.add("api", "org.lwjgl:lwjgl:$lwjglVersion")
        project.dependencies.add("api", "org.lwjgl:lwjgl-lz4:$lwjglLz4Version")
        project.dependencies.add("runtimeOnly", "org.lwjgl:lwjgl:$lwjglVersion:natives-windows")
        project.dependencies.add("runtimeOnly", "org.lwjgl:lwjgl-lz4:$lwjglLz4Version:natives-windows")
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

    /** Generates the parent-free consumer POM and companion artifact publication with exact metadata. */
    private fun configurePublication(project: Project) {
        project.extensions.getByType(PublishingExtension::class.java).apply {
            publications.create<MavenPublication>(JbsaPublicLibraryIdentity.PUBLICATION_NAME) {
                from(project.components.getByName("java"))
                pom {
                    name.set("JBSA archive library")
                    description.set(
                        "Independently authored Java support for Bethesda Archives, informed by the pinned " +
                            "TES5Edit Reference Snapshot at fd1e36020b2b5b6217e553dc0038983146a2e2dd."
                    )
                    url.set("https://github.com/evildarkarchon/jbsa")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("evildarkarchon")
                            name.set("evildarkarchon")
                            url.set("https://github.com/evildarkarchon")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/evildarkarchon/jbsa.git")
                        developerConnection.set("scm:git:ssh://git@github.com/evildarkarchon/jbsa.git")
                        url.set("https://github.com/evildarkarchon/jbsa")
                    }
                }
            }
        }
        project.tasks.withType(GenerateModuleMetadata::class.java).configureEach {
            // Maven parity permits only the binary, sources, Javadocs, and consumer POM.
            enabled = false
        }
        val binary = project.tasks.named("jar", Jar::class.java)
        val sources = project.tasks.named("sourcesJar", Jar::class.java)
        val javadocs = project.tasks.named("javadocJar", Jar::class.java)
        val consumerPom = project.layout.buildDirectory.file(JbsaPublicLibraryIdentity.publicationPath("pom-default.xml"))
        val generatePom = project.tasks.named(JbsaPublicLibraryIdentity.generatePomTaskName())
        project.tasks.register(JbsaPublicLibraryIdentity.ASSEMBLE_PUBLICATION_TASK, Sync::class.java) {
            group = "publishing"
            description = "Assembles the exact four-file local Maven-compatible publication set."
            dependsOn(binary, sources, javadocs, generatePom)
            into(project.layout.buildDirectory.dir(JbsaPublicLibraryIdentity.publicationPath("artifacts")))
            from(binary.flatMap(Jar::getArchiveFile))
            from(sources.flatMap(Jar::getArchiveFile))
            from(javadocs.flatMap(Jar::getArchiveFile))
            from(consumerPom) { rename { "${project.name}-${project.version}.pom" } }
        }
    }

    /** Supplies produced artifact paths to tests and models every producer dependency explicitly. */
    private fun configureArtifactBackedTests(project: Project) {
        val binary = project.tasks.named("jar", Jar::class.java)
        val sources = project.tasks.named("sourcesJar", Jar::class.java)
        val javadocs = project.tasks.named("javadocJar", Jar::class.java)
        val consumerPom = project.layout.buildDirectory.file(JbsaPublicLibraryIdentity.publicationPath("pom-default.xml"))
        val generatePom = project.tasks.named(JbsaPublicLibraryIdentity.generatePomTaskName())

        project.tasks.withType(Test::class.java).configureEach {
            dependsOn(binary)
            systemProperty("jbsa.library.jar", binary.get().archiveFile.get().asFile.absolutePath)
            if (name == "integrationTest") {
                dependsOn(sources, javadocs, generatePom)
                systemProperty("jbsa.library.sourcesJar", sources.get().archiveFile.get().asFile.absolutePath)
                systemProperty("jbsa.library.javadocJar", javadocs.get().archiveFile.get().asFile.absolutePath)
                systemProperty("jbsa.library.consumerPom", consumerPom.get().asFile.absolutePath)
            }
            jvmArgs("--enable-native-access=io.github.evildarkarchon.jbsa,org.lwjgl,org.lwjgl.lz4")
        }
    }

    /** Registers the real packaged-byte compatibility gate used by root verification. */
    private fun configureArtifactVerification(project: Project) {
        val binary = project.tasks.named("jar", Jar::class.java)
        project.tasks.register(
            JbsaPublicLibraryIdentity.VERIFY_ARTIFACT_TASK,
            VerifyPublicLibraryArtifact::class.java,
        ) {
            group = "verification"
            description = "Verifies the public library descriptor, signatures, and class-path usability."
            dependsOn(binary)
            libraryJar.set(binary.flatMap(Jar::getArchiveFile))
            runtimeClasspath.from(project.configurations.named("runtimeClasspath"))
        }
    }
}
