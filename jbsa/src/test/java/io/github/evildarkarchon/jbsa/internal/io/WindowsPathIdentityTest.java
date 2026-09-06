package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Exercises Windows extraction containment against real operating-system handles. */
@EnabledOnOs(OS.WINDOWS)
final class WindowsPathIdentityTest {
  @TempDir Path directory;

  /** An open pin blocks directory replacement until its owner releases it. */
  @Test
  void pinsDirectoryUntilClosed() throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    Path moved = directory.resolve("moved");
    try (WindowsPathIdentity.Pin pin = WindowsPathIdentity.pin(root)) {
      assertTrue(pin.snapshot().directory());
      assertFalse(pin.snapshot().indirection());
      assertEquals(pin.snapshot().identity(), WindowsPathIdentity.inspect(root).identity());
      assertThrows(IOException.class, () -> Files.move(root, moved));
    }
    Files.move(root, moved);
    assertTrue(Files.isDirectory(moved));
  }

  /** Matching content and timestamps cannot disguise a different entry occupying the same name. */
  @Test
  void distinguishesReplacementAndAbsence() throws Exception {
    Path file = Files.writeString(directory.resolve("entry.txt"), "same");
    var original = WindowsPathIdentity.inspect(file);
    var modified = Files.getLastModifiedTime(file);
    Files.move(file, directory.resolve("original.txt"));
    assertNull(WindowsPathIdentity.inspect(file));
    Files.writeString(file, "same");
    Files.setLastModifiedTime(file, modified);
    var replacement = WindowsPathIdentity.inspect(file);
    assertNotEquals(original.identity(), replacement.identity());
    assertTrue(replacement.regular());
    assertFalse(replacement.directory());
  }

  /** A junction is identified as the reparse entry, including when its target has disappeared. */
  @Test
  void inspectsJunctionWithoutFollowingIt() throws Exception {
    Path target = Files.createDirectory(directory.resolve("target"));
    Path junction = directory.resolve("junction");
    Process process =
        new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), target.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor(), output);
    try {
      var snapshot = WindowsPathIdentity.inspect(junction);
      assertTrue(snapshot.indirection());
      assertFalse(snapshot.regular());
      assertNotEquals(WindowsPathIdentity.inspect(target).identity(), snapshot.identity());
      Files.delete(target);
      assertEquals(snapshot.identity(), WindowsPathIdentity.inspect(junction).identity());
    } finally {
      Files.delete(junction);
    }
  }
}
