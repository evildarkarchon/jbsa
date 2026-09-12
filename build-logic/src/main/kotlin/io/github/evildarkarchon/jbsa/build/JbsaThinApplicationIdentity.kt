package io.github.evildarkarchon.jbsa.build

/** Stable Gradle identities owned by the thin CLI and its external runtime inputs. */
internal object JbsaThinApplicationIdentity {
    const val PROJECT_PATH = ":jbsa-cli"
    const val DISTRIBUTION_PROJECT_PATH = ":jbsa-dist"
    const val MODULE_NAME = "io.github.evildarkarchon.jbsa.cli"
    const val MAIN_CLASS = "io.github.evildarkarchon.jbsa.cli.Main"
    const val VERIFY_ARTIFACT_TASK = "verifyThinCliArtifact"
    const val VERIFY_APPLICATION_OUTPUTS_TASK = "verifyThinApplicationOutputs"
    const val SMOKE_TEST_TASK = "smokeTestThinCli"
    const val STAGE_RUNTIME_DEPENDENCIES_TASK = "stageRuntimeDependencies"
    const val VERIFY_RUNTIME_DEPENDENCIES_TASK = "verifyRuntimeDependencies"
    const val RUNTIME_CONFIGURATION = "runtimeInputs"
}
