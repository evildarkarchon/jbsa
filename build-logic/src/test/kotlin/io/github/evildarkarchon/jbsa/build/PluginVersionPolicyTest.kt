package io.github.evildarkarchon.jbsa.build

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PluginVersionPolicyTest {
    /** Verifies that core and included convention plugins remain versionless by Gradle design. */
    @Test
    fun `accepts core included and externally pinned plugins`() {
        assertDoesNotThrow { PluginVersionPolicy.validate("base", null) }
        assertDoesNotThrow { PluginVersionPolicy.validate("jbsa.foundation", null) }
        assertDoesNotThrow { PluginVersionPolicy.validate("com.diffplug.spotless", "8.10.2") }
    }

    /** Verifies that every external plugin request carries an explicit immutable version. */
    @Test
    fun `rejects unpinned external plugins`() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginVersionPolicy.validate("com.diffplug.spotless", null)
        }
    }
}
