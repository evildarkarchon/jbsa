package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Optional read-only local archive comparison; protected payloads stay in ignored build output. */
@EnabledIfSystemProperty(named = "jbsa.ba2.local", matches = "true")
final class Ba2LocalCorpusIT {
  /**
   * Compares every v1 local entry with pinned-oracle extraction without retaining payload bytes.
   */
  @Test
  void readsOptionalLocalGeneralArchivesAgainstOracle() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path corpus = root.resolve("tests/fixtures/local/corpus/archives/fo4-gnrl/v1");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.isDirectory(corpus), "UNAVAILABLE: local v1 corpus");
    Path work = Files.createTempDirectory(root.resolve("target"), "ba2-local-corpus-");
    Path execution = Files.createDirectory(work.resolve("working"));
    try (var paths = Files.list(corpus)) {
      List<Path> archives = paths.filter(Files::isRegularFile).sorted().toList();
      assertFalse(archives.isEmpty());
      for (int index = 0; index < archives.size(); index++) {
        Path source = archives.get(index);
        Path extracted = Files.createDirectory(execution.resolve("oracle-" + index));
        var command =
            new ArrayList<>(
                List.of(
                    "pwsh",
                    "-NoProfile",
                    "-File",
                    root.resolve("build/run-ba2-oracle.ps1").toString(),
                    "-Operation",
                    "unpack",
                    "-InputPath",
                    source.toString(),
                    "-OutputPath",
                    extracted.toString(),
                    "-WorkingDirectory",
                    execution.toString(),
                    "-EvidenceDirectory",
                    work.resolve("evidence-" + index).toString()));
        Process process =
            new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(work.resolve("oracle-" + index + ".log").toFile())
                .start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
          // Stopping the PowerShell wrapper alone leaves its oracle child writing local output.
          var descendants = process.descendants().toList();
          descendants.forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
          assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Wrapper did not terminate");
          for (ProcessHandle child : descendants) child.onExit().get(10, TimeUnit.SECONDS);
        }
        assertTrue(finished, "Local oracle timed out");
        assertEquals(0, process.exitValue(), "Local oracle failed; inspect ignored evidence");
        try (OpenArchive archive =
            BethesdaArchives.standard().open(source, OpenOptions.standard())) {
          assertEquals(ArchiveFamily.FO4_GENERAL_BA2, archive.inspection().metadata().family());
          try (var files = Files.walk(extracted)) {
            assertEquals(archive.entryCount(), files.filter(Files::isRegularFile).count());
          }
          ByteBuffer bytes = ByteBuffer.allocate(65536);
          for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
            ArchiveEntry entry = archive.entry(ordinal);
            Path expected =
                extracted.resolve(entry.metadata().displayName().replace('\\', '/')).normalize();
            assertTrue(expected.startsWith(extracted));
            assertEquals(entry.metadata().decodedSize(), Files.size(expected));
            MessageDigest observed = MessageDigest.getInstance("SHA-256");
            try (EntryContent content = entry.openContent()) {
              while (true) {
                bytes.clear();
                if (content.read(bytes) < 0) break;
                observed.update(bytes.flip());
              }
            }
            MessageDigest oracle = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(expected)) {
              byte[] window = new byte[65536];
              for (int count; (count = input.read(window)) >= 0; ) oracle.update(window, 0, count);
            }
            assertArrayEquals(
                oracle.digest(), observed.digest(), "Payload mismatch at ordinal " + ordinal);
          }
        }
      }
    }
  }
}
