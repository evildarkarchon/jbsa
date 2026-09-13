package io.github.evildarkarchon.jbsa.build

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConfigurationCachePolicyTest {
    /** Verifies compilation, formatting, and ordinary tests remain eligible for measured cache trials. */
    @Test
    fun `accepts intended cache compatible tasks`() {
        assertDoesNotThrow {
            ConfigurationCachePolicy.validate(
                listOf(":jbsa:compileJava", ":jbsa-cli:compileJava", "spotlessCheck", ":jbsa:test")
            )
        }
    }

    /** Verifies evidence, staging, and process tasks fail with one stable actionable diagnostic. */
    @Test
    fun `rejects incompatible evidence staging and process tasks`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                ConfigurationCachePolicy.validate(
                    listOf(
                        "verify",
                        "verifyCompliance",
                        "generateResolvedProductionDependencies",
                        ":jbsa-dist:stageReleaseInputs",
                        ":jbsa-dist:verifyStagedReleaseInputs",
                        ":jbsa-conformance-tests:captureAutomatedConformance",
                        ":jbsa-conformance-tests:automatedConformance",
                        ":jbsa-benchmarks:smokeTestBenchmarkLauncher",
                        ":jbsa-cli:smokeTestThinCli",
                    )
                )
            }

        assertEquals(
            "Configuration cache is not supported for evidence, staging, or external-process tasks: " +
                ":jbsa-benchmarks:smokeTestBenchmarkLauncher, :jbsa-cli:smokeTestThinCli, " +
                ":jbsa-conformance-tests:automatedConformance, " +
                ":jbsa-conformance-tests:captureAutomatedConformance, :jbsa-dist:stageReleaseInputs, " +
                ":jbsa-dist:verifyStagedReleaseInputs, generateResolvedProductionDependencies, verify, " +
                "verifyCompliance. Run them without --configuration-cache.",
            exception.message,
        )
    }
}
