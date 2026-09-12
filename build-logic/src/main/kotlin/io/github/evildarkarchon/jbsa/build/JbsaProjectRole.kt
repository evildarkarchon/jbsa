package io.github.evildarkarchon.jbsa.build

/** Stable build roles retained while Maven and Gradle are compared on the migration branch. */
internal enum class JbsaProjectRole(val id: String) {
    ROOT_AGGREGATOR("root-aggregator"),
    PUBLIC_LIBRARY("public-library"),
    THIN_APPLICATION("thin-application"),
    BUILD_ONLY_TEST_SUPPORT("build-only-test-support"),
    BUILD_ONLY_CONFORMANCE("build-only-conformance"),
    BUILD_ONLY_BENCHMARKS("build-only-benchmarks"),
    NON_JAVA_STAGING_AUDIT("non-java-staging-audit"),
}
