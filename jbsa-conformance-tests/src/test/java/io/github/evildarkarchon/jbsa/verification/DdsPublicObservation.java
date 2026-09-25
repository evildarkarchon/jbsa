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
public final class DdsPublicObservation {
  private DdsPublicObservation() {}

  /** Emits complete public semantic evidence or a structured operational failure for one input. */
  public static void main(String[] arguments) throws Exception {
    try {
      Path input = Path.of(arguments[0]);
      String operation = arguments.length > 1 ? arguments[1] : "decode";
      String codec = arguments.length > 2 ? arguments[2] : "zlib";
      DdsTarget target =
          arguments.length > 3 && arguments[3].equals("xbox-v1") ? DdsTarget.XBOX : DdsTarget.PC;
      String fixture = arguments.length > 4 ? arguments[4] : "";
      OpenOptions options =
          new OpenOptions(Optional.empty(), ResourceLimits.standard(), Optional.of(target));
      if (fixture.equals("dds-reconstruction-selection")) {
        var profile = Optional.of(CompatibilityProfile.BSARCH_1_0_V1);
        Path xboxName = Path.of("input_xbox.ba2");
        java.nio.file.Files.copy(input, xboxName);
        System.out.println(
            json(
                fields(
                    "pc", observe(input, options),
                    "xbox",
                        observe(
                            input,
                            new OpenOptions(
                                Optional.empty(),
                                ResourceLimits.standard(),
                                Optional.of(DdsTarget.XBOX))),
                    "profile_inferred",
                        observe(
                            xboxName,
                            new OpenOptions(profile, ResourceLimits.standard(), Optional.empty())),
                    "profile_explicit_pc",
                        observe(
                            xboxName,
                            new OpenOptions(
                                profile, ResourceLimits.standard(), Optional.of(DdsTarget.PC))))));
      } else if (operation.equals("encode")) {
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
                    new NormalizedNameIdentity("textures\\a.dds"), PackOptions.Compression.STORED)
                : Map.of();
        byte[] magic;
        try (var source = java.nio.file.Files.newInputStream(input)) {
          magic = source.readNBytes(4);
        }
        PackSource source =
            java.util.Arrays.equals(magic, new byte[] {68, 68, 83, 32})
                ? new PackSource.GeneratedEntry(
                    "textures/a.dds",
                    java.nio.file.Files.size(input),
                    () -> java.nio.channels.FileChannel.open(input))
                : new PackSource.DetectedPath(input);
        Path output = Path.of("candidate.ba2");
        BethesdaArchives.standard()
            .pack(
                new PackRequest(
                    output,
                    ArchiveFamily.FO4_DDS_BA2,
                    new ArchiveEncoding(
                        Optional.of(new WireVersion(1)),
                        Optional.of(Ba2Subtype.DX10),
                        OptionalLong.empty()),
                    Optional.empty(),
                    List.of(source),
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
                    Optional.of(target)),
                OperationControl.standard());
        // BA2 carries no reconstruction target; cross-direction evidence uses canonical PC DDS.
        System.out.println(json(observe(output, OpenOptions.standard())));
      } else if (operation.equals("extract")) {
        BethesdaArchives.standard()
            .extract(
                new ExtractRequest(
                    input,
                    Path.of("extracted"),
                    EntrySelection.ALL,
                    TargetPolicy.FAIL,
                    DiagnosticPolicy.standard(),
                    new WorkerSelection.UpTo(1),
                    options),
                OperationControl.standard());
        System.out.println(json(fields("extracted", true)));
      } else {
        if (operation.equals("oracle-to-jbsa")) options = OpenOptions.standard();
        var projection = observe(input, options);
        if (operation.equals("oracle-to-jbsa"))
          BethesdaArchives.standard()
              .extract(
                  new ExtractRequest(
                      input,
                      Path.of("."),
                      EntrySelection.ALL,
                      TargetPolicy.FAIL,
                      DiagnosticPolicy.standard(),
                      new WorkerSelection.UpTo(1),
                      options),
                  OperationControl.standard());
        System.out.println(json(projection));
      }
    } catch (ArchiveException failure) {
      System.out.println(
          json(
              fields(
                  "failure_kind",
                  failure.kind().name(),
                  "assessment",
                  failure.assessment().map(DdsPublicObservation::assessment).orElse(null),
                  "diagnostics",
                  failure.diagnostics().stream().map(DdsPublicObservation::diagnostic).toList())));
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
  public static Map<String, Object> observe(Path path, OpenOptions options) throws Exception {
    try (OpenArchive archive = BethesdaArchives.standard().open(path, options)) {
      var inspection = archive.inspection();
      List<Object> entries = new ArrayList<>();
      List<Object> diagnostics = new ArrayList<>();
      inspection.assessment().diagnostics().forEach(value -> diagnostics.add(diagnostic(value)));
      for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
        ArchiveEntry entry = archive.entry(ordinal);
        EntryMetadata metadata = entry.metadata();
        var wire = (EntryMetadata.DdsBa2) metadata.facts();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String headerHash;
        List<Object> chunks = new ArrayList<>();
        try (EntryContent content = entry.openContent()) {
          long opaqueSize =
              wire.chunks().stream().mapToLong(EntryMetadata.DdsChunk::unpackedSize).sum();
          headerHash = hashSpan(content, metadata.decodedSize() - opaqueSize, digest);
          for (var chunk : wire.chunks())
            chunks.add(
                fields(
                    "stored_size",
                    chunk.packedSize() == 0 ? chunk.unpackedSize() : chunk.packedSize(),
                    "decoded_size",
                    chunk.unpackedSize(),
                    "start_mip",
                    chunk.startMip(),
                    "end_mip",
                    chunk.endMip(),
                    "first_mip",
                    chunk.startMip(),
                    "last_mip",
                    chunk.endMip(),
                    "payload_sha256",
                    hashSpan(content, chunk.unpackedSize(), digest)));
          if (content.read(ByteBuffer.allocate(1)) != -1)
            throw new IllegalStateException("Surplus reconstructed bytes");
          content.assessment().orElseThrow().diagnostics().stream()
              // Payload assessments already include the structural evidence retained above.
              .filter(value -> !inspection.assessment().diagnostics().contains(value))
              .forEach(value -> diagnostics.add(diagnostic(value)));
        }
        String payloadHash = HexFormat.of().formatHex(digest.digest());
        entries.add(
            fields(
                "decoded_name",
                metadata.displayName(),
                "wire_name_bytes",
                wireName(metadata, "complete"),
                "normalized_name_identity",
                metadata.normalizedNameIdentity().map(NormalizedNameIdentity::value).orElse(null),
                "basename_hash",
                wire.identity().baseNameHash(),
                "directory_hash",
                wire.identity().directoryHash(),
                "wire_hashes",
                fields(
                    "basename",
                    wire.identity().baseNameHash(),
                    "directory",
                    wire.identity().directoryHash()),
                "flags",
                fields("texture", wire.flags()),
                "width",
                wire.width(),
                "height",
                wire.height(),
                "mip_count",
                wire.mipCount(),
                "dxgi_format",
                wire.dxgiFormat(),
                "cubemap",
                (wire.flags() & 1) != 0,
                "tile_mode",
                wire.tileMode(),
                "dds_header_sha256",
                headerHash,
                "extension",
                wire.identity().extension().toString(),
                "chunk_count",
                wire.identity().chunkCount(),
                "logical_size",
                metadata.decodedSize(),
                "stored_size",
                metadata.storedSize(),
                "decoded_size",
                metadata.decodedSize(),
                "compression_state",
                wire.chunks().stream().allMatch(c -> c.packedSize() == 0)
                    ? "stored"
                    : wire.chunks().stream().allMatch(c -> c.packedSize() != 0) ? "zlib" : "mixed",
                "payload_sha256",
                payloadHash,
                "reconstructed_dds_sha256",
                payloadHash,
                "chunks",
                chunks));
      }
      return fields(
          "archive_family",
          "fo4-dx10-v1",
          "wire_version",
          1,
          "subtype",
          "DX10",
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

  /**
   * Hashes exactly one canonical envelope or opaque chunk while retaining the complete DDS digest.
   */
  private static String hashSpan(EntryContent content, long length, MessageDigest complete)
      throws Exception {
    MessageDigest span = MessageDigest.getInstance("SHA-256");
    ByteBuffer buffer = ByteBuffer.allocate(65536);
    while (length > 0) {
      buffer.clear().limit((int) Math.min(buffer.capacity(), length));
      int count = content.read(buffer);
      if (count < 0) throw new IllegalStateException("Truncated reconstructed bytes");
      buffer.flip();
      complete.update(buffer.asReadOnlyBuffer());
      span.update(buffer);
      length -= count;
    }
    return HexFormat.of().formatHex(span.digest());
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
      return "[" + String.join(",", list.stream().map(DdsPublicObservation::json).toList()) + "]";
    throw new IllegalArgumentException("Unsupported JSON observation type");
  }
}
