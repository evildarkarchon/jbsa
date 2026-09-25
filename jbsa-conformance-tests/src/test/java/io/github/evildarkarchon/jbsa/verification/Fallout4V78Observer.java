package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Shared local evidence paths, oracle availability, and observer-process lifetime policy. */
final class Fallout4V78Observer {
  private Fallout4V78Observer() {}

  /** Requires local-only execution and the exact digest-pinned Conformance Oracle input. */
  static void assumeLocalOracle() {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.isRegularFile(root().resolve("tests/fixtures/local/oracle/BSArch.exe")));
  }

  /** Owns one independent observer process through completion and deadline cleanup. */
  static void run(List<String> command, Path log, long timeoutSeconds) throws Exception {
    Files.createDirectories(log.getParent());
    Process process =
        new ProcessBuilder(new ArrayList<>(command))
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS), "Observer timed out: " + log);
      assertEquals(0, process.exitValue(), () -> "Observer failed; see " + log);
    } finally {
      // A failed assertion or timeout must not leave a build-owned observer running.
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  /** Resolves committed evidence and tools from the configured reactor root. */
  static Path root() {
    return Path.of(System.getProperty("jbsa.reactor.root"));
  }
}
