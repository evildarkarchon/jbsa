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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;

/**
 * Materializes independently authored Fallout 4 General BA2 v1 wire vectors. Generated fixture
 * bytes are CC0-1.0; this test-support implementation is Apache-2.0 and never calls the product
 * archive writer.
 */
public final class Fo4GeneralBa2FixtureGenerator {
  private static final String GENERATOR_PATH =
      "jbsa-test-support/src/main/java/io/github/evildarkarchon/jbsa/fixtures/"
          + "Fo4GeneralBa2FixtureGenerator.java";
  private static final String COMMAND =
      "java -cp jbsa-test-support/target/classes/java/main "
          + "io.github.evildarkarchon.jbsa.fixtures.Fo4GeneralBa2FixtureGenerator "
          + "--output <directory>";
  private static final byte[] LARGE_PAYLOAD = new byte[1024];
  private static final byte[] SMALL_PAYLOAD = HexFormat.of().parseHex("000102ff");

  static {
    Arrays.fill(LARGE_PAYLOAD, (byte) 'A');
  }

  private Fo4GeneralBa2FixtureGenerator() {}

  /**
   * Generates the complete corpus into an absent or empty directory.
   *
   * @param args exactly {@code --output <directory>}
   * @throws IllegalArgumentException if the command-line shape is unsupported
   * @throws IOException if the destination cannot be written or is nonempty
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 2 || !"--output".equals(args[0])) {
      throw new IllegalArgumentException("Expected --output <directory>");
    }
    materialize(Path.of(args[1]));
  }

  /**
   * Writes deterministic hexadecimal archives and their provenance manifest without replacing
   * accepted fixture bytes.
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
    List<Fixture> fixtures = fixtures();
    List<byte[]> encoded = new ArrayList<>(fixtures.size());
    for (Fixture fixture : fixtures) {
      byte[] text =
          (HexFormat.of().formatHex(fixture.wire()) + "\n").getBytes(StandardCharsets.US_ASCII);
      Files.write(outputRoot.resolve("fo4-general-" + fixture.id() + ".hex"), text);
      encoded.add(text);
    }
    Files.writeString(outputRoot.resolve("manifest.json"), manifest(fixtures, encoded));
  }

  /** Builds the fixed corpus and applies each fault to an independently serialized base archive. */
  private static List<Fixture> fixtures() {
    List<Fixture> fixtures = new ArrayList<>();
    for (String mode : List.of("stored", "zlib", "mixed")) {
      fixtures.add(new Fixture(mode, archive(mode), "CONFORMING"));
    }
    for (String mode : List.of("raw-deflate", "raw-lz4", "lz4-frame")) {
      fixtures.add(new Fixture(mode, archive(mode), "REJECTED_AT_PAYLOAD"));
    }
    // The offsets below target the 24-byte header and two fixed 36-byte entry records.
    fixtures.add(
        new Fixture(
            "decoded-size-mismatch",
            mutateInt(archive("mixed"), 24 + 28, 1025),
            "REJECTED_AT_PAYLOAD"));
    fixtures.add(
        new Fixture(
            "truncated-payload",
            mutateLong(archive("stored"), 24 + 16, archive("stored").length - 1L),
            "REJECTED_AT_STRUCTURE"));
    fixtures.add(
        new Fixture(
            "missing-name-table", mutateLong(archive("stored"), 16, 0), "TOLERATED_NONCANONICAL"));
    fixtures.add(
        new Fixture(
            "sentinel-mismatch",
            mutateInt(archive("stored"), 24 + 32, 0),
            "TOLERATED_NONCANONICAL"));
    fixtures.add(
        new Fixture(
            "hash-mismatch", mutateInt(archive("stored"), 24, 1), "TOLERATED_NONCANONICAL"));
    fixtures.add(
        new Fixture(
            "chunk-count", mutateByte(archive("stored"), 24 + 13, 2), "REJECTED_AT_STRUCTURE"));
    fixtures.add(
        new Fixture(
            "partial-overlap",
            mutateLong(archive("stored"), 60 + 16, 97),
            "REJECTED_AT_STRUCTURE"));
    ByteArrayOutputStream trailing = new ByteArrayOutputStream();
    trailing.writeBytes(archive("stored"));
    trailing.writeBytes("TRAIL".getBytes(StandardCharsets.US_ASCII));
    fixtures.add(new Fixture("trailing-data", trailing.toByteArray(), "TOLERATED_NONCANONICAL"));
    return fixtures;
  }

  /**
   * Serializes two ordered General BA2 records and their payloads directly from the wire layout.
   */
  private static byte[] archive(String mode) {
    ByteBuffer records = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    for (int index = 0; index < 2; index++) {
      byte[] payload = index == 0 ? LARGE_PAYLOAD : SMALL_PAYLOAD;
      boolean compressed = mode.equals("zlib") || (index == 0 && !mode.equals("stored"));
      byte[] stored =
          !compressed
              ? payload
              : switch (mode) {
                case "zlib", "mixed" -> deflate(payload, false);
                case "raw-deflate" -> deflate(payload, true);
                case "raw-lz4" -> rawLz4(payload);
                case "lz4-frame" -> lz4Frame(payload);
                default -> throw new IllegalArgumentException("Unknown codec mode: " + mode);
              };
      records.putInt(wireHash(index == 0 ? "a" : "b"));
      records.put(new byte[] {'n', 'i', 'f', 0});
      records.putInt(wireHash("meshes"));
      records.put((byte) 0);
      records.put((byte) 1);
      records.putShort((short) 16);
      records.putLong(96L + data.size());
      records.putInt(compressed ? stored.length : 0);
      records.putInt(payload.length);
      records.putInt(0xbaadf00d);
      data.writeBytes(stored);
    }
    ByteArrayOutputStream names = new ByteArrayOutputStream();
    for (String name : List.of("meshes/a.nif", "meshes/b.nif")) {
      byte[] ascii = name.getBytes(StandardCharsets.US_ASCII);
      names.write(ascii.length);
      names.write(0);
      names.writeBytes(ascii);
    }
    ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
    header.put("BTDX".getBytes(StandardCharsets.US_ASCII));
    header.putInt(1);
    header.put("GNRL".getBytes(StandardCharsets.US_ASCII));
    header.putInt(2);
    header.putLong(96L + data.size());
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    result.writeBytes(header.array());
    result.writeBytes(records.array());
    result.writeBytes(data.toByteArray());
    result.writeBytes(names.toByteArray());
    return result.toByteArray();
  }

  /** Computes the General BA2 component CRC with initial zero and no final XOR. */
  private static int wireHash(String component) {
    int crc = 0;
    for (byte raw : component.getBytes(StandardCharsets.US_ASCII)) {
      int octet = raw >= 'A' && raw <= 'Z' ? raw + 32 : raw;
      crc ^= octet;
      for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) == 1 ? 0xedb88320 : 0);
    }
    return crc;
  }

  /**
   * Uses JDK zlib to encode the same level-nine wrapped or raw deflate payload as the wire recipe.
   */
  private static byte[] deflate(byte[] payload, boolean raw) {
    Deflater compressor = new Deflater(9, raw);
    try {
      compressor.setInput(payload);
      compressor.finish();
      ByteArrayOutputStream result = new ByteArrayOutputStream();
      byte[] window = new byte[256];
      while (!compressor.finished()) {
        int count = compressor.deflate(window);
        result.write(window, 0, count);
      }
      return result.toByteArray();
    } finally {
      compressor.end();
    }
  }

  /** Encodes a literal-only raw LZ4 block, including its extended 1,024-byte literal length. */
  private static byte[] rawLz4(byte[] payload) {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    result.writeBytes(HexFormat.of().parseHex("f0fffffff4"));
    result.writeBytes(payload);
    return result.toByteArray();
  }

  /** Encodes an independent-block LZ4 frame with the specified 60 40 descriptor checksum. */
  private static byte[] lz4Frame(byte[] payload) {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    result.writeBytes(HexFormat.of().parseHex("04224d18604082"));
    result.writeBytes(littleEndianInt(0x80000000 | payload.length));
    result.writeBytes(payload);
    result.writeBytes(new byte[4]);
    return result.toByteArray();
  }

  /** Returns one little-endian integer for the independently framed LZ4 block length. */
  private static byte[] littleEndianInt(int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  /** Changes exactly one four-byte field in a separately generated archive. */
  private static byte[] mutateInt(byte[] source, int offset, int value) {
    byte[] changed = source.clone();
    ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    return changed;
  }

  /** Changes exactly one eight-byte field in a separately generated archive. */
  private static byte[] mutateLong(byte[] source, int offset, long value) {
    byte[] changed = source.clone();
    ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value);
    return changed;
  }

  /** Changes exactly one byte in a separately generated archive. */
  private static byte[] mutateByte(byte[] source, int offset, int value) {
    byte[] changed = source.clone();
    changed[offset] = (byte) value;
    return changed;
  }

  /** Writes the ordered manifest with content digests and the current Java generation command. */
  private static String manifest(List<Fixture> fixtures, List<byte[]> encoded) {
    StringBuilder json = new StringBuilder();
    json.append("{\n");
    json.append("  \"schema_version\": 1,\n");
    json.append("  \"corpus_id\": \"jbsa-fo4-general-wire-vectors-v1\",\n");
    json.append("  \"creator\": \"JBSA project contributors\",\n");
    json.append("  \"spdx_license\": \"CC0-1.0\",\n");
    json.append("  \"redistribution_class\": \"project-authored-redistributable\",\n");
    json.append(
        "  \"source\": \"docs/spec/formats/general-ba2.md; independently authored wire vectors\",\n");
    json.append("  \"generator\": {\n");
    json.append("    \"path\": \"").append(GENERATOR_PATH).append("\",\n");
    json.append("    \"version\": \"2\",\n");
    json.append("    \"spdx_license\": \"Apache-2.0\",\n");
    json.append("    \"command\": \"").append(COMMAND).append("\"\n");
    json.append("  },\n");
    json.append("  \"fixtures\": [\n");
    for (int index = 0; index < fixtures.size(); index++) {
      Fixture fixture = fixtures.get(index);
      String name = "fo4-general-" + fixture.id();
      json.append("    {\n");
      json.append("      \"id\": \"").append(name).append("\",\n");
      json.append("      \"path\": \"").append(name).append(".hex\",\n");
      json.append(
          "      \"representation\": \"lowercase hexadecimal followed by LF; decode before archive use\",\n");
      json.append("      \"wire_size\": ").append(fixture.wire().length).append(",\n");
      json.append("      \"expected_disposition\": \"")
          .append(fixture.disposition())
          .append("\",\n");
      json.append("      \"wire_sha256\": \"").append(sha256(fixture.wire())).append("\",\n");
      json.append("      \"file_sha256\": \"").append(sha256(encoded.get(index))).append("\",\n");
      json.append("      \"oracle_sha256\": null\n");
      json.append(index == fixtures.size() - 1 ? "    }\n" : "    },\n");
    }
    json.append("  ]\n");
    json.append("}\n");
    return json.toString();
  }

  /** Returns a lowercase SHA-256 for manifesting fixture bytes, never implementation source. */
  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK SHA-256 is unavailable", impossible);
    }
  }

  /** Retains one independent archive and its expected public disposition. */
  private record Fixture(String id, byte[] wire, String disposition) {}
}
