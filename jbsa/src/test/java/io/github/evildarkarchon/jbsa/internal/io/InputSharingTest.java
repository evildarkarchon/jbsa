package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.BethesdaArchives;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.Operation;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Qualifies sharing denials against the Windows filesystem, including the public detection path.
 */
@EnabledOnOs(OS.WINDOWS)
final class InputSharingTest {
  @TempDir Path directory;

  /**
   * Read sharing remains available while write, delete, and rename wait for the owned input close.
   */
  @Test
  void deniesMutationUntilInputCloses() throws Exception {
    Path source = directory.resolve("input.bin");
    Path renamed = directory.resolve("renamed.bin");
    Files.write(source, new byte[] {1, 2, 3});
    try (ArchiveInput input = ArchiveInput.open(source, Operation.OPEN)) {
      assertEquals(3, input.size());
      assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(source));
      assertThrows(
          IOException.class,
          () -> {
            try (FileChannel writer = FileChannel.open(source, StandardOpenOption.WRITE)) {
              // Closing an unexpectedly accepted writer avoids leaking it when this assertion
              // fails.
              assertTrue(writer.isOpen());
            }
          });
      assertThrows(IOException.class, () -> Files.delete(source));
      assertThrows(IOException.class, () -> Files.move(source, renamed));
    }
    Files.write(source, new byte[] {4});
    Files.move(source, renamed);
    Files.delete(renamed);
    assertFalse(Files.exists(source));
    assertFalse(Files.exists(renamed));
  }

  /** Detection uses the same deny-write contract and releases its handle before returning. */
  @Test
  void detectionRejectsAnExistingWriterAndReleasesAfterSuccess() throws Exception {
    Path source = directory.resolve("detect.bin");
    Files.write(source, new byte[] {0, 1, 0, 0});
    try (FileChannel writer = FileChannel.open(source, StandardOpenOption.WRITE)) {
      assertTrue(writer.isOpen());
      ArchiveException failure =
          assertThrows(ArchiveException.class, () -> BethesdaArchives.standard().detect(source));
      assertEquals(FailureKind.SOURCE, failure.kind());
      assertEquals(
          "operation.source-io", failure.primaryFailure().diagnosticIdentifier().orElseThrow());
      assertEquals(Operation.DETECT, failure.diagnostics().getFirst().operation());
    }
    assertNotNull(BethesdaArchives.standard().detect(source));
    Files.write(source, new byte[] {9});
    Files.delete(source);
  }
}
