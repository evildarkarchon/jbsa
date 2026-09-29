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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.Deflater;

/**
 * Independent wire recipes and deterministic inventory writing for the three legacy BSA corpora.
 */
final class LegacyBsaFixtureWire {
  private static final byte[] FOLDER = "meshes".getBytes(StandardCharsets.US_ASCII);
  private static final byte[][] NAMES = {
    "a.nif".getBytes(StandardCharsets.US_ASCII), "b.nif".getBytes(StandardCharsets.US_ASCII)
  };
  private static final byte[][] PAYLOADS = {
    "A".repeat(1024).getBytes(StandardCharsets.US_ASCII), HexFormat.of().parseHex("000102ff")
  };
  private static final String REPRESENTATION =
      "lowercase hexadecimal followed by LF; decode before archive use";

  private LegacyBsaFixtureWire() {}

  /** Selects the exact manifest and archive recipe set to materialize. */
  enum Family {
    TES4,
    BSA068,
    BSA069
  }

  /**
   * Writes independently encoded fixtures and a deterministic manifest to an absent or empty root.
   * Fixture payload hashes identify bytes; no source file or runtime is hashed.
   *
   * @param destination caller-owned absent or empty directory
   * @param family archive family whose committed corpus is reproduced
   * @throws IOException if the destination is nonempty or cannot be written
   */
  static void materialize(Path destination, Family family) throws IOException {
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(family, "family");
    if (Files.exists(destination)) {
      if (!Files.isDirectory(destination)) {
        throw new IOException("Fixture destination is not a directory: " + destination);
      }
      try (var entries = Files.list(destination)) {
        if (entries.findAny().isPresent()) {
          throw new IOException("Fixture destination must be empty: " + destination);
        }
      }
    }
    Files.createDirectories(destination);
    Map<String, Object> manifest = manifest(family);
    @SuppressWarnings("unchecked")
    List<Object> fixtures = (List<Object>) manifest.get("fixtures");
    switch (family) {
      case TES4 -> {
        for (String mode : List.of("stored", "zlib", "mixed")) {
          byte[] wire = tes4Archive(mode);
          addTes4(destination, fixtures, mode, wire);
          if (mode.equals("stored")) {
            addTes4(
                destination,
                fixtures,
                "truncated-payload",
                java.util.Arrays.copyOf(wire, wire.length - 1));
          }
          if (mode.equals("mixed")) {
            byte[] mismatch = wire.clone();
            int payloadOffset = ByteBuffer.wrap(mismatch).order(ByteOrder.LITTLE_ENDIAN).getInt(72);
            ByteBuffer.wrap(mismatch).order(ByteOrder.LITTLE_ENDIAN).putInt(payloadOffset, 1025);
            addTes4(destination, fixtures, "decoded-size-mismatch", mismatch);
          }
        }
        // Match the original authored inventory order independently of recipe evaluation order.
        moveTes4MutationsToEnd(fixtures);
      }
      case BSA068 -> {
        for (String game : List.of("fallout3", "new-vegas", "skyrim-le")) {
          String selector =
              switch (game) {
                case "fallout3" -> "fo3";
                case "new-vegas" -> "fnv";
                default -> "tes5";
              };
          for (String mode : List.of("stored", "zlib", "mixed")) {
            for (boolean embedded : List.of(false, true)) {
              String name = game + "-" + mode + (embedded ? "-embedded" : "");
              byte[] wire = bsa068Archive(mode, embedded);
              byte[] file = writeHex(destination, name + ".hex", wire);
              fixtures.add(
                  fields(
                      "id",
                      name,
                      "path",
                      name + ".hex",
                      "game",
                      game,
                      "cli_selector",
                      "-" + selector,
                      "compression",
                      mode,
                      "embedded_names",
                      embedded,
                      "wire_size",
                      wire.length,
                      "wire_sha256",
                      sha256(wire),
                      "file_sha256",
                      sha256(file),
                      "oracle_sha256",
                      null));
            }
          }
        }
      }
      case BSA069 -> {
        for (String game : List.of("skyrim-se", "skyrim-ae")) {
          for (String mode : List.of("stored", "lz4-frame", "mixed")) {
            for (boolean embedded : List.of(false, true)) {
              String name = game + "-" + mode + (embedded ? "-embedded" : "");
              byte[] wire = bsa069Archive(mode, embedded);
              byte[] file = writeHex(destination, name + ".hex", wire);
              fixtures.add(
                  fields(
                      "id",
                      name,
                      "path",
                      name + ".hex",
                      "game",
                      game,
                      "cli_selector",
                      "-sse",
                      "compression",
                      mode,
                      "embedded_names",
                      embedded,
                      "wire_size",
                      wire.length,
                      "wire_sha256",
                      sha256(wire),
                      "file_sha256",
                      sha256(file),
                      "oracle_sha256",
                      null));
            }
          }
        }
      }
    }
    // The 0x68 authoring manifest used Windows text-mode CRLF; retain that byte contract.
    String lineEnding = family == Family.BSA068 ? "\r\n" : "\n";
    Files.writeString(
        destination.resolve("manifest.json"),
        json(manifest).replace("\n", lineEnding) + lineEnding,
        StandardCharsets.UTF_8);
  }

  /** Writes one TES4 vector and its fixture-byte identity. */
  private static void addTes4(Path root, List<Object> fixtures, String name, byte[] wire)
      throws IOException {
    String id = "bsa-067-" + name;
    String path = id + ".hex";
    byte[] file = writeHex(root, path, wire);
    fixtures.add(
        fields(
            "id",
            id,
            "path",
            path,
            "representation",
            REPRESENTATION,
            "wire_size",
            wire.length,
            "wire_sha256",
            sha256(wire),
            "file_sha256",
            sha256(file),
            "source",
            "independently authored BSA-002/003/006/008/009 wire vector; " + name,
            "oracle_sha256",
            null));
  }

  /** Preserves the committed stored, zlib, mixed, then malformed record order. */
  private static void moveTes4MutationsToEnd(List<Object> fixtures) {
    Object truncated = fixtures.remove(1);
    Object mismatch = fixtures.remove(fixtures.size() - 1);
    fixtures.add(truncated);
    fixtures.add(mismatch);
  }

  /** Encodes the base 0x67 folder, records, names, and stored or zlib payload framing. */
  private static byte[] tes4Archive(String mode) {
    int flags = mode.equals("stored") ? 0x683 : 0x687;
    byte[] nameTable = nameTable();
    byte[] folderBlock = folderBlock();
    int dataOffset = 36 + 16 + folderBlock.length + 32 + nameTable.length;
    ByteArrayOutputStream records = new ByteArrayOutputStream();
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    for (int ordinal = 0; ordinal < NAMES.length; ordinal++) {
      boolean compressed = ordinal == 0 ? !mode.equals("stored") : mode.equals("zlib");
      byte[] packed = compressed ? compressedZlib(PAYLOADS[ordinal]) : PAYLOADS[ordinal];
      int toggle = compressed != ((flags & 4) != 0) ? 0x40000000 : 0;
      littleLong(records, wireHash(NAMES[ordinal], true));
      littleInt(records, packed.length | toggle);
      littleInt(records, dataOffset + data.size());
      data.writeBytes(packed);
    }
    ByteArrayOutputStream archive = new ByteArrayOutputStream();
    archive.writeBytes(new byte[] {'B', 'S', 'A', 0});
    for (int value : new int[] {103, 36, flags, 1, 2, 7, nameTable.length, 1}) {
      littleInt(archive, value);
    }
    littleLong(archive, wireHash(FOLDER, false));
    littleInt(archive, 2);
    littleInt(archive, 52 + nameTable.length);
    archive.writeBytes(folderBlock);
    archive.writeBytes(records.toByteArray());
    archive.writeBytes(nameTable);
    archive.writeBytes(data.toByteArray());
    return archive.toByteArray();
  }

  /** Applies version 0x68 flags and optional full-name payload prefixes to the base recipe. */
  private static byte[] bsa068Archive(String mode, boolean embedded) {
    byte[] wire = tes4Archive(mode);
    ByteBuffer bytes = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
    bytes.putInt(4, 104);
    bytes.putInt(12, (bytes.getInt(12) & ~0x600) | (embedded ? 0x100 : 0));
    if (!embedded) return wire;

    // Insert backwards so original payload offsets stay valid until each prefix is applied.
    for (int ordinal = NAMES.length - 1; ordinal >= 0; ordinal--) {
      int record = 60 + ordinal * 16;
      int size = bytes.getInt(record + 8);
      int offset = bytes.getInt(record + 12);
      byte[] fullName =
          ("meshes\\" + new String(NAMES[ordinal], StandardCharsets.US_ASCII))
              .getBytes(StandardCharsets.US_ASCII);
      byte[] prefix = new byte[fullName.length + 1];
      prefix[0] = (byte) fullName.length;
      System.arraycopy(fullName, 0, prefix, 1, fullName.length);
      byte[] expanded = new byte[wire.length + prefix.length];
      System.arraycopy(wire, 0, expanded, 0, offset);
      System.arraycopy(prefix, 0, expanded, offset, prefix.length);
      System.arraycopy(wire, offset, expanded, offset + prefix.length, wire.length - offset);
      wire = expanded;
      bytes = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
      bytes.putInt(record + 8, size + prefix.length);
      for (int later = ordinal + 1; later < NAMES.length; later++) {
        int field = 60 + later * 16 + 12;
        bytes.putInt(field, bytes.getInt(field) + prefix.length);
      }
    }
    return wire;
  }

  /** Encodes the 0x69 folder record and the family's LZ4 frame payload envelope. */
  private static byte[] bsa069Archive(String mode, boolean embedded) {
    boolean anyCompression = !mode.equals("stored");
    int flags = (anyCompression ? 0x87 : 0x83) | (embedded ? 0x100 : 0);
    byte[] nameTable = nameTable();
    byte[] folderBlock = folderBlock();
    int dataOffset = 36 + 24 + folderBlock.length + 32 + nameTable.length;
    ByteArrayOutputStream records = new ByteArrayOutputStream();
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    for (int ordinal = 0; ordinal < NAMES.length; ordinal++) {
      boolean compressed = ordinal == 0 ? anyCompression : mode.equals("lz4-frame");
      byte[] packed = compressed ? lz4Payload(PAYLOADS[ordinal]) : PAYLOADS[ordinal];
      if (embedded) {
        byte[] fullName =
            ("meshes\\" + new String(NAMES[ordinal], StandardCharsets.US_ASCII))
                .getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream withName = new ByteArrayOutputStream();
        withName.write(fullName.length);
        withName.writeBytes(fullName);
        withName.writeBytes(packed);
        packed = withName.toByteArray();
      }
      int toggle = compressed != ((flags & 4) != 0) ? 0x40000000 : 0;
      littleLong(records, wireHash(NAMES[ordinal], true));
      littleInt(records, packed.length | toggle);
      littleInt(records, dataOffset + data.size());
      data.writeBytes(packed);
    }
    ByteArrayOutputStream archive = new ByteArrayOutputStream();
    archive.writeBytes(new byte[] {'B', 'S', 'A', 0});
    for (int value : new int[] {105, 36, flags, 1, 2, FOLDER.length + 1, nameTable.length, 1}) {
      littleInt(archive, value);
    }
    littleLong(archive, wireHash(FOLDER, false));
    littleInt(archive, 2);
    littleInt(archive, 0);
    littleInt(archive, 60 + nameTable.length);
    littleInt(archive, 0);
    archive.writeBytes(folderBlock);
    archive.writeBytes(records.toByteArray());
    archive.writeBytes(nameTable);
    archive.writeBytes(data.toByteArray());
    return archive.toByteArray();
  }

  /** Frames one payload with its decoded size and a complete level-nine zlib stream. */
  private static byte[] compressedZlib(byte[] payload) {
    Deflater deflater = new Deflater(9);
    try {
      deflater.setInput(payload);
      deflater.finish();
      ByteArrayOutputStream encoded = new ByteArrayOutputStream();
      byte[] buffer = new byte[256];
      while (!deflater.finished()) {
        int count = deflater.deflate(buffer);
        encoded.write(buffer, 0, count);
      }
      ByteArrayOutputStream framed = new ByteArrayOutputStream();
      littleInt(framed, payload.length);
      framed.writeBytes(encoded.toByteArray());
      return framed.toByteArray();
    } finally {
      deflater.end();
    }
  }

  /** Frames literal payload bytes in the independently worked 4 MiB LZ4 profile. */
  private static byte[] lz4Payload(byte[] payload) {
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    littleInt(framed, payload.length);
    framed.writeBytes(HexFormat.of().parseHex("04224d18607073"));
    littleInt(framed, 0x80000000 | payload.length);
    framed.writeBytes(payload);
    littleInt(framed, 0);
    return framed.toByteArray();
  }

  /** Computes the independently specified ASCII BSA name hash. */
  private static long wireHash(byte[] name, boolean extension) {
    int dot = -1;
    if (extension) {
      for (int index = name.length - 1; index >= 0; index--) {
        if (name[index] == '.') {
          dot = index;
          break;
        }
      }
    }
    int stemLength = dot < 0 ? name.length : dot;
    int low =
        Byte.toUnsignedInt(name[stemLength - 1])
            | (stemLength << 16)
            | (Byte.toUnsignedInt(name[0]) << 24);
    if (stemLength > 2) low |= Byte.toUnsignedInt(name[stemLength - 2]) << 8;
    if (dot >= 0) {
      String suffix = new String(name, dot, name.length - dot, StandardCharsets.US_ASCII);
      low |=
          switch (suffix) {
            case ".kf" -> 0x80;
            case ".nif" -> 0x8000;
            case ".dds" -> 0x8080;
            case ".wav" -> 0x80000000;
            default -> 0;
          };
    }
    long stem = 0;
    for (int index = 1; index < stemLength - 2; index++) {
      stem = (Byte.toUnsignedInt(name[index]) + 65599L * stem) & 0xffffffffL;
    }
    long suffix = 0;
    for (int index = stemLength; index < name.length; index++) {
      suffix = (Byte.toUnsignedInt(name[index]) + 65599L * suffix) & 0xffffffffL;
    }
    return (((stem + suffix) & 0xffffffffL) << 32) | Integer.toUnsignedLong(low);
  }

  /** Returns the canonical null-terminated file name table. */
  private static byte[] nameTable() {
    ByteArrayOutputStream table = new ByteArrayOutputStream();
    for (byte[] name : NAMES) {
      table.writeBytes(name);
      table.write(0);
    }
    return table.toByteArray();
  }

  /** Returns the length-prefixed, null-terminated folder block. */
  private static byte[] folderBlock() {
    ByteArrayOutputStream block = new ByteArrayOutputStream();
    block.write(FOLDER.length + 1);
    block.writeBytes(FOLDER);
    block.write(0);
    return block.toByteArray();
  }

  /** Writes the little-endian low 32 bits of one integer. */
  private static void littleInt(ByteArrayOutputStream output, int value) {
    for (int shift = 0; shift < 32; shift += 8) output.write(value >>> shift);
  }

  /** Writes one little-endian 64-bit integer. */
  private static void littleLong(ByteArrayOutputStream output, long value) {
    for (int shift = 0; shift < 64; shift += 8) output.write((int) (value >>> shift));
  }

  /** Persists lowercase hex and LF, returning exact text bytes for its manifest digest. */
  private static byte[] writeHex(Path root, String name, byte[] wire) throws IOException {
    byte[] file = (HexFormat.of().formatHex(wire) + "\n").getBytes(StandardCharsets.US_ASCII);
    Files.write(root.resolve(name), file);
    return file;
  }

  /** Computes a fixture-byte identity without inspecting any generator source. */
  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required SHA-256 algorithm is unavailable", impossible);
    }
  }

  /** Builds the exact corpus-level provenance fields and a writable fixture record list. */
  private static Map<String, Object> manifest(Family family) {
    String prefix = "jbsa-test-support/src/main/java/io/github/evildarkarchon/jbsa/fixtures/";
    String command = "java -cp jbsa-test-support/target/classes/java/main ";
    List<Object> fixtures = new ArrayList<>();
    if (family == Family.TES4) {
      return fields(
          "schema_version", 1,
          "corpus_id", "jbsa-bsa-067-wire-vectors-v1",
          "creator", "JBSA project contributors",
          "spdx_license", "CC0-1.0",
          "redistribution_class", "project-authored-redistributable",
          "source", "docs/spec/formats/versioned-bsa.md; independently authored wire vectors",
          "generator",
              fields(
                  "path",
                  prefix + "Tes4FixtureGenerator.java",
                  "version",
                  "2",
                  "spdx_license",
                  "Apache-2.0",
                  "command",
                  command + Tes4FixtureGenerator.class.getName() + " --output <directory>"),
          "fixtures", fixtures);
    }
    String className =
        family == Family.BSA068
            ? Bsa068FixtureGenerator.class.getName()
            : Bsa069FixtureGenerator.class.getName();
    String simpleName =
        family == Family.BSA068 ? "Bsa068FixtureGenerator.java" : "Bsa069FixtureGenerator.java";
    List<Object> generators =
        List.of(
            fields(
                "path", prefix + simpleName,
                "version", "2",
                "command", command + className + " --output <directory>"));
    List<Object> payloads =
        List.of(
            fields("name", "meshes/a.nif", "size", 1024, "sha256", sha256(PAYLOADS[0])),
            fields("name", "meshes/b.nif", "size", 4, "sha256", sha256(PAYLOADS[1])));
    if (family == Family.BSA068) {
      return fields(
          "schema_version", 1,
          "corpus_id", "jbsa-bsa-068-wire-vectors-v1",
          "spdx_license", "CC0-1.0",
          "creator", "JBSA project contributors",
          "redistribution_class", "project-authored-redistributable",
          "source",
              "docs/spec/formats/versioned-bsa.md; synthetic shared-family game-selector vectors",
          "generators", generators,
          "payloads", payloads,
          "fixtures", fixtures);
    }
    return fields(
        "schema_version",
        1,
        "corpus_id",
        "jbsa-bsa-069-wire-vectors-v1",
        "creator",
        "JBSA project contributors",
        "spdx_license",
        "CC0-1.0",
        "redistribution_class",
        "project-authored-redistributable",
        "source",
        "docs/spec/formats/versioned-bsa.md; independently authored SSE/AE vectors",
        "generators",
        generators,
        "validators",
        List.of(fields("path", "build/validate-bsa-wire.py")),
        "payloads",
        payloads,
        "fixtures",
        fixtures);
  }

  /** Creates an insertion-ordered JSON object from alternating string keys and values. */
  private static Map<String, Object> fields(Object... entries) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < entries.length; index += 2) {
      values.put((String) entries[index], entries[index + 1]);
    }
    return values;
  }

  /** Serializes manifest objects with the legacy two-space JSON layout and stable line endings. */
  private static String json(Object value) {
    StringBuilder output = new StringBuilder();
    appendJson(output, value, 0);
    return output.toString();
  }

  /** Appends one JSON value, preserving ordered object fields and list members. */
  private static void appendJson(StringBuilder output, Object value, int indent) {
    if (value instanceof Map<?, ?> map) {
      output.append('{');
      if (!map.isEmpty()) {
        output.append('\n');
        int index = 0;
        for (var entry : map.entrySet()) {
          output.append(" ".repeat(indent + 2));
          appendJson(output, entry.getKey(), indent + 2);
          output.append(": ");
          appendJson(output, entry.getValue(), indent + 2);
          output.append(++index == map.size() ? '\n' : ',').append(index == map.size() ? "" : "\n");
        }
        output.append(" ".repeat(indent));
      }
      output.append('}');
    } else if (value instanceof List<?> list) {
      output.append('[');
      if (!list.isEmpty()) {
        output.append('\n');
        for (int index = 0; index < list.size(); index++) {
          output.append(" ".repeat(indent + 2));
          appendJson(output, list.get(index), indent + 2);
          output.append(index + 1 == list.size() ? '\n' : ',');
          if (index + 1 < list.size()) output.append('\n');
        }
        output.append(" ".repeat(indent));
      }
      output.append(']');
    } else if (value instanceof String string) {
      output.append('"');
      for (int index = 0; index < string.length(); index++) {
        char letter = string.charAt(index);
        switch (letter) {
          case '"' -> output.append("\\\"");
          case '\\' -> output.append("\\\\");
          case '\n' -> output.append("\\n");
          case '\r' -> output.append("\\r");
          case '\t' -> output.append("\\t");
          default -> output.append(letter);
        }
      }
      output.append('"');
    } else {
      output.append(value);
    }
  }
}
