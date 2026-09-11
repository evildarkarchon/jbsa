package io.github.evildarkarchon.jbsa.verification;

import io.github.evildarkarchon.jbsa.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Build-only CV1 observation adapter; reads exclusively through the public library exports. */
public final class BsaPublicObservation {
  private BsaPublicObservation() {}

  /** Emits complete public semantic evidence or a structured operational failure for one input. */
  public static void main(String[] arguments) throws Exception {
    try {
      Path input = Path.of(arguments[0]);
      String operation = arguments.length > 1 ? arguments[1] : "decode";
      boolean version104 = arguments.length > 3 && arguments[3].equals("bsa-068");
      if (operation.equals("encode")) {
        String codec = arguments[2];
        var compression =
            switch (codec) {
              case "stored" -> PackOptions.Compression.STORED;
              case "raw-lz4" -> PackOptions.Compression.LZ4_RAW;
              case "lz4-frame" -> PackOptions.Compression.LZ4_FRAME;
              default -> PackOptions.Compression.ZLIB;
            };
        Map<NormalizedNameIdentity, PackOptions.Compression> overrides =
            codec.equals("mixed")
                ? Map.of(
                    new NormalizedNameIdentity("meshes\\b.nif"), PackOptions.Compression.STORED)
                : Map.of();
        Path output = Path.of("candidate.bsa");
        BethesdaArchives.standard()
            .pack(
                new PackRequest(
                    output,
                    version104 ? ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA : ArchiveFamily.TES4_BSA,
                    new ArchiveEncoding(
                        Optional.of(new WireVersion(version104 ? 104 : 103)),
                        Optional.empty(),
                        OptionalLong.empty()),
                    Optional.empty(),
                    List.of(new PackSource.DetectedPath(input)),
                    TargetPolicy.FAIL,
                    DiagnosticPolicy.standard(),
                    ResourceLimits.standard(),
                    new WorkerSelection.UpTo(1),
                    new PackOptions(
                        List.of(),
                        compression,
                        false,
                        new PackOptions.Splitting.UpToBytes(0),
                        FlagSelection.AUTOMATIC,
                        FlagSelection.AUTOMATIC,
                        overrides),
                    Optional.empty()),
                OperationControl.standard());
        System.out.println(json(observe(output)));
      } else if (operation.equals("extract")) {
        BethesdaArchives.standard()
            .extract(
                ExtractRequest.standard(input, Path.of("extracted")), OperationControl.standard());
        System.out.println(json(fields("extracted", true)));
      } else {
        var projection = observe(input);
        if (operation.equals("oracle-to-jbsa")) {
          BethesdaArchives.standard()
              .extract(ExtractRequest.standard(input, Path.of(".")), OperationControl.standard());
        }
        System.out.println(json(projection));
      }
    } catch (ArchiveException failure) {
      System.out.println(
          json(
              fields(
                  "failure_kind",
                  failure.kind().name(),
                  "diagnostics",
                  failure.diagnostics().stream().map(BsaPublicObservation::diagnostic).toList())));
    }
  }

  /**
   * Consumes every entry through EOF and records names, wire facts, diagnostics and payload hashes.
   */
  public static Map<String, Object> observe(Path path) throws Exception {
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard())) {
      var inspection = archive.inspection();
      var header = (ArchiveMetadata.VersionedBsa) inspection.metadata();
      List<Object> entries = new ArrayList<>();
      Map<Long, Object> folders = new LinkedHashMap<>();
      List<Object> diagnostics = new ArrayList<>();
      inspection.assessment().diagnostics().forEach(value -> diagnostics.add(diagnostic(value)));
      for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
        ArchiveEntry entry = archive.entry(ordinal);
        EntryMetadata metadata = entry.metadata();
        var wire = (EntryMetadata.VersionedBsa) metadata.facts();
        String folderHash = hex(wire.folderHash());
        String fileHash = hex(wire.nameHash());
        folders.putIfAbsent(
            wire.folderOrdinal(), fields("ordinal", wire.folderOrdinal(), "hash", folderHash));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (EntryContent content = entry.openContent()) {
          ByteBuffer buffer = ByteBuffer.allocate(65536);
          while (content.read(buffer) != -1) {
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
          }
          content
              .assessment()
              .orElseThrow()
              .diagnostics()
              // Payload assessments include the already retained structural evidence. Count it
              // once.
              .stream()
              .filter(value -> !inspection.assessment().diagnostics().contains(value))
              .forEach(value -> diagnostics.add(diagnostic(value)));
        }
        entries.add(
            fields(
                "decoded_name",
                metadata.displayName(),
                "wire_name_bytes",
                fields(
                    "folder",
                    wireName(metadata, "folder"),
                    "basename",
                    wireName(metadata, "basename")),
                "normalized_name_identity",
                metadata.normalizedNameIdentity().map(NormalizedNameIdentity::value).orElse(null),
                "wire_hashes",
                fields("folder", folderHash, "file", fileHash),
                "logical_size",
                metadata.decodedSize(),
                "stored_size",
                metadata.storedSize(),
                "decoded_size",
                metadata.decodedSize(),
                "compression_state",
                wire.compressed() ? "zlib" : "stored",
                "flags",
                fields("compression_toggle", (wire.sizeAndCompressionToggle() & 0x40000000L) != 0),
                "payload_sha256",
                HexFormat.of().formatHex(digest.digest()),
                "folder_hash",
                folderHash,
                "file_hash",
                fileHash,
                "embedded_name",
                wireName(metadata, "embedded")));
      }
      return fields(
          "archive_family",
          header.family() == ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA ? "bsa-068" : "bsa-067",
          "wire_version",
          header.encoding().wireVersion().orElseThrow().value(),
          "disposition",
          inspection.assessment().disposition().name(),
          "entry_count",
          archive.entryCount(),
          "archive_flags",
          header.archiveFlags(),
          "file_flags",
          header.fileFlags(),
          "folder_order",
          new ArrayList<>(folders.values()),
          "entries",
          entries,
          "diagnostics",
          diagnostics);
    }
  }

  /** Projects stable structured diagnostics while excluding incidental absolute input paths. */
  private static Object diagnostic(Diagnostic value) {
    var location = value.location();
    return fields(
        "identifier",
        value.identifier(),
        "severity",
        value.severity().name(),
        "operation",
        value.operation().name(),
        "affected",
        fields(
            "entry_ordinal",
            location.entryOrdinal().isPresent() ? location.entryOrdinal().getAsLong() : null,
            "entry_name",
            location.entryName().orElse(null),
            "field",
            location.field().orElse(null),
            "byte_span",
            location
                .byteSpan()
                .map(span -> fields("offset", span.offset(), "length", span.length()))
                .orElse(null)),
        "values",
        value.values());
  }

  private static String wireName(EntryMetadata metadata, String key) {
    var value = metadata.wireNames().get(key);
    return value == null ? null : value.toString();
  }

  private static String hex(long value) {
    return String.format(java.util.Locale.ROOT, "%016x", value);
  }

  /** Constructs ordered nullable JSON objects without permitting duplicate keys. */
  private static Map<String, Object> fields(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      String name = (String) pairs[index];
      if (result.containsKey(name)) throw new IllegalArgumentException("Duplicate field: " + name);
      result.put(name, pairs[index + 1]);
    }
    return result;
  }

  /** Serializes only the adapter's JSON primitives, lists and string-keyed objects. */
  public static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof String text) {
      StringBuilder quoted = new StringBuilder("\"");
      for (char character : text.toCharArray()) {
        if (character == '"' || character == '\\') quoted.append('\\').append(character);
        else if (character < 32)
          quoted.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
        else quoted.append(character);
      }
      return quoted.append('"').toString();
    }
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Map<?, ?> map)
      return "{"
          + String.join(
              ",",
              map.entrySet().stream()
                  .map(entry -> json(entry.getKey()) + ":" + json(entry.getValue()))
                  .toList())
          + "}";
    if (value instanceof List<?> list)
      return "[" + String.join(",", list.stream().map(BsaPublicObservation::json).toList()) + "]";
    throw new IllegalArgumentException("Unsupported JSON observation type");
  }
}
