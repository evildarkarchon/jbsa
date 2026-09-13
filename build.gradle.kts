import org.cyclonedx.gradle.CyclonedxDirectTask

plugins {
    id("jbsa.foundation")
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.spotless)
}

spotless {
    encoding("UTF-8")
    java {
        target("jbsa*/src/**/*.java")
        googleJavaFormat(libs.versions.google.java.format.get())
        formatAnnotations()
        removeUnusedImports()
    }
}

project(":jbsa").tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs = listOf("runtimeClasspath")
    includeMetadataResolution = false
    includeBomSerialNumber = false
    includeBuildSystem = false
    xmlOutput.unsetConvention()
}

tasks.named("verify") {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
