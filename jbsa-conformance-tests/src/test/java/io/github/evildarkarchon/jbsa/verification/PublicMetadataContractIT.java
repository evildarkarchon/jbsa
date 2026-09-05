package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.evildarkarchon.jbsa.ArchiveEncoding;
import io.github.evildarkarchon.jbsa.ArchiveFamily;
import io.github.evildarkarchon.jbsa.ArchiveMetadata;
import io.github.evildarkarchon.jbsa.Ba2Subtype;
import io.github.evildarkarchon.jbsa.EntryMetadata;
import io.github.evildarkarchon.jbsa.NormalizedNameIdentity;
import io.github.evildarkarchon.jbsa.WireName;
import io.github.evildarkarchon.jbsa.WireVersion;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises detached public metadata without a parser or an open archive. */
@Tag("contract")
class PublicMetadataContractIT {
  /** Detached facts preserve large semantic quantities and copy all caller-owned collections. */
  @Test
  void detachedMetadataKeepsLargeSizesAndDefensivelyCopiesNamesAndChunks() {
    long large = 4_294_967_296L;
    var encoding =
        new ArchiveEncoding(
            Optional.of(new WireVersion(3)), Optional.of(Ba2Subtype.DX10), OptionalLong.of(3));
    var archive =
        new ArchiveMetadata.DdsBa2(
            ArchiveFamily.STARFIELD_DDS_BA2, encoding, large, large + 36, OptionalLong.of(1));
    var names = new HashMap<String, WireName>();
    names.put("complete", new WireName(new byte[] {65}));
    var chunks = new ArrayList<EntryMetadata.DdsChunk>();
    chunks.add(new EntryMetadata.DdsChunk(large, 123, large, 0, 2, 0xbaad_f00dL));
    var facts =
        new EntryMetadata.DdsBa2(
            new EntryMetadata.Ba2Identity(
                1, new WireName(new byte[] {100, 100, 115, 0}), 2, 0, 1, 24),
            1024,
            1024,
            3,
            71,
            0,
            0,
            chunks);
    var entry =
        new EntryMetadata(
            ArchiveFamily.STARFIELD_DDS_BA2,
            encoding,
            large,
            "A",
            Optional.empty(),
            names,
            large,
            123,
            facts);
    names.clear();
    chunks.clear();
    assertEquals(large, archive.entryCount());
    assertEquals(large, entry.ordinal());
    assertEquals(large, entry.decodedSize());
    assertEquals(new WireVersion(3), entry.encoding().wireVersion().orElseThrow());
    assertEquals(1, entry.wireNames().size());
    assertEquals(large, facts.chunks().getFirst().payloadOffset());
    assertThrows(UnsupportedOperationException.class, () -> entry.wireNames().clear());
    assertThrows(UnsupportedOperationException.class, () -> facts.chunks().clear());
  }

  /** Original name octets are independent of both retained inputs and returned arrays. */
  @Test
  void originalWireBytesCannotBeChangedThroughRetainedOrReturnedArrays() {
    byte[] source = {65, (byte) 0xff};
    var name = new WireName(source);
    source[0] = 0;
    byte[] result = name.bytes();
    result[1] = 0;
    assertArrayEquals(new byte[] {65, (byte) 0xff}, name.bytes());
    assertEquals(new WireName(new byte[] {65, (byte) 0xff}), name);
  }

  /** Name identity uses ASCII folding and archive structure, independently of host-path rules. */
  @Test
  void normalizedIdentityPreservesNonAsciiSpellingAndRejectsUnsafeOrUnmappableNames() {
    var encoding = Charset.forName("windows-1252");
    assertEquals(
        "textures\\Äbc.dds",
        NormalizedNameIdentity.from("Textures/ÄBC.DDS", encoding).orElseThrow().value());
    for (var name :
        new String[] {
          "", "/a", "a/", "a//b", "a/../b", "a/./b", "a.", "a ", "c:a", "a\u0000b", "漢字"
        }) {
      assertTrue(NormalizedNameIdentity.from(name, encoding).isEmpty(), name);
    }
    assertTrue(NormalizedNameIdentity.from("CON/file", encoding).isPresent());
  }

  /** Out-of-range signed name-table offsets remain inspectable as tolerated BA2 header facts. */
  @Test
  void retainsSignedOutOfRangeBa2NameTableOffsets() {
    var generalEncoding =
        new ArchiveEncoding(
            Optional.of(new WireVersion(1)), Optional.of(Ba2Subtype.GNRL), OptionalLong.empty());
    var ddsEncoding =
        new ArchiveEncoding(
            Optional.of(new WireVersion(1)), Optional.of(Ba2Subtype.DX10), OptionalLong.empty());
    var general =
        new ArchiveMetadata.GeneralBa2(
            ArchiveFamily.FO4_GENERAL_BA2, generalEncoding, 1, -1L, OptionalLong.empty());
    var dds =
        new ArchiveMetadata.DdsBa2(
            ArchiveFamily.FO4_DDS_BA2, ddsEncoding, 1, Long.MIN_VALUE, OptionalLong.empty());
    assertEquals(-1L, general.fileNameTableOffset());
    assertEquals(Long.MIN_VALUE, dds.fileNameTableOffset());
  }
}
