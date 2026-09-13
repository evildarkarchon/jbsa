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
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** DDS format and envelope checks through public packing and reconstructed content. */
@EnabledOnOs(OS.WINDOWS)
class DdsEnvelopePackTest {
  @TempDir Path temporary;

  /** All writable numeric formats retain exact metadata and opaque bytes across packing. */
  @ParameterizedTest
  @CsvSource({
    "71,8,128",
    "72,8,148",
    "74,16,128",
    "75,16,148",
    "77,16,128",
    "78,16,148",
    "80,8,128",
    "81,8,128",
    "83,16,128",
    "84,16,128",
    "95,16,148",
    "96,16,148",
    "98,16,148",
    "99,16,148",
    "28,4,128",
    "29,4,148",
    "87,4,128",
    "91,4,148",
    "88,4,128",
    "93,4,148",
    "85,2,128",
    "86,2,128",
    "49,2,128",
    "65,1,128",
    "61,1,128"
  })
  void roundTripsWritableFormat(int format, int payloadSize, int canonicalSize) throws Exception {
    byte[] source = extended(format, payloadSize, false);
    byte[] actual = roundTrip(source, DdsTarget.PC);
    assertEquals(canonicalSize + payloadSize, actual.length);
    assertArrayEquals(
        Arrays.copyOfRange(source, 148, source.length),
        Arrays.copyOfRange(actual, canonicalSize, actual.length));
    ByteBuffer h = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(payloadSize, h.getInt(20));
    assertEquals(1, h.getInt(24));
    assertEquals(1, h.getInt(28));
    if (canonicalSize == 148) {
      assertEquals(format, h.getInt(128));
      assertEquals(3, h.getInt(132));
      assertEquals(1, h.getInt(140));
    }
    ByteBuffer wire =
        ByteBuffer.wrap(Files.readAllBytes(temporary.resolve("texture.ba2")))
            .order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(format, Byte.toUnsignedInt(wire.get(45)));
  }

  /** Legacy BGR pixels acquire opaque X bytes and the canonical 32-bit pitch. */
  @Test
  void normalizesLegacyBgr24() throws Exception {
    ByteBuffer h = ByteBuffer.wrap(DdsBa2PackTest.bc1(2, 1, 1, 6)).order(ByteOrder.LITTLE_ENDIAN);
    h.putInt(80, 0x40).putInt(84, 0).putInt(88, 24);
    h.putInt(92, 0xFF0000).putInt(96, 0xFF00).putInt(100, 0xFF);
    h.position(128).put(new byte[] {1, 2, 3, 4, 5, 6});
    byte[] actual = roundTrip(h.array(), DdsTarget.PC);
    assertEquals(136, actual.length);
    assertArrayEquals(
        new byte[] {1, 2, 3, -1, 4, 5, 6, -1}, Arrays.copyOfRange(actual, 128, actual.length));
    ByteBuffer canonical = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(32, canonical.getInt(88));
    assertEquals(8, canonical.getInt(20));
  }

  /** Xbox extension fields are canonicalized while the stored tile mode survives. */
  @Test
  void reconstructsXboxWithExplicitTarget() throws Exception {
    byte[] source = extended(71, 8, true);
    ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).putInt(148, 9).putInt(152, 4096);
    byte[] actual = roundTrip(source, DdsTarget.XBOX);
    ByteBuffer h = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(172, actual.length);
    assertEquals(0x584F4258, h.getInt(84));
    assertEquals(9, h.getInt(148));
    assertEquals(0, h.getInt(152));
    assertEquals(0, h.getInt(156));
    assertEquals(10705, h.getInt(160));
  }

  /** Encode target never follows source FourCC or archive filename. */
  @Test
  void rejectsBothTargetMismatchesBeforePublication() throws Exception {
    rejects(extended(71, 8, true), DdsTarget.PC, FailureKind.UNSUPPORTED);
    rejects(extended(71, 8, false), DdsTarget.XBOX, FailureKind.UNSUPPORTED);
  }

  /** Computable metadata math alone does not make a DXGI format writable. */
  @Test
  void rejectsUnlistedFormat() throws Exception {
    rejects(extended(2, 16, false), DdsTarget.PC, FailureKind.UNSUPPORTED);
  }

  /** Missing and surplus payload bytes fail rather than becoming partial chunks. */
  @Test
  void rejectsPayloadLengthMismatch() throws Exception {
    byte[] source = extended(71, 8, false);
    rejects(Arrays.copyOf(source, source.length - 1), DdsTarget.PC, FailureKind.FORMAT);
    rejects(Arrays.copyOf(source, source.length + 1), DdsTarget.PC, FailureKind.FORMAT);
  }

  /** Mip counts cannot extend beyond the complete mathematical texture chain. */
  @Test
  void rejectsImpossibleMipCount() throws Exception {
    byte[] source = extended(71, 16, false);
    ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 2);
    rejects(source, DdsTarget.PC, FailureKind.FORMAT);
  }

  /** Arrays and volumes cannot be preserved in the BA2 texture record. */
  @Test
  void rejectsUnpreservableShapesAndUnrepresentableDimensions() throws Exception {
    byte[] source = extended(71, 8, false);
    ByteBuffer h = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
    h.putInt(140, 2);
    rejects(source, DdsTarget.PC, FailureKind.UNSUPPORTED);
    h.putInt(140, 1).putInt(132, 4);
    rejects(source, DdsTarget.PC, FailureKind.UNSUPPORTED);
    h.putInt(132, 3).putInt(24, 2);
    rejects(source, DdsTarget.PC, FailureKind.UNSUPPORTED);
    h.putInt(24, 1).putInt(16, 65536);
    rejects(source, DdsTarget.PC, FailureKind.FORMAT);
    h.putInt(16, 0);
    rejects(source, DdsTarget.PC, FailureKind.FORMAT);
  }

  /** Builds an independent one-pixel extended DDS input with deliberately nonzero opaque bytes. */
  private static byte[] extended(int format, int payloadSize, boolean xbox) {
    int header = xbox ? 164 : 148;
    ByteBuffer h = ByteBuffer.allocate(header + payloadSize).order(ByteOrder.LITTLE_ENDIAN);
    h.putInt(0, 0x20534444).putInt(4, 124).putInt(8, 0x21007);
    h.putInt(12, 1).putInt(16, 1).putInt(24, 1).putInt(28, 1);
    h.putInt(76, 32).putInt(80, 4).putInt(84, xbox ? 0x584F4258 : 0x30315844);
    h.putInt(108, 0x1000).putInt(128, format).putInt(132, 3).putInt(140, 1);
    for (int i = header; i < h.capacity(); i++) h.put(i, (byte) (i * 17));
    return h.array();
  }

  /** Packs a source and obtains the canonical content through the selected public decode target. */
  private byte[] roundTrip(byte[] source, DdsTarget target) throws Exception {
    Path destination = temporary.resolve("texture.ba2");
    BethesdaArchives.standard()
        .pack(request(destination, source, target), OperationControl.standard());
    OpenOptions options =
        new OpenOptions(Optional.empty(), ResourceLimits.standard(), Optional.of(target));
    try (var archive = BethesdaArchives.standard().open(destination, options);
        var content = archive.entry(0).openContent()) {
      return Channels.newInputStream(content).readAllBytes();
    }
  }

  /** Checks the stable failure category and absence of destination publication. */
  private void rejects(byte[] source, DdsTarget target, FailureKind kind) throws Exception {
    Path destination = temporary.resolve("rejected.ba2");
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(request(destination, source, target), OperationControl.standard()));
    assertEquals(kind, failure.kind());
    assertFalse(Files.exists(destination));
  }

  /** Uses explicit DDS operation data and an independently reproducible generated source. */
  private static PackRequest request(Path destination, byte[] source, DdsTarget target) {
    return PackRequest.standard(
        destination,
        ArchiveFamily.FO4_DDS_BA2,
        new ArchiveEncoding(
            Optional.of(new WireVersion(1)), Optional.of(Ba2Subtype.DX10), OptionalLong.empty()),
        List.of(
            new PackSource.GeneratedEntry(
                "Textures/envelope.dds",
                source.length,
                () -> Channels.newChannel(new ByteArrayInputStream(source)))),
        Optional.of(target));
  }
}
