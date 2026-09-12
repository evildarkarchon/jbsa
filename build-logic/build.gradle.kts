plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The included build cannot apply the convention plugin that it is compiling, so bootstrap its lock policy here.
dependencyLocking {
    lockAllConfigurations()
    lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
}

configurations.configureEach {
    // Gradle 9.7.1's Kotlin DSL plugin mixes embedded 2.2.21 and compiler 2.4.0 declarations.
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
    resolutionStrategy.failOnVersionConflict()
}

val centralCatalog = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")
val catalogDependencyPins =
    centralCatalog.libraryAliases
        .map { alias -> centralCatalog.findLibrary(alias).get().get() }
        .map { dependency ->
            "${dependency.module.group}:${dependency.module.name}" to dependency.versionConstraint.requiredVersion
        }
        .toSet()
val catalogOwnedConfigurations =
    setOf(
        "api",
        "implementation",
        "compileOnly",
        "compileOnlyApi",
        "runtimeOnly",
        "annotationProcessor",
        "testImplementation",
        "testCompileOnly",
        "testRuntimeOnly",
        "testAnnotationProcessor",
    )
configurations.configureEach {
    if (name in catalogOwnedConfigurations) {
        withDependencies {
            withType(org.gradle.api.artifacts.ExternalModuleDependency::class.java).forEach { dependency ->
                val requested = "${dependency.group}:${dependency.name}" to dependency.version
                require(requested in catalogDependencyPins) {
                    "Included-build dependency ${requested.first}:${requested.second} must use a pinned catalog entry."
                }
            }
        }
    }
}

gradlePlugin {
    plugins {
        create("jbsaFoundation") {
            id = "jbsa.foundation"
            implementationClass = "io.github.evildarkarchon.jbsa.build.JbsaFoundationPlugin"
        }
        create("jbsaSettings") {
            id = "jbsa.settings"
            implementationClass = "io.github.evildarkarchon.jbsa.build.JbsaSettingsPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("jbsa.repositoryRoot", rootProject.projectDir.parentFile.absolutePath)
}

layout.buildDirectory = layout.projectDirectory.dir("target")
