import org.cyclonedx.gradle.CyclonedxDirectTask

plugins {
    id("jbsa.foundation")
    alias(libs.plugins.cyclonedx)
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
