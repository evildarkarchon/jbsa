package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Primary Failure precedence for a BA2 with two structural faults: a payload inside the index, then
 * a malformed record after it. The two record shapes resolve this differently, because a General
 * record has a fixed length and a DX10 record does not, so the end of the index is known before the
 * General walk but only after the DX10 walk.
 */
final class Ba2FailurePrecedenceTest {
  @TempDir Path directory;

  /**
   * A General BA2 knows where its index ends before the first record, so the payload inside the
   * index fails at its own entry, before the walk reaches the malformed record that follows.
   */
  @Test
  void generalReportsIndexOverlappingPayloadBeforeLaterMalformedRecord() throws Exception {
    // Each fault on its own is a failure, so the combined archive really carries both.
    assertPrimaryFailure(
        general(0x18, 1), ArchiveFamily.FO4_GENERAL_BA2, "ba2.payload-overlaps-index");
    assertPrimaryFailure(
        general(0x60, 2), ArchiveFamily.FO4_GENERAL_BA2, "gnrl.invalid-chunk-count");

    assertPrimaryFailure(
        general(0x18, 2), ArchiveFamily.FO4_GENERAL_BA2, "ba2.payload-overlaps-index");
  }

  /**
   * A DX10 BA2 learns where its index ends only after the last record, so the malformed record is
   * found by the walk before the index-overlapping payload can be judged.
   */
  @Test
  void ddsReportsLaterMalformedRecordBeforeIndexOverlappingPayload() throws Exception {
    // Each fault on its own is a failure, so the combined archive really carries both.
    assertPrimaryFailure(dds(0x18, 0), ArchiveFamily.FO4_DDS_BA2, "ba2.payload-overlaps-index");
    assertPrimaryFailure(dds(0x78, 1), ArchiveFamily.FO4_DDS_BA2, "dx10.invalid-mip-range");

    assertPrimaryFailure(dds(0x18, 1), ArchiveFamily.FO4_DDS_BA2, "dx10.invalid-mip-range");
  }

  /** Asserts that inspection rejects the archive with {@code identifier} as its Primary Failure. */
  private void assertPrimaryFailure(String hex, ArchiveFamily family, String identifier)
      throws Exception {
    Path path =
        Files.write(
            directory.resolve("multi-fault.ba2"), HexFormat.of().parseHex(hex.replace(" ", "")));
    assertEquals(family, BethesdaArchives.standard().detect(path).family().orElseThrow());
    ArchiveException failure =
        assertThrows(ArchiveException.class, () -> BethesdaArchives.standard().inspect(path));
    assertEquals(FailureKind.FORMAT, failure.kind());
    assertEquals(identifier, failure.primaryFailure().diagnosticIdentifier().orElseThrow());
    assertEquals(ArchiveDisposition.REJECTED, failure.assessment().orElseThrow().disposition());
  }

  /**
   * Two General records end the index at 96. A one-byte payload sits at 96 and the filename table
   * ("a/b.txt", "a/c.txt") follows at 97. The record hashes match those names.
   *
   * @param firstOffset the first entry's payload offset; below 96 it lies inside the index
   * @param secondChunks the second record's chunk count; anything but 1 is malformed
   */
  private static String general(long firstOffset, int secondChunks) {
    return "42544458 01000000 474e524c 02000000 6100000000000000 "
        + "7400bca3 74787400 ce51b53a 00011000 "
        + le64(firstOffset)
        + " 00000000 01000000 0df0adba "
        + "e230bbd4 74787400 ce51b53a 00"
        + String.format(Locale.ROOT, "%02x", secondChunks)
        + "1000 6000000000000000 00000000 01000000 0df0adba "
        + "07 0700 612f622e747874 0700 612f632e747874";
  }

  /**
   * Two 4x4 BC1 textures of one mip and one stored 8-byte chunk each, without a filename table. The
   * records end the index at 120; the second texture's chunk sits at 128.
   *
   * @param firstOffset the first texture's chunk offset; below 120 it lies inside the index
   * @param secondStartMip the second texture's first chunk mip; anything but 0 is malformed
   */
  private static String dds(long firstOffset, int secondStartMip) {
    String texture = "00000000 64647300 00000000 00 01 1800 0400 0400 01 47 00 00 ";
    return "42544458 01000000 44583130 02000000 0000000000000000 "
        + texture
        + le64(firstOffset)
        + " 00000000 08000000 0000 0000 0df0adba "
        + texture
        + "8000000000000000 00000000 08000000 "
        + String.format(Locale.ROOT, "%02x00 %02x00", secondStartMip, secondStartMip)
        + " 0df0adba "
        + "0700000000000000 0900000000000000";
  }

  /** Encodes a little-endian unsigned 64-bit wire field as hex. */
  private static String le64(long value) {
    return String.format(Locale.ROOT, "%016x", Long.reverseBytes(value));
  }
}
