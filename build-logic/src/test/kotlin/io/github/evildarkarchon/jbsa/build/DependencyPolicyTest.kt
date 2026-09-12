package io.github.evildarkarchon.jbsa.build

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DependencyPolicyTest {
    /** Verifies that exact releases and snapshots are accepted for local snapshot builds. */
    @Test
    fun `accepts pinned dependencies permitted by the build candidate`() {
        assertDoesNotThrow { DependencyPolicy.validate("0.1.0-SNAPSHOT", "6.1.3", false) }
        assertDoesNotThrow { DependencyPolicy.validate("0.1.0-SNAPSHOT", "1.0.0-SNAPSHOT", false) }
    }

    /** Verifies rejection of every nondeterministic dependency selector class. */
    @Test
    fun `rejects dynamic changing and release snapshot dependencies`() {
        listOf("6.+", "latest.release", "[6.0,7.0)").forEach { dependencyVersion ->
            assertThrows(IllegalArgumentException::class.java) {
                DependencyPolicy.validate("0.1.0-SNAPSHOT", dependencyVersion, false)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DependencyPolicy.validate("0.1.0-SNAPSHOT", "6.1.3", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DependencyPolicy.validate("1.0.0", "1.0.0-SNAPSHOT", false)
        }
    }
}
