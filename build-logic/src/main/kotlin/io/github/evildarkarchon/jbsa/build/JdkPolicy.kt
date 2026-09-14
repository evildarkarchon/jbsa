package io.github.evildarkarchon.jbsa.build

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainSpec
import org.gradle.jvm.toolchain.JvmVendorSpec

/** Validates that the Gradle runtime can load the managed Java 25 toolchain resolver. */
internal object JdkPolicy {
    /**
     * Applies the single Java language and vendor request used by every toolchain consumer.
     *
     * @param spec toolchain request to bind to Java 25 from Eclipse Adoptium
     */
    fun configureToolchain(spec: JavaToolchainSpec) {
        spec.languageVersion.set(JavaLanguageVersion.of(25))
        spec.vendor.set(JvmVendorSpec.ADOPTIUM)
    }

    /**
     * Validates the Gradle runtime major version.
     *
     * @throws IllegalArgumentException when the major version is non-numeric or older than 17
     */
    fun validateRuntime(majorVersion: String) {
        val parsed = majorVersion.toIntOrNull()
        require(parsed != null && parsed >= 17) {
            "JBSA requires Java 17 or newer to run Gradle and provision its Temurin Java 25 toolchain; " +
                "running Java $majorVersion."
        }
    }
}
