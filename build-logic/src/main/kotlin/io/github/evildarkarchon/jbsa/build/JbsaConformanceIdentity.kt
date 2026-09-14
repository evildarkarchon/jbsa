package io.github.evildarkarchon.jbsa.build

/** Stable Gradle identities owned by the build-only Automated Conformance project. */
internal object JbsaConformanceIdentity {
    const val PROJECT_PATH = ":jbsa-conformance-tests"
    const val CAPTURE_TASK = "captureAutomatedConformance"
    const val AUTOMATED_TASK = "automatedConformance"
    const val AUTOMATED_ASSURANCE_TASK = "automatedAssurance"
    const val EXIT_CODE_FILE = "conformance-exit-code.txt"
    const val EVIDENCE_DIRECTORY = "conformance"
    val TAGGED_TESTS =
        linkedMapOf(
            "architectureTest" to "architecture",
            "buildPolicyTest" to "build-policy",
            "conformanceHarnessTest" to "conformance-harness",
            "assurancePlanTest" to "assurance-plan",
            "tes3ConformanceTest" to "tes3",
            "bsaConformanceTest" to "bsa",
            "ba2ConformanceTest" to "ba2",
            "ddsConformanceTest" to "dds",
            "performanceHarnessTest" to "performance-harness",
        )
    val ARCHIVE_FAMILY_TASKS =
        TAGGED_TESTS.filterValues { tag -> tag in setOf("tes3", "bsa", "ba2") }.keys
}
