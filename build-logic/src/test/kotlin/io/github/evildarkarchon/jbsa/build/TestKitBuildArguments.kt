package io.github.evildarkarchon.jbsa.build

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Supplies stable TestKit arguments without leaving daemon-locked project caches inside JUnit fixtures. */
internal object TestKitBuildArguments {
    /**
     * Builds arguments for one nested Gradle invocation.
     *
     * @param projectDir fixture directory whose external cache remains stable across its nested builds
     * @param arguments fixture-specific task and option arguments
     * @param verificationOff whether this fixture intentionally bypasses repository checksum metadata
     */
    fun create(projectDir: Path, arguments: Array<out String>, verificationOff: Boolean = true): List<String> = buildList {
        // Tooling API builds always use a daemon; move its lock-bearing cache outside the deletable JUnit fixture.
        add("--project-cache-dir")
        add(projectCache(projectDir).toString())
        if (verificationOff) add("--dependency-verification=off")
        add("--stacktrace")
        addAll(arguments)
    }

    /** Isolates daemon-held Windows cleanup locks per fixture without putting them under @TempDir. */
    private fun projectCache(projectDir: Path): Path {
        val identity = projectDir.toAbsolutePath().normalize().toString().toByteArray(StandardCharsets.UTF_8)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity))
        return Path.of(System.getProperty("java.io.tmpdir"), "jbsa-gradle-testkit-project-caches", digest)
    }
}
