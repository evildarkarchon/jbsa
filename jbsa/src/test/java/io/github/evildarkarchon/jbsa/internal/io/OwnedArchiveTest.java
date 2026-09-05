package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the accepted public lifetime seam with a test-only stored-entry index. */
final class OwnedArchiveTest {
  @TempDir Path directory;

  /** Encoded metadata, name allocation and per-channel decoded limits fail at admission. */
  @Test
  void enforcesMetadataAndDecodedLimitsBeforeReading() throws Exception {
    Path path = Files.write(directory.resolve("limits.bin"), new byte[] {41, 42});
    ResourceLimits limits = new ResourceLimits(1, 1, 1, 0, 0, 1, 0);
    try (OpenArchive archive = open(path, limits, 2)) {
      ArchiveException failure =
          assertThrows(ArchiveException.class, () -> archive.entry(0).openContent());
      assertEquals(FailureKind.POLICY, failure.kind());
      assertEquals("maxDecodedBytes", failure.diagnostics().getFirst().values().get("field"));
    }
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                OwnedArchive.load(
                    path,
                    limits,
                    Operation.OPEN,
                    builder -> {
                      builder.readMetadata(0, 1);
                      builder.readName(1, 1);
                      throw new AssertionError("Metadata limit must prevent the second read");
                    }));
    assertEquals("maxMetadataBytes", failure.diagnostics().getFirst().values().get("field"));
    assertEquals("2", failure.diagnostics().getFirst().values().get("observed"));
    Files.delete(path);
  }

  /** Empty entries are valid and zero-capacity reads do not manufacture terminal evidence. */
  @Test
  void emptyEntriesValidateOnlyAtTerminalRead() throws Exception {
    Path path = Files.write(directory.resolve("empty.bin"), new byte[0]);
    try (OpenArchive archive = open(path, new ResourceLimits(1, 0, 0, 0, 0, 1, 0), 0);
        EntryContent child = archive.entry(0).openContent()) {
      assertEquals(0, child.read(ByteBuffer.allocate(0)));
      assertTrue(child.assessment().isEmpty());
      assertEquals(-1, child.read(ByteBuffer.allocate(1)));
      assertTrue(child.assessment().isPresent());
    }
  }

  /** Caller interruption closes shared state and invalidates every sibling without cancellation. */
  @Test
  void directInterruptionInvalidatesSiblings() throws Exception {
    Path path = Files.write(directory.resolve("interrupt.bin"), new byte[] {41, 42});
    try (OpenArchive archive = open(path, ResourceLimits.standard(), 2)) {
      EntryContent first = archive.entry(0).openContent();
      EntryContent sibling = archive.entry(0).openContent();
      java.util.concurrent.atomic.AtomicReference<Throwable> observed =
          new java.util.concurrent.atomic.AtomicReference<>();
      Thread reader =
          new Thread(
              () -> {
                Thread.currentThread().interrupt();
                try {
                  first.read(ByteBuffer.allocate(1));
                } catch (Throwable failure) {
                  observed.set(failure);
                }
              });
      reader.start();
      reader.join(5000);
      assertFalse(reader.isAlive());
      assertInstanceOf(java.nio.channels.ClosedByInterruptException.class, observed.get());
      assertFalse(sibling.isOpen());
      assertThrows(ClosedChannelException.class, () -> sibling.read(ByteBuffer.allocate(1)));
    }
  }

  /**
   * Children have independent sequential positions and establish assessment only at terminal EOF.
   */
  @Test
  void ownsLazyChildrenAndKeepsDetachedEvidenceAfterClose() throws Exception {
    Path path = Files.write(directory.resolve("stored.bin"), new byte[] {41, 42});
    OpenArchive archive = open(path, ResourceLimits.standard(), 2);
    ArchiveEntry entry = archive.entry(0);
    EntryContent first = entry.openContent();
    EntryContent second = entry.openContent();
    assertTrue(first.assessment().isEmpty());
    ByteBuffer one = ByteBuffer.allocate(1);
    assertEquals(1, first.read(one));
    assertEquals(41, one.get(0));
    first.close();
    assertTrue(first.assessment().isEmpty());
    ByteBuffer both = ByteBuffer.allocate(2);
    assertEquals(2, second.read(both));
    assertArrayEquals(new byte[] {41, 42}, both.array());
    assertTrue(second.assessment().isEmpty());
    assertEquals(-1, second.read(ByteBuffer.allocate(1)));
    assertEquals(
        new ValidationExtent.Payloads(Set.of(0L)), second.assessment().orElseThrow().extent());
    ArchiveInspection detached = archive.inspection();
    archive.close();
    archive.close();
    assertFalse(second.isOpen());
    assertThrows(ClosedChannelException.class, () -> second.read(ByteBuffer.allocate(1)));
    assertThrows(ClosedChannelException.class, entry::openContent);
    assertEquals(2, entry.metadata().decodedSize());
    assertSame(detached, archive.inspection());
    assertTrue(second.assessment().isPresent());
    Files.delete(path);
  }

  /**
   * Supplies structural metadata directly; this does not parse or certify a TES3 Archive Family.
   */
  private OpenArchive open(Path path, ResourceLimits limits, long length) throws Exception {
    var detection =
        BethesdaArchives.standard()
            .detect(Files.write(directory.resolve("selector"), new byte[] {0, 1, 0, 0}));
    var inspection =
        new ArchiveInspection(
            detection,
            new ArchiveMetadata.Tes3(1, 0, 0),
            new ArchiveAssessment(
                ArchiveDisposition.CONFORMING, new ValidationExtent.Structure(), List.of()));
    return OwnedArchive.load(
        path,
        limits,
        Operation.OPEN,
        builder -> {
          builder.declareEntries(1);
          builder.addStored(
              new EntryMetadata(
                  ArchiveFamily.TES3_BSA,
                  ArchiveEncoding.tes3(),
                  0,
                  "a",
                  Optional.empty(),
                  Map.of(),
                  length,
                  length,
                  new EntryMetadata.Tes3(0, 0, 0, 0)),
              0);
          return inspection;
        });
  }
}
