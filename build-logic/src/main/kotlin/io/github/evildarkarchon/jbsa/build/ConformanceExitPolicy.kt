package io.github.evildarkarchon.jbsa.build

/** Public meanings assigned to the Automated Conformance runner's process exit status. */
internal enum class ConformanceProcessOutcome {
    PASSED,
    NON_PASSING_CASES,
    INFRASTRUCTURE_FAILURE,
}

/** Preserves the PowerShell runner's distinction between case evidence and broken automation. */
internal object ConformanceExitPolicy {
    /** Classifies a process exit without collapsing trustworthy non-passing evidence into infrastructure failure. */
    fun classify(exitCode: Int): ConformanceProcessOutcome =
        when (exitCode) {
            0 -> ConformanceProcessOutcome.PASSED
            1 -> ConformanceProcessOutcome.NON_PASSING_CASES
            else -> ConformanceProcessOutcome.INFRASTRUCTURE_FAILURE
        }
}
