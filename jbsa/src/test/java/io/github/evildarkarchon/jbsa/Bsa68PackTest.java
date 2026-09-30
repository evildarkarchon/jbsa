package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public 0x68 packing contract with independently specified wire expectations. */
class Bsa68PackTest {
  @TempDir Path temporary;

  /** Equal payloads with different embedded names must remain distinct complete records. */
  @Test
  void retainsMixedRecordsAndDoesNotShareDifferentPrefixes() throws Exception {
    Path target = temporary.resolve("mixed.bsa");
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.STORED,
            true,
            new PackOptions.Splitting.FamilyDefault(),
            new FlagSelection.Explicit(0x107),
            FlagSelection.AUTOMATIC,
            Map.of(new NormalizedNameIdentity("meshes\\c.nif"), PackOptions.Compression.ZLIB));
    BethesdaArchives.standard()
        .pack(
            request(
                target,
                options,
                generated("meshes/a.nif", new byte[] {7}),
                generated("meshes/b.nif", new byte[] {7}),
                generated("meshes/c.nif", new byte[] {7})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertNotEquals(wire.getInt(72), wire.getInt(88));
    assertEquals(0x4000000e, wire.getInt(68));
    assertEquals(0x4000000e, wire.getInt(84));
    assertEquals(26, wire.getInt(100));
    try (var archive = BethesdaArchives.standard().open(target, OpenOptions.standard())) {
      for (int i = 0; i < 3; i++) {
        try (var content = archive.entry(i).openContent()) {
          ByteBuffer bytes = ByteBuffer.allocate(2);
          assertEquals(1, content.read(bytes));
          assertEquals(-1, content.read(bytes));
          assertEquals(7, bytes.get(0));
        }
      }
    }
  }

  /** Prefix and folder wire limits are enforced before a generated source is opened. */
  @Test
  void rejectsOversizedPrefixBeforeSourceEffects() throws Exception {
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    var source =
        new PackSource.GeneratedEntry(
            "meshes/" + "a".repeat(245) + ".nif",
            1,
            () -> {
              opens.incrementAndGet();
              return null;
            });
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.STORED,
            true,
            new PackOptions.Splitting.FamilyDefault(),
            new FlagSelection.Explicit(0x103),
            FlagSelection.AUTOMATIC);
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(temporary.resolve("long.bsa"), options, source),
                        OperationControl.standard()));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(0, opens.get());
    assertFalse(Files.exists(temporary.resolve("long.bsa")));
  }

  /** Obsolete menu/shader/font classifications are cleared only for automatic file flags. */
  @Test
  void clearsLegacyFileFlags() throws Exception {
    Path target = temporary.resolve("flags.bsa");
    BethesdaArchives.standard()
        .pack(
            request(
                target,
                PackOptions.standard(),
                generated("menus/a.xml", new byte[] {1}),
                generated("fonts/a.fnt", new byte[] {2}),
                generated("shaders/a.sdp", new byte[] {3})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(3, wire.getInt(12));
    assertEquals(0x100, wire.getInt(32));
  }

  /** Explicit prefixes use canonical names and precede both stored bytes and zlib framing. */
  @Test
  void packsCanonicalEmbeddedNames() throws Exception {
    for (var compression : List.of(PackOptions.Compression.STORED, PackOptions.Compression.ZLIB)) {
      Path target = temporary.resolve(compression + ".bsa");
      var options =
          new PackOptions(
              List.of(),
              compression,
              true,
              new PackOptions.Splitting.FamilyDefault(),
              new FlagSelection.Explicit(0x103),
              FlagSelection.AUTOMATIC);
      BethesdaArchives.standard()
          .pack(
              request(target, options, generated("Meshes/A.nif", new byte[] {42})),
              OperationControl.standard());
      ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
      assertEquals(0x103, wire.getInt(12));
      assertEquals(12, Byte.toUnsignedInt(wire.get(82)));
      assertArrayEquals(
          "meshes\\a.nif".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          Arrays.copyOfRange(wire.array(), 83, 95));
      if (compression == PackOptions.Compression.STORED) {
        assertEquals(14, wire.getInt(68));
        assertEquals(42, wire.get(95));
      } else {
        assertEquals(0x4000001a, wire.getInt(68));
        assertEquals(1, wire.getInt(95));
        var inflater = new java.util.zip.Inflater();
        try {
          inflater.setInput(wire.array(), 99, wire.array().length - 99);
          byte[] payload = new byte[2];
          assertEquals(1, inflater.inflate(payload));
          assertEquals(42, payload[0]);
          assertTrue(inflater.finished());
          assertEquals(0, inflater.getRemaining());
        } finally {
          inflater.end();
        }
      }
    }
  }

  /** The shared writer selects the 0x68 wire selector and family-specific automatic flags. */
  @Test
  void packsStoredWithFamilyFlags() throws Exception {
    Path target = temporary.resolve("stored.bsa");
    BethesdaArchives.standard()
        .pack(
            request(
                target, PackOptions.standard(), generated("Meshes/A.nif", new byte[] {1, 2, 3})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x68, wire.getInt(4));
    assertEquals(0x83, wire.getInt(12));
    assertEquals(1, wire.getInt(32));
    assertEquals(3, wire.getInt(68));
    assertEquals(82, wire.getInt(72));
    assertArrayEquals(new byte[] {1, 2, 3}, Arrays.copyOfRange(wire.array(), 82, 85));
  }

  /** Builds a family request without exposing internal writer mechanisms. */
  private static PackRequest request(Path target, PackOptions options, PackSource... sources) {
    var standard =
        PackRequest.standard(
            target,
            ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA,
            new ArchiveEncoding(
                Optional.of(new WireVersion(0x68)), Optional.empty(), OptionalLong.empty()),
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

  /** Supplies a fresh channel over synthetic, redistributable payload bytes. */
  private static PackSource generated(String name, byte[] bytes) {
    return new PackSource.GeneratedEntry(
        name, bytes.length, () -> Channels.newChannel(new ByteArrayInputStream(bytes)));
  }
}
