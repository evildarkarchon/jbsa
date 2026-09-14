package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Public SSE BSA packing observations with independent wire and round-trip expectations. */
@EnabledOnOs(OS.WINDOWS)
final class Bsa69PackTest {
  @TempDir Path directory;

  /** Stored output uses version 0x69, zero folder padding, and the SSE automatic flag mask. */
  @Test
  void packsStoredWithTwentyFourByteFolderRecords() throws Exception {
    Path target = directory.resolve("stored.bsa");
    BethesdaArchives.standard()
        .pack(
            request(
                target, PackOptions.standard(), generated("Meshes/A.nif", new byte[] {1, 2, 3})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x69, wire.getInt(4));
    assertEquals(0x83, wire.getInt(12));
    assertEquals(0, wire.getInt(48));
    assertEquals(0, wire.getInt(56));
    assertEquals(90, wire.getInt(80));
    assertArrayEquals(new byte[] {1, 2, 3}, Arrays.copyOfRange(wire.array(), 90, 93));
  }

  /** LZ4-frame output uses the SSE profile and mixed entry toggles round-trip exactly. */
  @Test
  void packsAndReadsLz4FrameAndMixedEntries() throws Exception {
    byte[] compressible = new byte[200_000];
    Arrays.fill(compressible, (byte) 'A');
    Path target = directory.resolve("mixed.bsa");
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.LZ4_FRAME,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC,
            Map.of(new NormalizedNameIdentity("meshes\\b.nif"), PackOptions.Compression.STORED));
    BethesdaArchives.standard()
        .pack(
            request(
                target,
                options,
                generated("meshes/a.nif", compressible),
                generated("meshes/b.nif", new byte[] {7})),
            OperationControl.standard());

    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x87, wire.getInt(12));
    int firstPayload = wire.getInt(80);
    assertEquals(200_000, wire.getInt(firstPayload));
    assertEquals(0x184D2204, wire.getInt(firstPayload + 4));
    assertEquals(0x60, Byte.toUnsignedInt(wire.get(firstPayload + 8)));
    assertEquals(0x70, Byte.toUnsignedInt(wire.get(firstPayload + 9)));

    try (var archive = BethesdaArchives.standard().open(target, OpenOptions.standard())) {
      assertEquals(2, archive.entryCount());
      for (int ordinal = 0; ordinal < 2; ordinal++) {
        var metadata = archive.entry(ordinal).metadata();
        assertEquals(ordinal == 0, ((EntryMetadata.VersionedBsa) metadata.facts()).compressed());
        try (var content = archive.entry(ordinal).openContent()) {
          byte[] actual = Channels.newInputStream(content).readAllBytes();
          assertArrayEquals(ordinal == 0 ? compressible : new byte[] {7}, actual);
        }
      }
    }
  }

  /** SSE rejects zlib and raw LZ4 choices before opening generated payloads. */
  @Test
  void rejectsForeignCodecsBeforeSourceEffects() {
    for (var compression : List.of(PackOptions.Compression.ZLIB, PackOptions.Compression.LZ4_RAW)) {
      var opens = new java.util.concurrent.atomic.AtomicInteger();
      var source =
          new PackSource.GeneratedEntry(
              "meshes/a.nif",
              1,
              () -> {
                opens.incrementAndGet();
                return Channels.newChannel(new ByteArrayInputStream(new byte[] {1}));
              });
      var options =
          new PackOptions(
              List.of(),
              compression,
              false,
              new PackOptions.Splitting.UpToBytes(0),
              FlagSelection.AUTOMATIC,
              FlagSelection.AUTOMATIC);
      assertEquals(
          FailureKind.UNSUPPORTED,
          assertThrows(
                  ArchiveException.class,
                  () ->
                      BethesdaArchives.standard()
                          .pack(
                              request(directory.resolve(compression + ".bsa"), options, source),
                              OperationControl.standard()))
              .kind());
      assertEquals(0, opens.get());
    }
  }

  /** Corrupt, trailing, and wrong-size frames fail lazily with the SSE profile identity. */
  @Test
  void rejectsMalformedLz4FramesAtContentEof() throws Exception {
    Path source = directory.resolve("valid.bsa");
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.LZ4_FRAME,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    BethesdaArchives.standard()
        .pack(
            request(source, options, generated("meshes/a.nif", new byte[100_000])),
            OperationControl.standard());
    byte[] valid = Files.readAllBytes(source);
    int payload = ByteBuffer.wrap(valid).order(ByteOrder.LITTLE_ENDIAN).getInt(80);

    byte[] corrupt = valid.clone();
    corrupt[payload + 4] = 0;
    assertFrameFailure(corrupt, "codec.invalid-data");

    byte[] wrongSize = valid.clone();
    ByteBuffer.wrap(wrongSize)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(
            payload, ByteBuffer.wrap(wrongSize).order(ByteOrder.LITTLE_ENDIAN).getInt(payload) + 1);
    assertFrameFailure(wrongSize, "codec.size-mismatch");

    byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
    ByteBuffer trailingWords = ByteBuffer.wrap(trailing).order(ByteOrder.LITTLE_ENDIAN);
    trailingWords.putInt(76, trailingWords.getInt(76) + 1);
    assertFrameFailure(trailing, "codec.size-mismatch");
  }

  /** Automatic 0x69 flags clear the miscellaneous classification inherited from materials. */
  @Test
  void clearsSseMiscellaneousFileFlag() throws Exception {
    Path target = directory.resolve("flags.bsa");
    BethesdaArchives.standard()
        .pack(
            request(target, PackOptions.standard(), generated("materials/a.bgsm", new byte[] {1})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(3, wire.getInt(12));
    assertEquals(0, wire.getInt(32));
  }

  /** The retained Xbox archive bit remains available through an explicit SSE override. */
  @Test
  void acceptsExplicitXboxArchiveFlag() throws Exception {
    Path target = directory.resolve("xbox-flag.bsa");
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.STORED,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            new FlagSelection.Explicit(0x43),
            FlagSelection.AUTOMATIC);
    BethesdaArchives.standard()
        .pack(
            request(target, options, generated("meshes/a.nif", new byte[] {1})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x43, wire.getInt(12));
  }

  /** Sharing and advisory splitting retain the common writer semantics with 24-byte records. */
  @Test
  void sharesEqualRecordsAndPublishesReadableSplitParts() throws Exception {
    Path shared = directory.resolve("shared.bsa");
    var sharing =
        new PackOptions(
            List.of(),
            PackOptions.Compression.STORED,
            true,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    BethesdaArchives.standard()
        .pack(
            request(
                shared,
                sharing,
                generated("meshes/a.nif", new byte[] {7}),
                generated("meshes/b.nif", new byte[] {7})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(shared)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(wire.getInt(80), wire.getInt(96));

    Path split = directory.resolve("split.bsa");
    var splitting =
        new PackOptions(
            List.of(),
            PackOptions.Compression.STORED,
            false,
            new PackOptions.Splitting.UpToBytes(1),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    var report =
        BethesdaArchives.standard()
            .pack(
                request(
                    split,
                    splitting,
                    generated("meshes/a.nif", new byte[] {7}),
                    generated("meshes/b.nif", new byte[] {8})),
                OperationControl.standard());
    assertEquals(
        List.of("split.bsa", "split2.bsa"),
        report.archiveParts().stream().map(part -> part.path().getFileName().toString()).toList());
    for (var part : report.archiveParts())
      try (var archive = BethesdaArchives.standard().open(part.path(), OpenOptions.standard())) {
        assertEquals(1, archive.entryCount());
      }
  }

  /** LZ4 processing observes cooperative cancellation before publishing an archive. */
  @Test
  void cancelsLz4BeforePublication() {
    Path target = directory.resolve("cancelled.bsa");
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var control =
        new OperationControl(
            snapshot -> {
              if (snapshot.phase() == OperationPhase.PROCESSING) cancelled.set(true);
            },
            cancelled::get);
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.LZ4_FRAME,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    assertThrows(
        ArchiveCancelledException.class,
        () ->
            BethesdaArchives.standard()
                .pack(
                    request(target, options, generated("meshes/a.nif", new byte[200_000])),
                    control));
    assertFalse(Files.exists(target));
  }

  /** Reads a malformed archive through the public lazy content seam and checks stable evidence. */
  private void assertFrameFailure(byte[] bytes, String identifier) throws Exception {
    Path archivePath =
        Files.write(directory.resolve(identifier + "-" + bytes.length + ".bsa"), bytes);
    try (var archive = BethesdaArchives.standard().open(archivePath, OpenOptions.standard());
        var content = archive.entry(0).openContent()) {
      ArchiveException failure =
          assertThrows(
              ArchiveException.class, () -> Channels.newInputStream(content).readAllBytes());
      assertEquals(FailureKind.FORMAT, failure.kind());
      assertEquals(identifier, failure.primaryFailure().diagnosticIdentifier().orElseThrow());
      assertEquals("jbsa-bsa-069-lz4-v1", failure.diagnostics().getFirst().values().get("profile"));
    }
  }

  /** Builds an SSE request through the public immutable pack model. */
  private static PackRequest request(Path target, PackOptions options, PackSource... sources) {
    return new PackRequest(
        target,
        ArchiveFamily.SSE_BSA,
        new ArchiveEncoding(
            Optional.of(new WireVersion(0x69)), Optional.empty(), OptionalLong.empty()),
        Optional.empty(),
        List.of(sources),
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        ResourceLimits.standard(),
        new WorkerSelection.UpTo(1),
        options,
        Optional.empty());
  }

  /** Supplies a fresh channel over synthetic redistributable bytes. */
  private static PackSource generated(String name, byte[] bytes) {
    return new PackSource.GeneratedEntry(
        name, bytes.length, () -> Channels.newChannel(new ByteArrayInputStream(bytes)));
  }
}
