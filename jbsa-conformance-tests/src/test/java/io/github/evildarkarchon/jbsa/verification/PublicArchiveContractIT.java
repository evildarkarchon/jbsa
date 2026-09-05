package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the Contract Baseline through the same public interface as an embedded consumer. */
@Tag("contract")
final class PublicArchiveContractIT {
  @TempDir Path directory;

  /** Bounded detection identifies selectors without claiming structural validation. */
  @Test
  void detectsTes3RegardlessOfExtension() throws Exception {
    Path archive = directory.resolve("input.txt");
    Files.write(archive, new byte[] {0, 1, 0, 0});
    BethesdaArchives archives = BethesdaArchives.standard();
    assertSame(archives, BethesdaArchives.standard());
    ArchiveDetection detection = archives.detect(archive);
    assertEquals(DetectionStatus.SUPPORTED_FAMILY, detection.status());
    assertEquals(ArchiveFamily.TES3_BSA, detection.family().orElseThrow());
    assertTrue(detection.wireVersion().isEmpty());
  }

  /**
   * Recognition retains supported, unknown, and incomplete selectors without interpreting indexes.
   */
  @Test
  void distinguishesRecognitionOutcomesAndWireVersions() throws Exception {
    assertStatus(new byte[0], DetectionStatus.UNRECOGNIZED);
    assertStatus(new byte[] {1, 2, 3}, DetectionStatus.UNRECOGNIZED);
    assertStatus(new byte[] {66, 83}, DetectionStatus.INDETERMINATE);
    assertStatus(new byte[] {66, 83, 65, 0, 0x67}, DetectionStatus.INDETERMINATE);
    assertStatus(new byte[] {66, 83, 65, 0, 0x66, 0, 0, 0}, DetectionStatus.UNSUPPORTED_VARIANT);
    for (long version : new long[] {1, 7, 8}) {
      byte[] bytes =
          java.nio.ByteBuffer.allocate(12)
              .order(java.nio.ByteOrder.LITTLE_ENDIAN)
              .put(new byte[] {66, 84, 68, 88})
              .putInt((int) version)
              .put(new byte[] {71, 78, 82, 76})
              .array();
      ArchiveDetection result = assertStatus(bytes, DetectionStatus.SUPPORTED_FAMILY);
      assertEquals(version, result.wireVersion().orElseThrow().value());
      assertEquals(ArchiveFamily.FO4_GENERAL_BA2, result.family().orElseThrow());
    }
    byte[] starfield =
        java.nio.ByteBuffer.allocate(36)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(new byte[] {66, 84, 68, 88})
            .putInt(3)
            .put(new byte[] {68, 88, 49, 48})
            .putInt(0)
            .putLong(0)
            .putLong(1)
            .putInt(3)
            .array();
    assertEquals(
        3,
        assertStatus(starfield, DetectionStatus.SUPPORTED_FAMILY)
            .compressionMethod()
            .orElseThrow());
    starfield[32] = 4;
    assertEquals(
        4,
        assertStatus(starfield, DetectionStatus.UNSUPPORTED_VARIANT)
            .compressionMethod()
            .orElseThrow());
  }

  /** Both inspection overloads preserve baseline capability failure; source I/O remains checked. */
  @Test
  void inspectionDefaultsAndSourceFailuresAreStructured() throws Exception {
    Path path = directory.resolve("missing.bsa");
    BethesdaArchives library = BethesdaArchives.standard();
    ArchiveException io = assertThrows(ArchiveException.class, () -> library.detect(path));
    assertEquals(FailureKind.SOURCE, io.kind());
    assertInstanceOf(java.nio.file.NoSuchFileException.class, io.getCause());
    ArchiveException implicit = assertThrows(ArchiveException.class, () -> library.inspect(path));
    ArchiveException explicit =
        assertThrows(ArchiveException.class, () -> library.inspect(path, OpenOptions.standard()));
    assertEquals(FailureKind.CAPABILITY, implicit.kind());
    assertEquals(implicit.primaryFailure(), explicit.primaryFailure());
    assertTrue(implicit.assessment().isEmpty());
    assertTrue(implicit.artifacts().isEmpty());
    assertThrows(NullPointerException.class, () -> library.detect(null));
    assertThrows(NullPointerException.class, () -> library.open(path, null));
  }

  /**
   * Writes only a tiny selector fixture and compares recognition with literal specification facts.
   */
  private ArchiveDetection assertStatus(byte[] bytes, DetectionStatus expected) throws Exception {
    Path path = directory.resolve("selector.bin");
    Files.write(path, bytes);
    ArchiveDetection result = BethesdaArchives.standard().detect(path);
    assertEquals(expected, result.status());
    assertArrayEquals(bytes, result.observedPrefix().bytes());
    return result;
  }
}
