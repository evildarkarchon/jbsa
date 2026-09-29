package io.github.evildarkarchon.jbsa.fixtures;

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
import java.util.zip.Adler32;

/**
 * Materializes one independently authored Fallout 4 DDS BA2 v1 archive. Generated fixture bytes are
 * CC0-1.0; this Apache-2.0 test-support writer uses only the published DX10 wire fields.
 */
public final class Fo4DdsV1FixtureGenerator {
  private static final String NAME = "textures/checker.dds";
  private static final byte[] MIP = HexFormat.of().parseHex("630e873656a6ce50");
  private static final String GENERATOR_PATH =
      "jbsa-test-support/src/main/java/io/github/evildarkarchon/jbsa/fixtures/"
          + "Fo4DdsV1FixtureGenerator.java";
  private static final String COMMAND =
      "java -cp jbsa-test-support/target/classes/java/main "
          + "io.github.evildarkarchon.jbsa.fixtures.Fo4DdsV1FixtureGenerator "
          + "--output <directory>";

  private Fo4DdsV1FixtureGenerator() {}

  /**
   * Runs the deterministic v1 DDS corpus materializer.
   *
   * @param args exactly {@code --output <directory>}
   * @throws IllegalArgumentException if the command-line shape is unsupported
   * @throws IOException if the destination is nonempty or cannot be written
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 2 || !"--output".equals(args[0])) {
      throw new IllegalArgumentException("Expected --output <directory>");
    }
    materialize(Path.of(args[1]));
  }

  /**
   * Writes the v1 wire vector and digest-bound manifest to an absent or empty directory.
   *
   * @param outputRoot absent or empty destination directory
   * @throws NullPointerException if {@code outputRoot} is null
   * @throws IOException if the destination is nonempty or an output cannot be written
   */
  public static void materialize(Path outputRoot) throws IOException {
    Objects.requireNonNull(outputRoot, "outputRoot");
    if (Files.exists(outputRoot)) {
      try (var entries = Files.list(outputRoot)) {
        if (entries.findAny().isPresent())
          throw new IOException("Fixture destination must be empty");
      }
    }
    Files.createDirectories(outputRoot);
    byte[] archive = archive();
    byte[] encoded = (HexFormat.of().formatHex(archive) + "\n").getBytes(StandardCharsets.US_ASCII);
    Files.write(outputRoot.resolve("fo4-dx10-v1-zlib.hex"), encoded);
    Files.writeString(outputRoot.resolve("manifest.json"), manifest(archive, encoded));
  }

  /** Serializes one 4x4 BC1 DX10 record and chunk directly in version-1 wire order. */
  private static byte[] archive() {
    byte[] name = NAME.getBytes(StandardCharsets.US_ASCII);
    byte[] chunk = zlibStoredBlock(MIP);
    int payloadOffset = 24 + 48;
    int namesOffset = payloadOffset + chunk.length;
    ByteBuffer wire =
        ByteBuffer.allocate(namesOffset + 2 + name.length).order(ByteOrder.LITTLE_ENDIAN);
    wire.put("BTDX".getBytes(StandardCharsets.US_ASCII));
    wire.putInt(1);
    wire.put("DX10".getBytes(StandardCharsets.US_ASCII));
    wire.putInt(1);
    wire.putLong(namesOffset);
    wire.putInt(wireHash("checker"));
    wire.put(new byte[] {'d', 'd', 's', 0});
    wire.putInt(wireHash("textures"));
    wire.put((byte) 0);
    wire.put((byte) 1);
    wire.putShort((short) 24);
    wire.putShort((short) 4);
    wire.putShort((short) 4);
    wire.put((byte) 1);
    wire.put((byte) 71);
    wire.put((byte) 0);
    wire.put((byte) 0);
    wire.putLong(payloadOffset);
    wire.putInt(chunk.length);
    wire.putInt(MIP.length);
    wire.putShort((short) 0);
    wire.putShort((short) 0);
    wire.putInt(0xbaadf00d);
    wire.put(chunk);
    wire.putShort((short) name.length);
    wire.put(name);
    return wire.array();
  }

  /** Computes the BA2 component CRC with an initial zero and no final XOR. */
  private static int wireHash(String component) {
    int crc = 0;
    for (byte current : component.getBytes(StandardCharsets.US_ASCII)) {
      crc ^= Byte.toUnsignedInt(current);
      for (int bit = 0; bit < 8; bit++) {
        crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xedb88320);
      }
    }
    return crc;
  }

  /**
   * Frames the opaque mip as a complete RFC 1950 stream with one stored DEFLATE block. Explicit
   * framing keeps fixture bytes independent of a compression provider's version and heuristics.
   */
  private static byte[] zlibStoredBlock(byte[] mip) {
    ByteBuffer stream = ByteBuffer.allocate(mip.length + 11).order(ByteOrder.LITTLE_ENDIAN);
    stream.put((byte) 0x78);
    stream.put((byte) 0x01);
    stream.put((byte) 0x01);
    stream.putShort((short) mip.length);
    stream.putShort((short) ~mip.length);
    stream.put(mip);
    Adler32 adler = new Adler32();
    adler.update(mip);
    stream.order(ByteOrder.BIG_ENDIAN).putInt((int) adler.getValue());
    return stream.array();
  }

  /** Serializes provenance and artifact digests without hashing generator source. */
  private static String manifest(byte[] wire, byte[] encoded) {
    StringBuilder json = new StringBuilder();
    json.append("{\n");
    json.append("  \"schema_version\": 1,\n");
    json.append("  \"corpus_id\": \"jbsa-fo4-dds-v1-wire-vectors-v1\",\n");
    json.append("  \"creator\": \"JBSA project contributors\",\n");
    json.append("  \"spdx_license\": \"CC0-1.0\",\n");
    json.append("  \"redistribution_class\": \"project-authored-redistributable\",\n");
    json.append(
        "  \"source\": \"docs/spec/formats/dds-ba2.md; independently authored DX10 v1 wire vector\",\n");
    json.append("  \"generator\": {\n");
    json.append("    \"path\": \"").append(GENERATOR_PATH).append("\",\n");
    json.append("    \"version\": \"1\",\n");
    json.append("    \"spdx_license\": \"Apache-2.0\",\n");
    json.append("    \"command\": \"").append(COMMAND).append("\"\n");
    json.append("  },\n");
    json.append("  \"fixtures\": [\n");
    json.append("    {\n");
    json.append("      \"id\": \"fo4-dx10-v1-zlib\",\n");
    json.append("      \"path\": \"fo4-dx10-v1-zlib.hex\",\n");
    json.append(
        "      \"representation\": \"lowercase hexadecimal followed by LF; decode before archive use\",\n");
    json.append("      \"wire_size\": ").append(wire.length).append(",\n");
    json.append("      \"expected_disposition\": \"CONFORMING\",\n");
    json.append("      \"wire_sha256\": \"").append(sha256(wire)).append("\",\n");
    json.append("      \"file_sha256\": \"").append(sha256(encoded)).append("\",\n");
    json.append("      \"oracle_sha256\": null\n");
    json.append("    }\n");
    json.append("  ]\n");
    json.append("}\n");
    return json.toString();
  }

  /** Computes a lowercase digest for fixture content, independent of implementation source. */
  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK SHA-256 is unavailable", impossible);
    }
  }
}
