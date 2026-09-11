package io.github.evildarkarchon.jbsa.verification;

import io.github.evildarkarchon.jbsa.*;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Public-API observations for independently specified BSA framing and collision scenarios. */
public final class Bsa68FramingScenarios {
  private Bsa68FramingScenarios() {}

  /** Runs one scenario against a supplied supported wire fixture and emits deterministic JSON. */
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("scenario wire-path");
    if (BethesdaArchives.standard().inspect(Path.of(args[1])).metadata().family()
        != ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA) throw new IllegalArgumentException("Expected 0x68");
    Path work =
        Files.createTempDirectory(Path.of(args[1]).toAbsolutePath().getParent(), "framing-");
    var checks =
        switch (args[0]) {
          case "bsa-name-flags" -> names(work);
          case "bsa-dds-empty-stem" -> emptyStem(work);
          case "bsa-embedded-068" -> embedded(work);
          case "bsa-hash-collisions" -> collisions(work);
          case "bsa-automatic-flags" -> automaticFlags(work);
          default -> throw new IllegalArgumentException("Unknown scenario");
        };
    System.out.println(BsaPublicObservation.json(Map.of("scenario", args[0], "checks", checks)));
  }

  /** Exercises all presence flags using literal single-folder records and independent offsets. */
  private static Map<String, Object> names(Path work) throws Exception {
    var result = new TreeMap<String, Object>();
    for (int flags = 0; flags < 4; flags++) {
      var entry =
          BethesdaArchives.standard()
              .inspect(wire(work, "names-" + flags, flags, "b", new byte[] {7}))
              .entries()
              .getFirst();
      result.put("flags_" + flags + "_folder", entry.wireNames().containsKey("folder"));
      result.put("flags_" + flags + "_basename", entry.wireNames().containsKey("basename"));
      result.put("flags_" + flags + "_identity", entry.normalizedNameIdentity().isPresent());
      result.put("flags_" + flags + "_display", entry.displayName());
    }
    return result;
  }

  /** Decode retains the empty stem and hash while public encode rejects it before publication. */
  private static Map<String, Object> emptyStem(Path work) throws Exception {
    var inspection =
        BethesdaArchives.standard().inspect(wire(work, "empty", 3, ".dds", new byte[] {7}));
    var entry = inspection.entries().getFirst();
    Path target = work.resolve("rejected.bsa");
    var result = new TreeMap<String, Object>();
    result.put("decode_name", entry.displayName());
    result.put("basename_hex", entry.wireNames().get("basename").toString());
    result.put(
        "retained_hash",
        String.format("%016x", ((EntryMetadata.VersionedBsa) entry.facts()).nameHash()));
    result.put(
        "hash_warning",
        inspection.assessment().diagnostics().stream()
            .anyMatch(d -> d.identifier().equals("bsa.file-hash-mismatch")));
    try {
      pack(target, List.of("a/.dds"), false, false, 1);
      throw new AssertionError("Empty stem encoded");
    } catch (ArchiveException failure) {
      result.put("encode_failure", failure.kind().name());
    }
    result.put("output_exists", Files.exists(target));
    return result;
  }

  /**
   * Canonical names precede stored/zlib payloads; a bounded mismatch retains content and warning.
   */
  private static Map<String, Object> embedded(Path work) throws Exception {
    var result = new TreeMap<String, Object>();
    for (boolean compressed : new boolean[] {false, true}) {
      Path target = work.resolve("embedded-" + compressed + ".bsa");
      pack(target, List.of("Meshes/A.nif"), true, compressed, 1);
      try (var archive = BethesdaArchives.standard().open(target, OpenOptions.standard())) {
        var entry = archive.entry(0);
        String prefix = compressed ? "zlib_" : "stored_";
        result.put(
            prefix + "embedded_hex", entry.metadata().wireNames().get("embedded").toString());
        try (var content = entry.openContent()) {
          result.put(
              prefix + "payload_hex",
              HexFormat.of().formatHex(Channels.newInputStream(content).readAllBytes()));
        }
      }
    }
    Path mismatch = wire(work, "mismatch", 0x103, "b", new byte[] {3, 97, 92, 99, 7});
    var inspection = BethesdaArchives.standard().inspect(mismatch);
    var warning =
        inspection.assessment().diagnostics().stream()
            .filter(d -> d.identifier().equals("bsa.embedded-name-mismatch"))
            .findFirst()
            .orElseThrow();
    result.put("mismatch_disposition", inspection.assessment().disposition().name());
    result.put("mismatch_start", warning.location().byteSpan().orElseThrow().offset());
    result.put("mismatch_length", warning.location().byteSpan().orElseThrow().length());
    try (var archive = BethesdaArchives.standard().open(mismatch, OpenOptions.standard());
        var content = archive.entry(0).openContent()) {
      result.put(
          "mismatch_payload_hex",
          HexFormat.of().formatHex(Channels.newInputStream(content).readAllBytes()));
    }
    return result;
  }

  /** Known equal polynomial hashes exercise folder grouping and basename byte tie-breaks. */
  private static Map<String, Object> collisions(Path work) throws Exception {
    // Both eight-byte middles independently hash to 6cc6fc1a under h=65599*h+byte.
    String first = "aavukdrhizz", second = "augbmizjvzz";
    List<String> sources =
        List.of(
            second + "/y",
            first + "/" + second,
            first + "/z",
            second + "/b",
            first + "/" + first,
            first + "/a");
    var result = new TreeMap<String, Object>();
    byte[] baseline = null;
    for (int configuration = 0; configuration < 4; configuration++) {
      var order = new ArrayList<>(sources);
      if ((configuration & 1) != 0) Collections.reverse(order);
      Path target = work.resolve("collisions-" + configuration + ".bsa");
      pack(target, order, false, false, configuration < 2 ? 1 : 4);
      byte[] bytes = Files.readAllBytes(target);
      if (baseline == null) baseline = bytes;
      result.put("configuration_" + configuration + "_stable", Arrays.equals(baseline, bytes));
      var entries = BethesdaArchives.standard().inspect(target).entries();
      if (configuration == 0) {
        result.put(
            "order", String.join("|", entries.stream().map(EntryMetadata::displayName).toList()));
        var a = (EntryMetadata.VersionedBsa) entries.get(0).facts();
        var b = (EntryMetadata.VersionedBsa) entries.get(4).facts();
        result.put("folder_hash", String.format("%016x", a.folderHash()));
        result.put("folders_collide", a.folderHash() == b.folderHash());
        result.put(
            "folder_ordinals",
            String.join(
                ",",
                entries.stream()
                    .map(
                        e ->
                            Long.toString(((EntryMetadata.VersionedBsa) e.facts()).folderOrdinal()))
                    .toList()));
        result.put(
            "basenames_collide",
            ((EntryMetadata.VersionedBsa) entries.get(2).facts()).nameHash()
                == ((EntryMetadata.VersionedBsa) entries.get(3).facts()).nameHash());
      }
    }
    return result;
  }

  /** Exercises every normative classifier row and overlapping fallback/root precedence. */
  private static Map<String, Object> automaticFlags(Path work) throws Exception {
    var result = new TreeMap<String, Object>();
    // These paths are test inputs transcribed from the normative classifier rows, not its code.
    String[][] roots = {
      {"mesh", "MeShEs/a.dds"}, {"texture", "textures/a.nif"},
      {"material", "materials/a.nif"}, {"geometry", "geometries/a.nif"},
      {"voice", "sound/voice/a.nif"}, {"sound", "sound/a.nif"},
      {"music", "music/a.nif"}, {"script_source", "scripts/source/a.nif"},
      {"sse_script_source", "source/scripts/a.nif"}, {"script", "scripts/a.nif"},
      {"strings", "strings/a.nif"}, {"speedtree", "trees/a.nif"},
      {"video", "video/a.nif"}, {"lod_settings", "lodsettings/a.nif"},
      {"distant_lod", "distantlod/a.nif"}, {"interface", "interface/a.nif"},
      {"program", "programs/a.nif"}, {"menus", "menus/a.nif"},
      {"font", "fonts/a.nif"}, {"facegen", "facegen/a.nif"},
      {"ls_data", "lsdata/a.nif"}, {"shaders", "shaders/a.nif"},
      {"shader_effects", "shadersfx/a.nif"}, {"grass", "grass/a.nif"},
      {"pre_visibility", "vis/a.nif"}, {"sequence", "seq/a.nif"},
      {"dialogue_views", "dialogueviews/a.nif"}, {"book_art", "bookart/a.nif"},
      {"icon", "icons/a.nif"}, {"splash", "splash/a.nif"},
      {"no_match", "unclassified/a.unknown"}, {"root_boundary", "meshesextra/a.unknown"}
    };
    for (String[] row : roots) observeFlags(work, result, "root_" + row[0], List.of(row[1]), false);
    // Ambiguous fallback extensions select their first matching row, independent of later roots.
    for (String extension :
        List.of("wav", "xwm", "mp3", "ogg", "psc", "png", "swf", "txt", "xml", "dds", "tga", "lod"))
      observeFlags(
          work, result, "fallback_" + extension, List.of("unclassified/a." + extension), false);
    observeFlags(work, result, "no_tes4_xml_rule", List.of("materials/a.xml"), false);
    observeFlags(work, result, "compressed_mesh", List.of("meshes/a.nif"), true);
    observeFlags(
        work,
        result,
        "combined_mesh_sound_font",
        List.of("meshes/a.nif", "sound/a.wav", "fonts/a.fnt"),
        false);
    return result;
  }

  /** Records exact archive/file flag pairs for one normative classification input. */
  private static void observeFlags(
      Path work, Map<String, Object> result, String key, List<String> names, boolean compressed)
      throws Exception {
    Path target = work.resolve(key + ".bsa");
    pack(target, names, false, compressed, 1);
    var metadata =
        (ArchiveMetadata.VersionedBsa) BethesdaArchives.standard().inspect(target).metadata();
    result.put(key, metadata.archiveFlags() + "/" + metadata.fileFlags());
  }

  /** Writes independent one-entry records with deliberately authoritative fixed file hashes. */
  private static Path wire(Path work, String label, int flags, String basename, byte[] payload)
      throws Exception {
    byte[] name = basename.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    int folderBytes = (flags & 1) != 0 ? 3 : 0, namesBytes = (flags & 2) != 0 ? name.length + 1 : 0;
    int data = 68 + folderBytes + namesBytes;
    var bytes = ByteBuffer.allocate(data + payload.length).order(ByteOrder.LITTLE_ENDIAN);
    bytes
        .putInt(0x00415342)
        .putInt(104)
        .putInt(36)
        .putInt(flags)
        .putInt(1)
        .putInt(1)
        .putInt(folderBytes == 0 ? 0 : 2)
        .putInt(namesBytes)
        .putInt(0);
    bytes.putLong(0x61010061L).putInt(1).putInt(52 + namesBytes);
    if (folderBytes != 0) bytes.put(new byte[] {2, 97, 0});
    bytes.putLong(0x62010062L).putInt(payload.length).putInt(data);
    if (namesBytes != 0) bytes.put(name).put((byte) 0);
    bytes.put(payload);
    return Files.write(work.resolve(label + ".bsa"), bytes.array());
  }

  /** Packs generated redistributable one-byte sources through immutable public options. */
  private static void pack(
      Path target, List<String> names, boolean embedded, boolean compressed, int workers)
      throws Exception {
    var sources =
        names.stream()
            .<PackSource>map(
                name ->
                    new PackSource.GeneratedEntry(
                        name,
                        1,
                        () -> Channels.newChannel(new ByteArrayInputStream(new byte[] {7}))))
            .toList();
    BethesdaArchives.standard()
        .pack(
            new PackRequest(
                target,
                ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA,
                new ArchiveEncoding(
                    Optional.of(new WireVersion(104)), Optional.empty(), OptionalLong.empty()),
                Optional.empty(),
                sources,
                TargetPolicy.FAIL,
                DiagnosticPolicy.standard(),
                ResourceLimits.standard(),
                new WorkerSelection.UpTo(workers),
                new PackOptions(
                    List.of(),
                    compressed ? PackOptions.Compression.ZLIB : PackOptions.Compression.STORED,
                    false,
                    new PackOptions.Splitting.UpToBytes(0),
                    embedded ? new FlagSelection.Explicit(0x103) : FlagSelection.AUTOMATIC,
                    FlagSelection.AUTOMATIC),
                Optional.empty()),
            OperationControl.standard());
  }
}
