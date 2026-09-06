package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Qualifies existing drive-root extraction using a temporary user-scoped DOS drive mapping. */
class PublicationDriveRootTest {
  @TempDir Path directory;

  /** A filesystem root is an existing tree even though it has no parent for adjacent staging. */
  @Test
  void publishesAtAnExistingDriveRoot() throws Exception {
    String drive = null;
    for (char letter = 'Z'; letter >= 'D'; letter--) {
      if (!Files.exists(Path.of(letter + ":\\"))) {
        drive = letter + ":";
        break;
      }
    }
    assertNotNull(drive, "A free drive letter is required for the Windows root fixture");
    assertEquals(0, new ProcessBuilder("subst.exe", drive, directory.toString()).start().waitFor());
    try {
      Path root = Path.of(drive + "\\");
      var artifacts =
          PublicationTransaction.extract(
              root,
              List.of(
                  new PublicationTransaction.Entry(
                      "file.txt", output -> output.write(0, ByteBuffer.wrap(new byte[] {7})))),
              TargetPolicy.FAIL,
              ResourceLimits.standard(),
              OperationControl.standard());
      assertArrayEquals(new byte[] {7}, Files.readAllBytes(directory.resolve("file.txt")));
      assertTrue(
          artifacts.contains(new Artifact(root.resolve("file.txt"), 0, ArtifactState.PUBLISHED)));
      try (var contents = Files.list(directory)) {
        assertEquals(1, contents.count());
      }
    } finally {
      assertEquals(0, new ProcessBuilder("subst.exe", drive, "/D").start().waitFor());
    }
  }
}
