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
public final class Ba2PublicObservation {
  private Ba2PublicObservation() {}

  /** Emits complete public semantic evidence or a structured operational failure for one input. */
  public static void main(String[] arguments) throws Exception {
    try {
      Path input = Path.of(arguments[0]);
      String operation = arguments.length > 1 ? arguments[1] : "decode";
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
        Path output = Path.of("candidate.ba2");
        BethesdaArchives.standard()
            .pack(
                new PackRequest(
                    output,
                    ArchiveFamily.FO4_GENERAL_BA2,
                    new ArchiveEncoding(
                        Optional.of(new WireVersion(1)),
                        Optional.of(Ba2Subtype.GNRL),
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
                  "assessment",
                  failure.assessment().map(Ba2PublicObservation::assessment).orElse(null),
                  "diagnostics",
                  failure.diagnostics().stream().map(Ba2PublicObservation::diagnostic).toList())));
    }
  }

  /** Retains the intrinsic disposition and the exact boundary established before failure. */
  private static Object assessment(ArchiveAssessment value) {
    Object extent =
        switch (value.extent()) {
          case ValidationExtent.Recognition ignored -> fields("kind", "recognition");
          case ValidationExtent.Structure ignored -> fields("kind", "structure");
          case ValidationExtent.Payloads payloads ->
              fields("kind", "payloads", "ordinals", new ArrayList<>(payloads.entryOrdinals()));
        };
    return fields("disposition", value.disposition().name(), "extent", extent);
  }

  /**
   * Consumes every entry through EOF and records names, wire facts, diagnostics and payload hashes.
   */
  public static Map<String, Object> observe(Path path) throws Exception {
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard())) {
      var inspection = archive.inspection();
      List<Object> entries = new ArrayList<>();
      List<Object> diagnostics = new ArrayList<>();
      inspection.assessment().diagnostics().forEach(value -> diagnostics.add(diagnostic(value)));
      for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
        ArchiveEntry entry = archive.entry(ordinal);
        EntryMetadata metadata = entry.metadata();
        var wire = (EntryMetadata.GeneralBa2) metadata.facts();
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
        String payloadHash = HexFormat.of().formatHex(digest.digest());
        entries.add(
            fields(
                "decoded_name", metadata.displayName(),
                "wire_name_bytes", wireName(metadata, "complete"),
                "normalized_name_identity",
                    metadata
                        .normalizedNameIdentity()
                        .map(NormalizedNameIdentity::value)
                        .orElse(null),
                "basename_hash", wire.identity().baseNameHash(),
                "directory_hash", wire.identity().directoryHash(),
                "wire_hashes",
                    fields(
                        "basename",
                        wire.identity().baseNameHash(),
                        "directory",
                        wire.identity().directoryHash()),
                "flags", fields(),
                "extension", wire.identity().extension().toString(),
                "chunk_count", wire.identity().chunkCount(),
                "logical_size", metadata.decodedSize(),
                "stored_size", metadata.storedSize(),
                "decoded_size", metadata.decodedSize(),
                "compression_state", wire.packedSize() == 0 ? "stored" : "zlib",
                "payload_sha256", payloadHash,
                "chunks",
                    List.of(
                        fields(
                            "stored_size",
                            metadata.storedSize(),
                            "decoded_size",
                            metadata.decodedSize(),
                            "payload_sha256",
                            payloadHash))));
      }
      return fields(
          "archive_family",
          "fo4-gnrl-v1",
          "wire_version",
          1,
          "subtype",
          "GNRL",
          "compression_method",
          null,
          "disposition",
          inspection.assessment().disposition().name(),
          "entry_count",
          archive.entryCount(),
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
  private static String json(Object value) {
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
      return "[" + String.join(",", list.stream().map(Ba2PublicObservation::json).toList()) + "]";
    throw new IllegalArgumentException("Unsupported JSON observation type");
  }
}
