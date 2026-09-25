package io.github.evildarkarchon.jbsa.build

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
     * @throws IOException if the external project cache cannot be created or canonicalized
     */
    fun create(projectDir: Path, arguments: Array<out String>, verificationOff: Boolean = true): List<String> = buildList {
        // Tooling API builds always use a daemon; move its lock-bearing cache outside the deletable JUnit fixture.
        add("--project-cache-dir")
        add(projectCache(projectDir).toString())
        if (verificationOff) add("--dependency-verification=off")
        add("--stacktrace")
        addAll(arguments)
    }

    /**
     * Creates a canonical external cache per fixture. Gradle's Windows lock manager canonicalizes
     * its open lock path, so an 8.3 temp-path alias can make cache initialization delete that lock.
     *
     * @throws IOException if the cache cannot be created or canonicalized
     */
    private fun projectCache(projectDir: Path): Path {
        val identity = projectDir.toAbsolutePath().normalize().toString().toByteArray(StandardCharsets.UTF_8)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity))
        val cache = Path.of(System.getProperty("java.io.tmpdir"), "jbsa-gradle-testkit-project-caches", digest)
        Files.createDirectories(cache)
        return cache.toFile().canonicalFile.toPath()
    }
}
