package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Canonical General BA2 output observed through the public packing boundary. */
@EnabledOnOs(OS.WINDOWS)
class Ba2PackTest {
  @TempDir Path temporary;

  /** Canonical metadata and payload order retain the caller's spelling and insertion order. */
  @Test
  void writesStoredRecordsInLogicalOrderWithCasePreserved() throws Exception {
    Path target = temporary.resolve("stored.ba2");
    BethesdaArchives.standard()
        .pack(
            request(
                target,
                options(PackOptions.Compression.STORED, false, 0),
                generated("Textures/Z.DDS", new byte[] {1, 2, 3}),
                generated("Meshes/A.NIF", new byte[] {4})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x58445442, wire.getInt(0));
    assertEquals(1, wire.getInt(4));
    assertEquals(0x4c524e47, wire.getInt(8));
    assertEquals(2, wire.getInt(12));
    assertEquals(100, wire.getLong(16));
    assertEquals(96, wire.getLong(40));
    assertEquals(0, wire.getInt(48));
    assertEquals(3, wire.getInt(52));
    assertEquals(0xbaadf00d, wire.getInt(56));
    assertEquals(1, wire.get(37));
    assertEquals(16, wire.getShort(38));
    assertArrayEquals(new byte[] {1, 2, 3, 4}, Arrays.copyOfRange(wire.array(), 96, 100));
    assertEquals(
        "Textures/Z.DDS",
        new String(wire.array(), 102, 14, java.nio.charset.StandardCharsets.US_ASCII));
  }

  /** Equal decoded bytes share the earliest representation even across entry codec overrides. */
  @Test
  void sharesEarliestCompressedRepresentationAndRoundTripsAnExpandingPayload() throws Exception {
    Path target = temporary.resolve("shared.ba2");
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            true,
            new PackOptions.Splitting.FamilyDefault(),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC,
            Map.of(new NormalizedNameIdentity("data\\second.bin"), PackOptions.Compression.STORED));
    BethesdaArchives.standard()
        .pack(
            request(
                target,
                options,
                generated("Data/First.bin", new byte[] {42}),
                generated("Data/Second.bin", new byte[] {42})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertTrue(wire.getInt(48) > 1);
    assertEquals(wire.getLong(40), wire.getLong(76));
    assertEquals(wire.getInt(48), wire.getInt(84));
    try (var archive = BethesdaArchives.standard().open(target, OpenOptions.standard())) {
      for (int i = 0; i < 2; i++) {
        try (var channel = archive.entry(i).openContent()) {
          assertArrayEquals(new byte[] {42}, Channels.newInputStream(channel).readAllBytes());
        }
      }
    }
  }

  /** Split siblings remain independent and overlays retain the first logical insertion slot. */
  @Test
  void overlaysArchiveSourcesThenSplitsWholeEntries() throws Exception {
    Path source = temporary.resolve("source.ba2"), target = temporary.resolve("parts.ba2");
    BethesdaArchives.standard()
        .pack(
            request(
                source,
                options(PackOptions.Compression.STORED, false, 0),
                generated("Data/A.bin", new byte[] {1}),
                generated("Data/B.bin", new byte[] {2})),
            OperationControl.standard());
    var report =
        BethesdaArchives.standard()
            .pack(
                request(
                    target,
                    options(PackOptions.Compression.ZLIB, true, 1),
                    new PackSource.DetectedPath(source),
                    generated("DATA/A.BIN", new byte[] {9})),
                OperationControl.standard());
    assertEquals(2, report.archiveParts().size());
    try (var archive = BethesdaArchives.standard().open(target, OpenOptions.standard());
        var content = archive.entry(0).openContent()) {
      assertEquals("DATA\\A.BIN", archive.entry(0).metadata().displayName());
      assertArrayEquals(new byte[] {9}, Channels.newInputStream(content).readAllBytes());
    }
    try (var archive =
        BethesdaArchives.standard()
            .open(report.archiveParts().get(1).path(), OpenOptions.standard())) {
      assertEquals("Data\\B.bin", archive.entry(0).metadata().displayName());
    }
  }

  /** Invalid canonical names and unresolved hash-only sources fail before destination effects. */
  @Test
  void rejectsInvalidNamesWithoutOpeningGeneratedPayloads() throws Exception {
    for (String name :
        List.of("root.bin", "Data/caf\u00e9.bin", "../Data/a.bin", "Data/" + "a".repeat(65536))) {
      var opens = new java.util.concurrent.atomic.AtomicInteger();
      Path target = temporary.resolve("invalid.ba2");
      var source =
          new PackSource.GeneratedEntry(
              name,
              0,
              () -> {
                opens.incrementAndGet();
                return Channels.newChannel(new ByteArrayInputStream(new byte[0]));
              });
      assertThrows(
          ArchiveException.class,
          () ->
              BethesdaArchives.standard()
                  .pack(
                      request(target, options(PackOptions.Compression.STORED, false, 0), source),
                      OperationControl.standard()));
      assertEquals(0, opens.get());
      assertFalse(Files.exists(target));
    }
    Path source = temporary.resolve("nameless.ba2"), target = temporary.resolve("repacked.ba2");
    BethesdaArchives.standard()
        .pack(
            request(
                source,
                options(PackOptions.Compression.STORED, false, 0),
                generated("Data/a.bin", new byte[] {1})),
            OperationControl.standard());
    byte[] bytes = Files.readAllBytes(source);
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(16, 0);
    Files.write(source, bytes);
    assertThrows(
        ArchiveException.class,
        () ->
            BethesdaArchives.standard()
                .pack(
                    request(
                        target,
                        options(PackOptions.Compression.STORED, false, 0),
                        new PackSource.DetectedPath(source)),
                    OperationControl.standard()));
    assertFalse(Files.exists(target));
  }

  /** Every part independently preserves a selected zlib encoding for even an empty entry. */
  @Test
  void writesEmptyCompressedPayloadAndStoredFirstSharing() throws Exception {
    Path target = temporary.resolve("empty.ba2");
    var selected =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            true,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC,
            Map.of(new NormalizedNameIdentity("data\\stored.bin"), PackOptions.Compression.STORED));
    BethesdaArchives.standard()
        .pack(
            request(
                target,
                selected,
                generated("Data/Empty.bin", new byte[0]),
                generated("Data/Stored.bin", new byte[] {7}),
                generated("Data/Equal.bin", new byte[] {7})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertTrue(wire.getInt(48) > 0);
    assertEquals(0, wire.getInt(52));
    assertEquals(0, wire.getInt(84));
    assertEquals(0, wire.getInt(120));
    assertEquals(wire.getLong(76), wire.getLong(112));
    try (var archive = BethesdaArchives.standard().open(target, OpenOptions.standard());
        var content = archive.entry(0).openContent()) {
      assertEquals(-1, content.read(ByteBuffer.allocate(1)));
    }
  }

  /** BSA-only flag fields cannot be silently discarded by a General BA2 writer. */
  @Test
  void rejectsFlagsWithoutOpeningPayloads() throws Exception {
    for (boolean archiveFlag : List.of(true, false)) {
      var opens = new java.util.concurrent.atomic.AtomicInteger();
      Path target = temporary.resolve("flags.ba2");
      var selected =
          new PackOptions(
              List.of(),
              PackOptions.Compression.STORED,
              false,
              new PackOptions.Splitting.FamilyDefault(),
              archiveFlag ? new FlagSelection.Explicit(0) : FlagSelection.AUTOMATIC,
              archiveFlag ? FlagSelection.AUTOMATIC : new FlagSelection.Explicit(0));
      var source =
          new PackSource.GeneratedEntry(
              "Data/a.bin",
              0,
              () -> {
                opens.incrementAndGet();
                return Channels.newChannel(new ByteArrayInputStream(new byte[0]));
              });
      assertThrows(
          ArchiveException.class,
          () ->
              BethesdaArchives.standard()
                  .pack(request(target, selected, source), OperationControl.standard()));
      assertEquals(0, opens.get());
      assertFalse(Files.exists(target));
    }
  }

  /**
   * ACP932's ASCII-byte aliases qualify by encoded bytes and cannot create duplicate wire names.
   */
  @Test
  void qualifiesActiveAnsiEncodedBytesBeforePayloadAccess() throws Throwable {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        System.getProperty("os.name").startsWith("Windows"));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Ba2PackTest.class.getModule().isNativeAccessEnabled());
    try (var arena = java.lang.foreign.Arena.ofConfined()) {
      var lookup = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", arena);
      var function =
          java.lang.foreign.Linker.nativeLinker()
              .downcallHandle(
                  lookup.find("GetACP").orElseThrow(),
                  java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT));
      org.junit.jupiter.api.Assumptions.assumeTrue(
          (int) function.invokeExact() == 932,
          "Requires the real Windows ACP932 environment; the test never changes the machine ACP");
    }
    Path target = temporary.resolve("ansi.ba2");
    var selected = options(PackOptions.Compression.STORED, false, 0);
    BethesdaArchives.standard()
        .pack(
            withActiveAnsi(request(target, selected, generated("Data/\u203e.bin", new byte[] {3}))),
            OperationControl.standard());
    try (var archive = BethesdaArchives.standard().open(target, OpenOptions.standard())) {
      assertEquals("Data\\~.bin", archive.entry(0).metadata().displayName());
    }
    for (String alias : List.of("Data/\u203e.bin", "Data/\u00a5..\u00a5evil.bin")) {
      var opens = new java.util.concurrent.atomic.AtomicInteger();
      var source =
          new PackSource.GeneratedEntry(
              alias,
              0,
              () -> {
                opens.incrementAndGet();
                return Channels.newChannel(new ByteArrayInputStream(new byte[0]));
              });
      Path rejected = temporary.resolve("alias.ba2");
      assertThrows(
          ArchiveException.class,
          () ->
              BethesdaArchives.standard()
                  .pack(
                      withActiveAnsi(
                          request(
                              rejected, selected, generated("Data/~.bin", new byte[0]), source)),
                      OperationControl.standard()));
      assertEquals(0, opens.get());
      assertFalse(Files.exists(rejected));
    }
  }

  /** Selects only the public profile that snapshots the actual Windows ANSI code page. */
  private static PackRequest withActiveAnsi(PackRequest request) {
    return new PackRequest(
        request.destination(),
        request.family(),
        request.encoding(),
        Optional.of(CompatibilityProfile.BSARCH_1_0_V1),
        request.sources(),
        request.targetPolicy(),
        request.diagnosticPolicy(),
        request.resourceLimits(),
        request.workerSelection(),
        request.options(),
        request.ddsTarget());
  }

  /** Constructs caller-selected wire compression and split behavior. */
  private static PackOptions options(
      PackOptions.Compression compression, boolean sharing, long split) {
    return new PackOptions(
        List.of(),
        compression,
        sharing,
        new PackOptions.Splitting.UpToBytes(split),
        FlagSelection.AUTOMATIC,
        FlagSelection.AUTOMATIC);
  }

  /** Builds the public canonical Fallout 4 General request. */
  private static PackRequest request(Path target, PackOptions options, PackSource... sources) {
    var standard =
        PackRequest.standard(
            target,
            ArchiveFamily.FO4_GENERAL_BA2,
            new ArchiveEncoding(
                Optional.of(new WireVersion(1)),
                Optional.of(Ba2Subtype.GNRL),
                OptionalLong.empty()),
            List.of(sources),
            Optional.empty());
    return new PackRequest(
        standard.destination(),
        standard.family(),
        standard.encoding(),
        standard.compatibilityProfile(),
        standard.sources(),
        standard.targetPolicy(),
        standard.diagnosticPolicy(),
        standard.resourceLimits(),
        standard.workerSelection(),
        options,
        standard.ddsTarget());
  }

  /** Supplies a fresh channel with its declared exact source length. */
  private static PackSource generated(String name, byte[] bytes) {
    return new PackSource.GeneratedEntry(
        name, bytes.length, () -> Channels.newChannel(new ByteArrayInputStream(bytes)));
  }
}
