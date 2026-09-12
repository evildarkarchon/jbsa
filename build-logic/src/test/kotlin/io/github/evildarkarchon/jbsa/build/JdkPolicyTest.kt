package io.github.evildarkarchon.jbsa.build

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JdkPolicyTest {
    /** Verifies that a complete installed Java 25 development kit is accepted. */
    @Test
    fun `accepts Java 25 with a compiler`() {
        assertDoesNotThrow { JdkPolicy.validate("25", true) }
    }

    /** Verifies that a runtime image and the wrong Java release are both rejected. */
    @Test
    fun `rejects missing compiler and non Java 25 runtimes`() {
        assertThrows(IllegalArgumentException::class.java) { JdkPolicy.validate("25", false) }
        assertThrows(IllegalArgumentException::class.java) { JdkPolicy.validate("26", true) }
    }
}
