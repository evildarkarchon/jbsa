package io.github.evildarkarchon.jbsa.internal.tes3;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted;
import io.github.evildarkarchon.jbsa.internal.pack.Codec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Pure input-to-output tests of the TES3 adapter; nothing here performs I/O. */
class Tes3AdapterTest {
  private static final Path TARGET = Path.of("unused.bsa").toAbsolutePath();
  private static final IoContext CONTEXT = IoContext.of(TARGET, Operation.PACK);

  /** Hash order is the low word, then the high word, then canonical name bytes unsigned. */
  @Test
  void plansStoredEntriesInHashThenUnsignedNameOrder() throws Exception {
    var admitted = admitted(PackOptions.standard());
    List<Admitted.Planned<Tes3Adapter.WireName>> planned =
        admitted.plan(
            List.of(
                entry("high-low", 0x0000_0002_0000_0001L, 1),
                entry("low", 0x0000_0009_0000_0000L, 1),
                entry("tie-\u0080", 0x0000_0001_0000_0001L, 1),
                entry("tie-a", 0x0000_0001_0000_0001L, 1)),
            CONTEXT);
    // "tie-a" precedes "tie-\u0080" because 0x61 < 0x80 unsigned, though (byte) 0x80 is negative.
    assertEquals(
        List.of("low", "tie-a", "tie-\u0080", "high-low"),
        planned.stream().map(entry -> entry.source().displayName()).toList());
    for (var entry : planned) {
      assertEquals(Codec.STORED, entry.codec());
      assertEquals(0, entry.frame().length);
      assertSame(entry.source().name(), entry.key().bytes());
      assertEquals(entry.source().hash(), entry.key().hash());
    }
  }

  /** A declared size beyond u32 fails in plan, before splitting or the decoded-size limit. */
  @Test
  void planRejectsSizesBeyondU32() throws Exception {
    var admitted = admitted(PackOptions.standard());
    var failure =
        assertThrows(
            ArchiveException.class,
            () -> admitted.plan(List.of(entry("a", 1, 0x1_0000_0000L)), CONTEXT));
    assertEquals(Optional.of("tes3.wire-limit"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(FailureKind.POLICY, failure.kind());
  }

  /**
   * The payload start is the 12-byte header plus 20 bytes and one NUL-terminated name per entry.
   */
  @Test
  void layoutPlacesPayloadsAfterTheCompleteMetadata() throws Exception {
    var admitted = admitted(PackOptions.standard());
    var part = admitted.plan(List.of(entry("a", 7, 3), entry("bc", 8, 5)), CONTEXT);
    var layout = admitted.layout(part, CONTEXT);
    assertEquals(12 + 2 * 20 + 2 + 3, layout.payloadStart());
    assertEquals(layout.payloadStart(), layout.metadataBytes());
  }

  /** An undeduplicated payload offset beyond u32 cannot be encoded, even if sharing would help. */
  @Test
  void layoutRejectsPayloadOffsetsBeyondU32() throws Exception {
    var admitted = admitted(PackOptions.standard());
    var fits = List.of(entry("a", 1, 0xffff_ffffL), entry("b", 2, 1));
    admitted.layout(admitted.plan(fits, CONTEXT), CONTEXT);
    var overflow = List.of(entry("a", 1, 0xffff_ffffL), entry("b", 2, 1), entry("c", 3, 0));
    var failure =
        assertThrows(
            ArchiveException.class,
            () -> admitted.layout(admitted.plan(overflow, CONTEXT), CONTEXT));
    assertEquals(Optional.of("tes3.wire-limit"), failure.primaryFailure().diagnosticIdentifier());
  }

  /** Tables carry sizes, payload-relative offsets (repeated when shared), names, and hashes. */
  @Test
  void tablesPatchEveryRecordAfterPlacement() throws Exception {
    var admitted = admitted(PackOptions.standard());
    var part = admitted.plan(List.of(entry("a", 0x11L, 3), entry("bc", 0x22L, 3)), CONTEXT);
    var layout = admitted.layout(part, CONTEXT);
    long start = layout.payloadStart();
    var placed =
        List.of(
            new Admitted.Placed<>(part.get(0), start, 3),
            // The second entry shares the first entry's stored copy.
            new Admitted.Placed<>(part.get(1), start, 3));
    var patches = admitted.tables(placed, layout);
    long hashOffset = 2 * 12 + 2 + 3;
    assertEquals(
        List.of(0L, 12L, 12L + 2 * 8, 12L + 2 * 12, 12L + hashOffset),
        patches.stream().map(Admitted.Patch::position).toList());
    assertArrayEquals(words(0x100, hashOffset, 2), patches.get(0).bytes());
    assertArrayEquals(words(3, 0, 3, 0), patches.get(1).bytes());
    assertArrayEquals(words(0, 2), patches.get(2).bytes());
    assertArrayEquals("a\0bc\0".getBytes(StandardCharsets.US_ASCII), patches.get(3).bytes());
    assertArrayEquals(
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(0x11).putLong(0x22).array(),
        patches.get(4).bytes());
    // The patches tile the metadata exactly, ending at the payload start.
    var last = patches.getLast();
    assertEquals(start, last.position() + last.bytes().length);
  }

  /**
   * D6: a compressed global choice is refused, while stored and the family default are admitted.
   */
  @Test
  void admitRejectsCompressedGlobalCodecs() throws Exception {
    for (var compression : PackOptions.Compression.values()) {
      var options = options(compression, Map.of());
      boolean stored =
          compression == PackOptions.Compression.FAMILY_DEFAULT
              || compression == PackOptions.Compression.STORED;
      if (stored) assertNotNull(admitted(options), compression.name());
      else {
        var failure = assertThrows(ArchiveException.class, () -> admitted(options));
        assertEquals(
            Optional.of("tes3.unsupported-codec"), failure.primaryFailure().diagnosticIdentifier());
        assertEquals(FailureKind.UNSUPPORTED, failure.kind());
      }
    }
  }

  /** An entry codec override is inapplicable, and that rule precedes the global codec rule. */
  @Test
  void entryCompressionOverridesPrecedeTheCodecRule() {
    var options =
        options(
            PackOptions.Compression.ZLIB,
            Map.of(new NormalizedNameIdentity("a"), PackOptions.Compression.STORED));
    var failure = assertThrows(ArchiveException.class, () -> admitted(options));
    assertEquals(
        Optional.of("tes3.entry-compression-inapplicable"),
        failure.primaryFailure().diagnosticIdentifier());
  }

  /** Admits a TES3 request with the given options through the adapter singleton. */
  private static Admitted<Tes3Adapter.WireName> admitted(PackOptions options)
      throws ArchiveException {
    var standard =
        PackRequest.standard(
            TARGET, ArchiveFamily.TES3_BSA, ArchiveEncoding.tes3(), List.of(), Optional.empty());
    var request =
        new PackRequest(
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
    return Tes3Adapter.INSTANCE.admit(request, CONTEXT);
  }

  /** Standard options with only the global codec and entry overrides replaced. */
  private static PackOptions options(
      PackOptions.Compression compression,
      Map<NormalizedNameIdentity, PackOptions.Compression> entryCompression) {
    return new PackOptions(
        List.of(),
        compression,
        true,
        new PackOptions.Splitting.FamilyDefault(),
        FlagSelection.AUTOMATIC,
        FlagSelection.AUTOMATIC,
        entryCompression);
  }

  /** A planned source with an explicit hash; its payload is never consumed by pure methods. */
  private static PackSources.Entry entry(String name, long hash, long size) {
    return new PackSources.Entry(
        name,
        name,
        name.getBytes(StandardCharsets.ISO_8859_1),
        hash,
        size,
        reader -> {
          throw new AssertionError("Adapters must not read payloads");
        });
  }

  /** Serializes little-endian u32 words. */
  private static byte[] words(long... values) {
    var bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (long value : values) bytes.putInt((int) value);
    return bytes.array();
  }
}
