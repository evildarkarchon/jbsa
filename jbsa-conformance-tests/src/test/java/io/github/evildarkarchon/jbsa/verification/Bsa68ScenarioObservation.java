package io.github.evildarkarchon.jbsa.verification;

import io.github.evildarkarchon.jbsa.*;
import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

/** Public-only, assertion-bearing observations for eight shared 0x68 scenario assignments. */
public final class Bsa68ScenarioObservation {
  private Bsa68ScenarioObservation() {}

  /** Executes one named recipe in an isolated working directory; failed assertions exit nonzero. */
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 2)
      throw new IllegalArgumentException("scenario and wire fixture required");
    String scenario = arguments[0];
    Path fixture = Path.of(arguments[1]);
    if (!Files.isRegularFile(fixture)) throw new IllegalArgumentException("Missing bound fixture");
    Map<String, Object> checks =
        switch (scenario) {
          case "input-nested" -> nested();
          case "names-ascii-lowercase" -> lowercase();
          case "names-case-and-separators" -> overlay();
          case "names-nonascii-lowercase" ->
              rejectedNames(
                  List.of("meshes/É.nif", "meshes/é.nif", "meshes/ß.nif", "meshes/Ð.nif"));
          case "names-normalized-rejections" ->
              rejectedNames(
                  List.of(
                      "meshes//a.nif",
                      "meshes/./a.nif",
                      "meshes/../a.nif",
                      "meshes/a.nif.",
                      "meshes/a.nif ",
                      "meshes/a:b.nif",
                      "/meshes/a.nif",
                      "meshes\\\\a.nif"));
          case "names-unmappable-encode" ->
              rejectedNames(List.of("meshes/\ud83d\ude00.nif", "meshes/\u4e00.nif"));
          case "names-undecodable-components" -> undecodable(fixture);
          case "source-sharing" -> sharing();
          case "compression-boundaries" -> compressionBoundaries(fixture);
          case "names-wire-encodings" -> wireEncodings(fixture);
          case "source-splitting" -> Bsa68LargeSplitScenario.observe(fixture);
          default -> throw new IllegalArgumentException("Unsupported scenario: " + scenario);
        };
    System.out.println(BsaPublicObservation.json(Map.of("scenario", scenario, "checks", checks)));
  }

  /**
   * Verifies that generated complete nested names retain their directory hierarchy and payloads.
   */
  private static Map<String, Object> nested() throws Exception {
    Path output = Path.of("nested.bsa");
    pack(
        output,
        List.of(
            entry("meshes/actors/hero/a.nif", new byte[] {1, 2, 3}),
            entry("textures/actors/hero/a.dds", new byte[] {4, 5, 6, 7})),
        false);
    var names = names(output);
    require(
        names.equals(List.of("meshes\\actors\\hero\\a.nif", "textures\\actors\\hero\\a.dds")),
        "Nested names changed");
    var contents = payloads(output);
    require(
        Arrays.equals(contents.get("meshes\\actors\\hero\\a.nif"), new byte[] {1, 2, 3})
            && Arrays.equals(
                contents.get("textures\\actors\\hero\\a.dds"), new byte[] {4, 5, 6, 7}),
        "Nested payloads changed");
    return Map.of(
        "names",
        names,
        "entry_count",
        2,
        "payload_sizes",
        payloads(output).values().stream().map(value -> value.length).sorted().toList());
  }

  /**
   * Checks byte-level ASCII mapping, retained wire bytes and normalized identities independently.
   */
  private static Map<String, Object> lowercase() throws Exception {
    Path output = Path.of("lowercase.bsa");
    pack(
        output,
        List.of(entry("MeShEs/Foo.NIF", new byte[] {1}), entry("TeXtUrEs/BAR.DDS", new byte[] {2})),
        false);
    List<String> names = names(output);
    require(
        names.equals(List.of("meshes\\foo.nif", "textures\\bar.dds")), "ASCII lowercase mismatch");
    var contents = payloads(output);
    require(
        Arrays.equals(contents.get("meshes\\foo.nif"), new byte[] {1})
            && Arrays.equals(contents.get("textures\\bar.dds"), new byte[] {2}),
        "ASCII name conversion changed payload bytes");
    List<String> wireNames = new ArrayList<>();
    try (OpenArchive archive = BethesdaArchives.standard().open(output, OpenOptions.standard())) {
      for (long index = 0; index < archive.entryCount(); index++) {
        var metadata = archive.entry(index).metadata();
        String joined =
            new String(metadata.wireNames().get("folder").bytes(), Charset.forName("windows-1252"))
                + "\\"
                + new String(
                    metadata.wireNames().get("basename").bytes(), Charset.forName("windows-1252"));
        require(
            joined.equals(metadata.normalizedNameIdentity().orElseThrow().value()),
            "Wire spelling and identity disagree");
        wireNames.add(joined);
      }
    }
    wireNames.sort(String::compareTo);
    return Map.of("names", names, "wire_names", wireNames, "entry_count", names.size());
  }

  /** Establishes slash/case identity equality and later-source-wins payload replacement. */
  private static Map<String, Object> overlay() throws Exception {
    Path output = Path.of("overlay.bsa");
    pack(
        output,
        List.of(
            entry("MeShEs/Thing.NIF", new byte[] {1}),
            entry("meshes\\thing.nif", new byte[] {9, 8})),
        false);
    var payloads = payloads(output);
    require(
        payloads.size() == 1 && Arrays.equals(payloads.get("meshes\\thing.nif"), new byte[] {9, 8}),
        "Overlay failed");
    return Map.of(
        "names",
        names(output),
        "entry_count",
        payloads.size(),
        "surviving_payload_hex",
        HexFormat.of().formatHex(payloads.get("meshes\\thing.nif")));
  }

  /** Exercises name preflight failures before payload factories or publication effects occur. */
  private static Map<String, Object> rejectedNames(List<String> names) throws Exception {
    List<String> kinds = new ArrayList<>();
    AtomicInteger opened = new AtomicInteger();
    var initialTree = treeState();
    for (int index = 0; index < names.size(); index++) {
      Path output = Path.of("rejected-" + index + ".bsa");
      var source =
          new PackSource.GeneratedEntry(
              names.get(index),
              1,
              () -> {
                opened.incrementAndGet();
                return Channels.newChannel(new ByteArrayInputStream(new byte[] {1}));
              });
      try {
        pack(output, List.of(source), false);
        throw new AssertionError("Rejected name was accepted: " + names.get(index));
      } catch (ArchiveException expected) {
        require(expected.kind() == FailureKind.POLICY, "Wrong name rejection kind");
        kinds.add(expected.kind().name());
      }
      require(!Files.exists(output), "Rejected name published output");
      require(initialTree.equals(treeState()), "Rejected name changed the working tree");
    }
    require(opened.get() == 0, "Name preflight opened payload factory");
    return Map.of(
        "rejected_count",
        names.size(),
        "failure_kinds",
        kinds,
        "payload_factory_calls",
        opened.get(),
        "output_count",
        0);
  }

  /**
   * Mutates each name component to undefined CP1252 while preserving wire bytes and payload access.
   */
  private static Map<String, Object> undecodable(Path fixture) throws Exception {
    byte[] source = Files.readAllBytes(fixture);
    List<String> fields = new ArrayList<>();
    List<String> retained = new ArrayList<>();
    List<String> displays = new ArrayList<>();
    List<Long> ordinals = new ArrayList<>();
    List<Long> offsets = new ArrayList<>();
    List<Long> lengths = new ArrayList<>();
    for (String component : List.of("folder", "basename")) {
      byte[] bytes = source.clone();
      // The bound fixture recipe has one 16-byte folder record and a meshes folder block.
      bytes[component.equals("folder") ? 53 : 92] = (byte) 0x81;
      Path input = Files.write(Path.of("undecodable-" + component + ".bsa"), bytes);
      var inspection = BethesdaArchives.standard().inspect(input);
      var entry = inspection.entries().getFirst();
      require(entry.normalizedNameIdentity().isEmpty(), "Undecodable name has an identity");
      require(
          entry.displayName().startsWith("__jbsa_hash__\\f00000000-"),
          "Synthetic display name missing");
      var diagnostic =
          inspection.assessment().diagnostics().stream()
              .filter(value -> value.identifier().equals("archive-name.undecodable-wire-bytes"))
              .findFirst()
              .orElseThrow();
      require(
          diagnostic.severity() == DiagnosticSeverity.WARNING, "Undecodable-name severity changed");
      require(
          diagnostic.location().field().orElseThrow().equals(component),
          "Undecodable component location changed");
      require(
          entry.wireNames().get(component).bytes()[0] == (byte) 0x81, "Original wire byte lost");
      fields.add(diagnostic.location().field().orElseThrow());
      retained.add(HexFormat.of().formatHex(entry.wireNames().get(component).bytes()));
      displays.add(entry.displayName());
      ordinals.add(diagnostic.location().entryOrdinal().orElseThrow());
      offsets.add(diagnostic.location().byteSpan().orElseThrow().offset());
      lengths.add(diagnostic.location().byteSpan().orElseThrow().length());
      try (OpenArchive archive = BethesdaArchives.standard().open(input, OpenOptions.standard());
          EntryContent content = archive.entry(0).openContent()) {
        byte[] decoded = Channels.newInputStream(content).readAllBytes();
        require(
            decoded.length == 1024 && Arrays.equals(decoded, repeat((byte) 'A', 1024)),
            "Undecodable name changed content");
      }
    }
    return Map.of(
        "components",
        fields,
        "retained_wire_hex",
        retained,
        "identities_present",
        List.of(false, false),
        "payload_sizes",
        List.of(1024, 1024),
        "diagnostic_identifier",
        "archive-name.undecodable-wire-bytes",
        "display_names",
        displays,
        "diagnostic_ordinals",
        ordinals,
        "diagnostic_offsets",
        offsets,
        "diagnostic_lengths",
        lengths);
  }

  /** Proves sharing requires byte equality and keeps equal-sized distinct content independent. */
  private static Map<String, Object> sharing() throws Exception {
    byte[] repeated = repeat((byte) 7, 32);
    List<PackSource> sources =
        List.of(
            entry("meshes/a.nif", repeated),
            entry("meshes/b.nif", repeated),
            entry("meshes/c.nif", repeat((byte) 8, 32)));
    Path shared = Path.of("shared.bsa");
    Path separate = Path.of("separate.bsa");
    pack(shared, sources, true);
    pack(separate, sources, false);
    Map<String, Long> sharedOffsets = offsets(shared);
    Map<String, Long> separateOffsets = offsets(separate);
    boolean same = sharedOffsets.get("meshes\\a.nif").equals(sharedOffsets.get("meshes\\b.nif"));
    boolean distinct =
        !sharedOffsets.get("meshes\\a.nif").equals(sharedOffsets.get("meshes\\c.nif"));
    require(same && distinct, "Sharing confused size equality with byte equality");
    require(
        separateOffsets.values().stream().distinct().count() == 3,
        "Disabled sharing reused a span");
    long saved = Files.size(separate) - Files.size(shared);
    require(saved == 32, "Unexpected shared-byte savings");
    for (Path archive : List.of(shared, separate)) {
      var content = payloads(archive);
      require(
          Arrays.equals(content.get("meshes\\a.nif"), repeated)
              && Arrays.equals(content.get("meshes\\b.nif"), repeated)
              && Arrays.equals(content.get("meshes\\c.nif"), repeat((byte) 8, 32)),
          "Shared payload corruption");
    }
    return Map.of(
        "entry_count",
        3,
        "equal_payloads_share_offset",
        same,
        "different_payload_offset_distinct",
        distinct,
        "unshared_unique_offsets",
        3,
        "saved_bytes",
        saved,
        "payload_sizes",
        List.of(32, 32, 32));
  }

  /** Returns detached data offsets keyed by complete names through the public inspection model. */
  private static Map<String, Long> offsets(Path path) throws Exception {
    Map<String, Long> result = new LinkedHashMap<>();
    for (var entry : BethesdaArchives.standard().inspect(path).entries())
      result.put(entry.displayName(), ((EntryMetadata.VersionedBsa) entry.facts()).dataOffset());
    return result;
  }

  /** Retrieves complete payloads to check EOF, shared-span ownership and exact values. */
  private static Map<String, byte[]> payloads(Path path) throws Exception {
    Map<String, byte[]> result = new LinkedHashMap<>();
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard())) {
      require(
          archive.inspection().metadata().family() == ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA,
          "Wrong family");
      for (long index = 0; index < archive.entryCount(); index++) {
        try (EntryContent content = archive.entry(index).openContent()) {
          result.put(
              archive.entry(index).metadata().displayName(),
              Channels.newInputStream(content).readAllBytes());
        }
      }
    }
    return result;
  }

  /** Returns complete canonical output names in comparison order. */
  private static List<String> names(Path path) throws Exception {
    return payloads(path).keySet().stream().sorted().toList();
  }

  /** Constructs a repeatable generated source whose payload channel transfers to the operation. */
  private static PackSource entry(String name, byte[] bytes) {
    return new PackSource.GeneratedEntry(
        name, bytes.length, () -> Channels.newChannel(new ByteArrayInputStream(bytes)));
  }

  /** Packs exact stored sources with one worker and explicit sharing, without splitting. */
  private static void pack(Path output, List<PackSource> sources, boolean sharing)
      throws Exception {
    pack(output, sources, sharing, PackOptions.Compression.STORED);
  }

  /** Packs a concrete codec choice through the same immutable public request. */
  private static void pack(
      Path output, List<PackSource> sources, boolean sharing, PackOptions.Compression compression)
      throws Exception {
    BethesdaArchives.standard()
        .pack(
            new PackRequest(
                output,
                ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA,
                new ArchiveEncoding(
                    Optional.of(new WireVersion(104)), Optional.empty(), OptionalLong.empty()),
                Optional.empty(),
                sources,
                TargetPolicy.FAIL,
                DiagnosticPolicy.standard(),
                ResourceLimits.standard(),
                new WorkerSelection.UpTo(1),
                new PackOptions(
                    List.of(),
                    compression,
                    sharing,
                    new PackOptions.Splitting.UpToBytes(0),
                    FlagSelection.AUTOMATIC,
                    FlagSelection.AUTOMATIC),
                Optional.empty()),
            OperationControl.standard());
  }

  private static byte[] repeat(byte value, int size) {
    byte[] result = new byte[size];
    Arrays.fill(result, value);
    return result;
  }

  /**
   * Records all working-tree paths and file bytes so rejected operations cannot hide side effects.
   */
  private static Map<String, String> treeState() throws Exception {
    Map<String, String> result = new LinkedHashMap<>();
    try (var paths = Files.walk(Path.of("."))) {
      for (var path : paths.sorted().toList()) {
        result.put(
            path.toString(),
            Files.isDirectory(path)
                ? "directory"
                : HexFormat.of()
                    .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(path))));
      }
    }
    return result;
  }

  /** Enforces normative checks without runtime test-library dependencies. */
  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  /** Exercises the bound DEFLATE sizes with exact full-byte comparisons including empty content. */
  private static Map<String, Object> compressionBoundaries(Path recipe) throws Exception {
    checkRecipe(recipe, "00b43cbd2f9f3a9de62f21d9168681fa33849594bec66dc829f92117ef72e911");
    List<Integer> sizes = List.of(0, 1, 65535, 65536);
    List<Integer> observed = new ArrayList<>();
    List<Boolean> compressed = new ArrayList<>();
    for (int size : sizes) {
      byte[] bytes = new byte[size];
      for (int index = 0; index < size; index++) bytes[index] = (byte) (index % 251);
      Path output = Path.of("boundary-" + size + ".bsa");
      pack(output, List.of(entry("data/boundary.bin", bytes)), false, PackOptions.Compression.ZLIB);
      try (OpenArchive archive = BethesdaArchives.standard().open(output, OpenOptions.standard());
          EntryContent content = archive.entry(0).openContent()) {
        byte[] decoded = Channels.newInputStream(content).readAllBytes();
        require(Arrays.equals(bytes, decoded), "DEFLATE boundary payload mismatch");
        observed.add(decoded.length);
        compressed.add(
            ((EntryMetadata.VersionedBsa) archive.entry(0).metadata().facts()).compressed());
      }
    }
    return Map.of("input_sizes", sizes, "decoded_sizes", observed, "compressed", compressed);
  }

  /**
   * Retains both byte sets under standard1252;932-origin bytes do not select an alternate codec.
   */
  private static Map<String, Object> wireEncodings(Path recipe) throws Exception {
    checkRecipe(recipe, "0c389c8ec13e2f13c8cff94ee08d13ab87844d6a93739c892a328663a1fd1dfc");
    List<String> hex = List.of("636166e92e747874", "8365835883672e747874");
    List<String> displays = new ArrayList<>(), retained = new ArrayList<>();
    List<Boolean> hashWarnings = new ArrayList<>();
    for (int index = 0; index < hex.size(); index++) {
      byte[] name = HexFormat.of().parseHex(hex.get(index));
      int data = 74 + name.length + 1;
      var wire = java.nio.ByteBuffer.allocate(data + 1).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      wire.putInt(0x00415342)
          .putInt(104)
          .putInt(36)
          .putInt(3)
          .putInt(1)
          .putInt(1)
          .putInt(5)
          .putInt(name.length + 1)
          .putInt(0);
      wire.putLong(0x0000006164047461L).putInt(1).putInt(52 + name.length + 1);
      wire.put(new byte[] {5, 'd', 'a', 't', 'a', 0})
          .putLong(0)
          .putInt(1)
          .putInt(data)
          .put(name)
          .put((byte) 0)
          .put((byte) 7);
      Path input = Files.write(Path.of("wire-" + index + ".bsa"), wire.array());
      var inspection = BethesdaArchives.standard().inspect(input);
      var metadata = inspection.entries().getFirst();
      displays.add(metadata.displayName());
      retained.add(metadata.wireNames().get("basename").toString());
      hashWarnings.add(
          inspection.assessment().diagnostics().stream()
              .anyMatch(value -> value.identifier().equals("bsa.file-hash-mismatch")));
      require(
          Arrays.equals(payloads(input).get(metadata.displayName()), new byte[] {7}),
          "Wire encoding changed content");
    }
    return Map.of(
        "default_encoding",
        "windows-1252",
        "wire_hex",
        retained,
        "decoded_names",
        displays,
        "hash_mismatch_warnings",
        hashWarnings,
        "windows932_profile_decode_claimed",
        false);
  }

  /** Rejects a changed declarative input rather than silently running a different recipe. */
  private static void checkRecipe(Path path, String digest) throws Exception {
    require(
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
            .equals(digest),
        "Unexpected scenario recipe identity");
  }
}
