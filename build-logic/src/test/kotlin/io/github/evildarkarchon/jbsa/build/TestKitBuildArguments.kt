package io.github.evildarkarchon.jbsa.build

import java.nio.file.Path

/** Supplies stable TestKit arguments without leaving daemon-locked project caches inside JUnit fixtures. */
internal object TestKitBuildArguments {
    private val sharedProjectCache =
        Path.of(System.getProperty("java.io.tmpdir"), "jbsa-gradle-testkit-project-cache").toAbsolutePath()

    /**
     * Builds arguments for one nested Gradle invocation.
     *
     * @param arguments fixture-specific task and option arguments
     * @param verificationOff whether this fixture intentionally bypasses repository checksum metadata
     */
    fun create(arguments: Array<out String>, verificationOff: Boolean = true): List<String> = buildList {
        // Tooling API builds always use a daemon; move its lock-bearing cache outside the deletable JUnit fixture.
        add("--project-cache-dir")
        add(sharedProjectCache.toString())
        if (verificationOff) add("--dependency-verification=off")
        add("--stacktrace")
        addAll(arguments)
    }
}
