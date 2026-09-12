package io.github.evildarkarchon.jbsa.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConformanceExitPolicyTest {
    /** Keeps a complete all-pass report distinct from a trustworthy report with non-passing cases. */
    @Test
    fun `classifies expected evidence outcomes`() {
        assertEquals(ConformanceProcessOutcome.PASSED, ConformanceExitPolicy.classify(0))
        assertEquals(ConformanceProcessOutcome.NON_PASSING_CASES, ConformanceExitPolicy.classify(1))
    }

    /** Treats every result outside the documented evidence outcomes as an infrastructure failure. */
    @Test
    fun `classifies infrastructure failures`() {
        assertEquals(ConformanceProcessOutcome.INFRASTRUCTURE_FAILURE, ConformanceExitPolicy.classify(2))
        assertEquals(ConformanceProcessOutcome.INFRASTRUCTURE_FAILURE, ConformanceExitPolicy.classify(17))
    }
}
