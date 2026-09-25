pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("jbsa.settings")
}

rootProject.name = "jbsa-parent"

include(
    "jbsa",
    "jbsa-cli",
    "jbsa-test-support",
    "jbsa-conformance-tests",
    "jbsa-benchmarks",
    "jbsa-dist",
)
