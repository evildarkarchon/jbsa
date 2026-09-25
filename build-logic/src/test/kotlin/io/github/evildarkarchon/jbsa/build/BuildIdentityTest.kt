package io.github.evildarkarchon.jbsa.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BuildIdentityTest {
    /** Verifies the exact local default and representative explicit release candidates. */
    @Test
    fun `accepts the default and explicit candidate versions`() {
        assertEquals("0.1.0-SNAPSHOT", BuildIdentity.validateVersion("0.1.0-SNAPSHOT"))
        assertEquals("1.2.3", BuildIdentity.validateVersion("1.2.3"))
        assertEquals("2.0.0-rc.1", BuildIdentity.validateVersion("2.0.0-rc.1"))
    }

    /** Verifies that ambiguous or repository-selector-like versions cannot identify a build. */
    @Test
    fun `rejects invalid candidate versions`() {
        listOf("", "1.2", "1.2.+", "latest.release", " 1.2.3", "1.2.3 ", "1.2.3+local")
            .forEach { candidate ->
                assertThrows(IllegalArgumentException::class.java) {
                    BuildIdentity.validateVersion(candidate)
                }
            }
    }
}
