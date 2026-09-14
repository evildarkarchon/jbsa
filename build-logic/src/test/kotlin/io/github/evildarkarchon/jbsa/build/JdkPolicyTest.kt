package io.github.evildarkarchon.jbsa.build

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JdkPolicyTest {
    /** Verifies every supported Gradle runtime can bootstrap the managed Java 25 toolchain. */
    @Test
    fun `accepts supported Gradle runtimes`() {
        assertDoesNotThrow { JdkPolicy.validateRuntime("17") }
        assertDoesNotThrow { JdkPolicy.validateRuntime("25") }
        assertDoesNotThrow { JdkPolicy.validateRuntime("26") }
    }

    /** Verifies runtimes older than the resolver and malformed version identities are rejected. */
    @Test
    fun `rejects unsupported Gradle runtimes`() {
        assertThrows(IllegalArgumentException::class.java) { JdkPolicy.validateRuntime("16") }
        assertThrows(IllegalArgumentException::class.java) { JdkPolicy.validateRuntime("unknown") }
    }
}
