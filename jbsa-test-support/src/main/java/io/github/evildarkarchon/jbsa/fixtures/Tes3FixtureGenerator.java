package io.github.evildarkarchon.jbsa.fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Materializes independently authored TES3 wire vectors without invoking a product archive codec.
 * The generated fixture data is CC0-1.0; this implementation remains Apache-2.0.
 */
public final class Tes3FixtureGenerator {
  private static final String CANONICAL_HEX =
      "00010000310000000200000004000000000000000500000004000000000000000d000000"
          + "6d65736865735c612e6e696600736f756e645c622e77617600"
          + "081673683271b754176f756ed04597bb4e49460000010203ff";
  private static final String REPRESENTATION =
      "lowercase hexadecimal followed by LF; decode before archive use";
  private static final String COMMAND =
      "java -cp jbsa-test-support/target/classes/java/main "
          + "io.github.evildarkarchon.jbsa.fixtures.Tes3FixtureGenerator "
          + "--output <empty-directory>";

  private Tes3FixtureGenerator() {}

  /**
   * Runs deterministic generation from the command line.
   *
   * @param args exactly {@code --output <empty-directory>}
   * @throws IllegalArgumentException if the arguments have another shape
   * @throws IOException if the destination is not empty or a fixture cannot be written
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 2 || !"--output".equals(args[0])) {
      throw new IllegalArgumentException("Usage: Tes3FixtureGenerator --output <empty-directory>");
    }
    materialize(Path.of(args[1]));
  }

  /**
   * Writes seven TES3 archive vectors and their provenance manifest to an empty directory.
   *
   * @param outputRoot absent or empty destination directory
   * @throws NullPointerException if the destination is null
   * @throws IOException if the destination is nonempty or an output cannot be written
   */
  public static void materialize(Path outputRoot) throws IOException {
    Objects.requireNonNull(outputRoot, "outputRoot");
    requireEmptyDestination(outputRoot);
    Files.createDirectories(outputRoot);
    Map<String, byte[]> vectors = vectors();
    for (var vector : vectors.entrySet()) {
      Files.writeString(
          outputRoot.resolve("tes3-" + vector.getKey() + ".hex"),
          HexFormat.of().formatHex(vector.getValue()) + "\n",
          StandardCharsets.US_ASCII);
    }
    Files.writeString(
        outputRoot.resolve("manifest.json"), manifest(vectors), StandardCharsets.UTF_8);
  }

  /** Constructs one canonical archive and six single-fault variants in stable manifest order. */
  private static Map<String, byte[]> vectors() {
    byte[] canonical = HexFormat.of().parseHex(CANONICAL_HEX);
    Map<String, byte[]> vectors = new LinkedHashMap<>();
    vectors.put("stored", canonical);
    vectors.put("truncated-payload", Arrays.copyOf(canonical, canonical.length - 1));

    byte[] impossible = canonical.clone();
    ByteBuffer.wrap(impossible).order(ByteOrder.LITTLE_ENDIAN).putInt(8, -1);
    vectors.put("impossible-count", impossible);

    byte[] overlap = canonical.clone();
    overlap[24] = 3;
    vectors.put("partial-overlap", overlap);

    byte[] offset = canonical.clone();
    offset[32] = 1;
    vectors.put("name-offset", offset);

    byte[] storedHash = canonical.clone();
    storedHash[61] ^= 1;
    vectors.put("stored-hash", storedHash);

    vectors.put("trailing-data", Arrays.copyOf(canonical, canonical.length + 1));
    return vectors;
  }

  /** Formats the reviewed corpus identity and digest inventory using stable UTF-8/LF bytes. */
  private static String manifest(Map<String, byte[]> vectors) {
    StringBuilder json =
        new StringBuilder(
            """
            {
              "schema_version": 1,
              "corpus_id": "jbsa-tes3-wire-vectors-v1",
              "creator": "JBSA project contributors",
              "spdx_license": "CC0-1.0",
              "redistribution_class": "project-authored-redistributable",
              "generated_on": "2026-09-08",
              "reference_snapshot_revision": "fd1e36020b2b5b6217e553dc0038983146a2e2dd",
              "generator": {
                "path": "jbsa-test-support/src/main/java/io/github/evildarkarchon/jbsa/fixtures/Tes3FixtureGenerator.java",
                "version": "2",
                "spdx_license": "Apache-2.0",
                "command": "%s"
              },
              "fixtures": [
            """
                .formatted(COMMAND));
    int index = 0;
    for (var vector : vectors.entrySet()) {
      String name = "tes3-" + vector.getKey();
      byte[] wire = vector.getValue();
      byte[] encoded = (HexFormat.of().formatHex(wire) + "\n").getBytes(StandardCharsets.US_ASCII);
      json.append(
          """
                {
                  "id": "%s",
                  "path": "%s.hex",
                  "representation": "%s",
                  "wire_size": %d,
                  "wire_sha256": "%s",
                  "file_sha256": "%s",
                  "source": "independently authored JBSA-TES3-002/003 wire vector; one-field mutation %s",
                  "oracle_sha256": null
                }%s
            """
              .formatted(
                  name,
                  name,
                  REPRESENTATION,
                  wire.length,
                  sha256(wire),
                  sha256(encoded),
                  vector.getKey(),
                  ++index < vectors.size() ? "," : ""));
    }
    return json.append("  ]\n}\n").toString();
  }

  /** Refuses to replace committed or staged evidence through ordinary generation. */
  private static void requireEmptyDestination(Path outputRoot) throws IOException {
    if (!Files.exists(outputRoot)) {
      return;
    }
    if (!Files.isDirectory(outputRoot)) {
      throw new IOException("Fixture output is not a directory: " + outputRoot);
    }
    try (var children = Files.list(outputRoot)) {
      if (children.findAny().isPresent()) {
        throw new IOException("Fixture output directory must be empty: " + outputRoot);
      }
    }
  }

  /** Returns a lowercase data digest for fixture integrity, never for source-file identity. */
  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("The Java runtime must support SHA-256", exception);
    }
  }
}
