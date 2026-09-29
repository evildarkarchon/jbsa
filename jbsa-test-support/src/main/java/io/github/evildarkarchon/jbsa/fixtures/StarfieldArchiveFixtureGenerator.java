package io.github.evildarkarchon.jbsa.fixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Serializes the project-authored Starfield General and DDS BA2 vectors directly from their wire
 * layouts. This build-only generator does not call the JBSA archive writer.
 */
public final class StarfieldArchiveFixtureGenerator {
  private static final String GENERATOR_PATH =
      "jbsa-test-support/src/main/java/io/github/evildarkarchon/jbsa/fixtures/"
          + "StarfieldArchiveFixtureGenerator.java";
  private static final String COMMAND_PREFIX =
      "java -cp jbsa-test-support/target/classes/java/main "
          + "io.github.evildarkarchon.jbsa.fixtures.StarfieldArchiveFixtureGenerator ";
  private static final String GENERAL_COMMAND = COMMAND_PREFIX + "--general-output <directory>";
  private static final String DDS_COMMAND = COMMAND_PREFIX + "--dds-output <directory>";
  private static final String GENERAL_NAME = "a/b.txt";
  private static final String DDS_NAME = "textures/a.dds";
  private static final byte[] MIP = HexFormat.of().parseHex("630e873656a6ce50");

  private StarfieldArchiveFixtureGenerator() {}

  /**
   * Generates exactly one Starfield fixture corpus from the command line.
   *
   * @param args {@code --general-output <directory>} or {@code --dds-output <directory>}
   * @throws IllegalArgumentException if the command-line shape is unsupported
   * @throws IOException if the destination is nonempty or cannot be written
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException(
          "Usage: StarfieldArchiveFixtureGenerator --general-output|--dds-output <directory>");
    }
    switch (args[0]) {
      case "--general-output" -> materializeGeneral(Path.of(args[1]));
      case "--dds-output" -> materializeDds(Path.of(args[1]));
      default ->
          throw new IllegalArgumentException(
              "Usage: StarfieldArchiveFixtureGenerator --general-output|--dds-output <directory>");
    }
  }

  /**
   * Writes the version-2 zlib General BA2 vector and provenance manifest.
   *
   * @param destination absent or empty output directory
   * @throws NullPointerException if {@code destination} is null
   * @throws IOException if the destination is nonempty or cannot be written
   */
  public static void materializeGeneral(Path destination) throws IOException {
    requireEmptyDestination(destination);
    Files.createDirectories(destination);
    byte[] wire = generalV2();
    byte[] hex = hexText(wire);
    Files.write(destination.resolve("starfield-general-v2-zlib.hex"), hex);
    Files.writeString(destination.resolve("manifest.json"), generalManifest(wire, hex));
  }

  /**
   * Writes the version-2 zlib and version-3 method-3 raw-LZ4 DDS BA2 vectors and manifest.
   *
   * @param destination absent or empty output directory
   * @throws NullPointerException if {@code destination} is null
   * @throws IOException if the destination is nonempty or cannot be written
   */
  public static void materializeDds(Path destination) throws IOException {
    requireEmptyDestination(destination);
    Files.createDirectories(destination);
    byte[] v2 = dds(2);
    byte[] v3 = dds(3);
    byte[] v2Hex = hexText(v2);
    byte[] v3Hex = hexText(v3);
    Files.write(destination.resolve("starfield-dds-v2-zlib.hex"), v2Hex);
    Files.write(destination.resolve("starfield-dds-v3-raw-lz4.hex"), v3Hex);
    Files.writeString(destination.resolve("manifest.json"), ddsManifest(v2, v2Hex, v3, v3Hex));
  }

  /** Rejects an occupied destination before publishing any generated fixture. */
  private static void requireEmptyDestination(Path destination) throws IOException {
    Objects.requireNonNull(destination, "destination");
    if (!Files.exists(destination)) return;
    if (!Files.isDirectory(destination)) {
      throw new IOException("Fixture destination is not a directory: " + destination);
    }
    try (var entries = Files.list(destination)) {
      if (entries.findAny().isPresent()) {
        throw new IOException("Fixture destination must be empty: " + destination);
      }
    }
  }

  /** Serializes the v2 extended header, General record, one zlib stream, and name table. */
  private static byte[] generalV2() {
    byte[] name = GENERAL_NAME.getBytes(StandardCharsets.US_ASCII);
    byte[] payload = {7};
    byte[] compressed = zlibStored(payload);
    int payloadOffset = 32 + 36;
    int namesOffset = payloadOffset + compressed.length;
    ByteBuffer output =
        ByteBuffer.allocate(namesOffset + 2 + name.length).order(ByteOrder.LITTLE_ENDIAN);
    putHeader(output, 2, "GNRL", namesOffset);
    output.putLong(1L); // The v2 extension occupies eight bytes before the entry records.
    output.putInt(ba2Hash("b"));
    output.put(extension("txt"));
    output.putInt(ba2Hash("a"));
    output.put((byte) 0);
    output.put((byte) 1);
    output.putShort((short) 16);
    output.putLong(payloadOffset);
    output.putInt(compressed.length);
    output.putInt(payload.length);
    output.putInt(0xbaadf00d);
    output.put(compressed);
    putName(output, name);
    return output.array();
  }

  /** Serializes one 4x4 BC1 texture record with the codec declared by its Starfield header. */
  private static byte[] dds(int version) {
    if (version != 2 && version != 3) {
      throw new IllegalArgumentException("Starfield DDS fixture version must be 2 or 3");
    }
    byte[] name = DDS_NAME.getBytes(StandardCharsets.US_ASCII);
    byte[] compressed = version == 2 ? zlibFixed(MIP) : rawLz4Literal(MIP);
    int payloadOffset = (version == 2 ? 32 : 36) + 48;
    int namesOffset = payloadOffset + compressed.length;
    ByteBuffer output =
        ByteBuffer.allocate(namesOffset + 2 + name.length).order(ByteOrder.LITTLE_ENDIAN);
    putHeader(output, version, "DX10", namesOffset);
    output.putLong(1L);
    if (version == 3) output.putInt(3);
    output.putInt(ba2Hash("a"));
    output.put(extension("dds"));
    output.putInt(ba2Hash("textures"));
    output.put((byte) 0);
    output.put((byte) 1);
    output.putShort((short) 24);
    output.putShort((short) 4);
    output.putShort((short) 4);
    output.put((byte) 1);
    output.put((byte) 71);
    output.put((byte) 0);
    output.put((byte) 0);
    output.putLong(payloadOffset);
    output.putInt(compressed.length);
    output.putInt(MIP.length);
    output.putShort((short) 0);
    output.putShort((short) 0);
    output.putInt(0xbaadf00d);
    output.put(compressed);
    putName(output, name);
    return output.array();
  }

  /** Writes the common 24-byte BA2 header without using any product format code. */
  private static void putHeader(ByteBuffer output, int version, String subtype, long namesOffset) {
    output.put("BTDX".getBytes(StandardCharsets.US_ASCII));
    output.putInt(version);
    output.put(subtype.getBytes(StandardCharsets.US_ASCII));
    output.putInt(1);
    output.putLong(namesOffset);
  }

  /** Appends one unsigned-short-length ASCII name-table entry. */
  private static void putName(ByteBuffer output, byte[] name) {
    output.putShort((short) name.length);
    output.put(name);
  }

  /** Returns one four-byte ASCII extension field with a trailing zero byte. */
  private static byte[] extension(String value) {
    byte[] ascii = value.getBytes(StandardCharsets.US_ASCII);
    if (ascii.length != 3) throw new IllegalArgumentException("Extension must have three bytes");
    return new byte[] {ascii[0], ascii[1], ascii[2], 0};
  }

  /** Computes the BA2 component CRC with initial zero and no final XOR. */
  private static int ba2Hash(String component) {
    int crc = 0;
    for (byte raw : component.getBytes(StandardCharsets.US_ASCII)) {
      int octet = raw >= 'A' && raw <= 'Z' ? raw + 32 : raw;
      crc ^= octet;
      for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) == 1 ? 0xedb88320 : 0);
    }
    return crc;
  }

  /** Frames a final RFC 1951 stored block in the original v2 General zlib envelope. */
  private static byte[] zlibStored(byte[] payload) {
    if (payload.length > 0xffff) throw new IllegalArgumentException("Stored block is too large");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    // CMF/FLG 78 da records the original level hint; the DEFLATE block itself is stored.
    output.write(0x78);
    output.write(0xda);
    output.write(1);
    output.write(payload.length & 0xff);
    output.write(payload.length >>> 8);
    int complement = ~payload.length;
    output.write(complement & 0xff);
    output.write((complement >>> 8) & 0xff);
    output.writeBytes(payload);
    writeBigEndianInt(output, adler32(payload));
    return output.toByteArray();
  }

  /** Encodes the eight BC1 bytes as an RFC 1951 fixed-Huffman block in a zlib envelope. */
  private static byte[] zlibFixed(byte[] payload) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(0x78);
    output.write(0x9c);
    FixedBits bits = new FixedBits();
    bits.writeLeastSignificantFirst(3, 3); // Final block with BTYPE=01 (fixed Huffman).
    for (byte raw : payload) {
      int literal = raw & 0xff;
      if (literal <= 143) {
        bits.writeMostSignificantFirst(0x30 + literal, 8);
      } else {
        bits.writeMostSignificantFirst(0x190 + literal - 144, 9);
      }
    }
    bits.writeMostSignificantFirst(0, 7); // End-of-block code 256.
    output.writeBytes(bits.toByteArray());
    writeBigEndianInt(output, adler32(payload));
    return output.toByteArray();
  }

  /** Encodes one complete raw-LZ4 literal sequence for the eight-byte BC1 block. */
  private static byte[] rawLz4Literal(byte[] payload) {
    if (payload.length > 15) throw new IllegalArgumentException("Literal sequence is too large");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(payload.length << 4);
    output.writeBytes(payload);
    return output.toByteArray();
  }

  /** Computes RFC 1950 Adler-32 from the uncompressed payload. */
  private static int adler32(byte[] payload) {
    int first = 1;
    int second = 0;
    for (byte value : payload) {
      first = (first + (value & 0xff)) % 65521;
      second = (second + first) % 65521;
    }
    return (second << 16) | first;
  }

  /** Appends a network-order checksum after the little-endian archive fields. */
  private static void writeBigEndianInt(ByteArrayOutputStream output, int value) {
    output.write((value >>> 24) & 0xff);
    output.write((value >>> 16) & 0xff);
    output.write((value >>> 8) & 0xff);
    output.write(value & 0xff);
  }

  /** Represents binary archive bytes as lowercase hexadecimal followed by one LF. */
  private static byte[] hexText(byte[] wire) {
    return (HexFormat.of().formatHex(wire) + "\n").getBytes(StandardCharsets.US_ASCII);
  }

  /** Binds the General recipe, generator identity, and both committed byte representations. */
  private static String generalManifest(byte[] wire, byte[] hex) {
    return """
        {
          "schema_version": 1,
          "generated_on": "2026-09-29",
          "reference_snapshot_revision": "fd1e36020b2b5b6217e553dc0038983146a2e2dd",
          "generator": {
            "id": "jbsa-starfield-general-wire-v1",
            "path": "%s",
            "version": "1",
            "spdx_license": "Apache-2.0",
            "command": "%s"
          },
          "fixtures": [
            {
              "id": "starfield-general-v2-zlib",
              "creator": "JBSA project contributors",
              "spdx_license": "CC0-1.0",
              "redistribution_class": "project-authored-redistributable",
              "source": "independently authored JBSA-GNRL-001 through JBSA-GNRL-005 wire recipe",
              "input_sha256": "ca358758f6d27e6cf45272937977a748fd88391db679ceda7dc7bf1f005ee879",
              "generation": {
                "command": "%s",
                "procedure": "Write one canonical version-2 General BA2 record and RFC 1950 stored block for payload byte 07, then append the a/b.txt name table.",
                "options": {
                  "archive_family": "sf-gnrl-v2",
                  "codec": "zlib",
                  "unknown_value_at_24": 1
                }
              },
              "output": {
                "path": "starfield-general-v2-zlib.hex",
                "sha256": "%s",
                "decoded_archive_sha256": "%s"
              }
            }
          ]
        }
        """
        .formatted(GENERATOR_PATH, GENERAL_COMMAND, GENERAL_COMMAND, sha256(hex), sha256(wire));
  }

  /** Binds both DDS recipes and their exact textual and decoded archive bytes. */
  private static String ddsManifest(byte[] v2, byte[] v2Hex, byte[] v3, byte[] v3Hex) {
    return """
        {
          "schema_version": 1,
          "generated_on": "2026-09-29",
          "reference_snapshot_revision": "fd1e36020b2b5b6217e553dc0038983146a2e2dd",
          "generator": {
            "id": "jbsa-starfield-dds-wire-v1",
            "path": "%s",
            "version": "1",
            "spdx_license": "Apache-2.0",
            "command": "%s"
          },
          "fixtures": [
            {
              "id": "starfield-dds-v2-zlib",
              "creator": "JBSA project contributors",
              "spdx_license": "CC0-1.0",
              "redistribution_class": "project-authored-redistributable",
              "source": "independently authored JBSA-GNRL-001 and JBSA-DX10-001 through JBSA-DX10-005 wire recipe",
              "input_sha256": "4aae08ff0c39c4203e44b4f8e5f518ea5cdf2e0c510958d58e552e3b577961ee",
              "generation": {
                "command": "%s",
                "procedure": "Encode one canonical 4x4 BC1 texture record and independently framed zlib chunk under the Starfield version-2 header.",
                "options": {
                  "archive_family": "sf-dx10-v2",
                  "codec": "zlib",
                  "unknown_value_at_24": "1"
                }
              },
              "output": {
                "path": "starfield-dds-v2-zlib.hex",
                "sha256": "%s",
                "decoded_archive_sha256": "%s"
              }
            },
            {
              "id": "starfield-dds-v3-raw-lz4",
              "creator": "JBSA project contributors",
              "spdx_license": "CC0-1.0",
              "redistribution_class": "project-authored-redistributable",
              "source": "independently authored JBSA-GNRL-001, JBSA-GNRL-005, and JBSA-DX10-001 through JBSA-DX10-005 wire recipe",
              "input_sha256": "4aae08ff0c39c4203e44b4f8e5f518ea5cdf2e0c510958d58e552e3b577961ee",
              "generation": {
                "command": "%s",
                "procedure": "Encode the same canonical 4x4 BC1 texture record as one complete raw-LZ4 literal block under the Starfield version-3 method-3 header.",
                "options": {
                  "archive_family": "sf-dx10-v3-m3",
                  "codec": "raw-lz4",
                  "compression_method": "3",
                  "unknown_value_at_24": "1"
                }
              },
              "output": {
                "path": "starfield-dds-v3-raw-lz4.hex",
                "sha256": "%s",
                "decoded_archive_sha256": "%s"
              }
            }
          ]
        }
        """
        .formatted(
            GENERATOR_PATH,
            DDS_COMMAND,
            DDS_COMMAND,
            sha256(v2Hex),
            sha256(v2),
            DDS_COMMAND,
            sha256(v3Hex),
            sha256(v3));
  }

  /** Returns a lowercase SHA-256 of fixture data, never of generator source. */
  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK SHA-256 is unavailable", impossible);
    }
  }

  /** Packs fixed-Huffman codes in the RFC 1951 bit order without a compression provider. */
  private static final class FixedBits {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int current;
    private int used;

    /** Writes the low bits of a DEFLATE block header in wire order. */
    private void writeLeastSignificantFirst(int value, int length) {
      for (int bit = 0; bit < length; bit++) writeBit((value >>> bit) & 1);
    }

    /** Writes a canonical Huffman code with its most significant bit first. */
    private void writeMostSignificantFirst(int value, int length) {
      for (int bit = length - 1; bit >= 0; bit--) writeBit((value >>> bit) & 1);
    }

    /** Appends one wire bit and emits each completed byte. */
    private void writeBit(int bit) {
      current |= bit << used++;
      if (used == 8) {
        output.write(current);
        current = 0;
        used = 0;
      }
    }

    /** Pads the final byte with zero bits and returns the encoded DEFLATE block. */
    private byte[] toByteArray() {
      if (used != 0) output.write(current);
      return output.toByteArray();
    }
  }
}
