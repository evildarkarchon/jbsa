package io.github.evildarkarchon.jbsa.verification;

import io.github.evildarkarchon.jbsa.*;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Public CV1 compression and publication observations for independently bound 0x68 recipes. */
public final class Bsa68FormatScenarios {
  private Bsa68FormatScenarios() {}

  /** Runs one scenario in its isolated working directory and emits actual stable observations. */
  public static void main(String[] args) throws Exception {
    String scenario = args[0];
    Path input = Path.of(args[1]);
    if (BethesdaArchives.standard().detect(input).family().orElseThrow()
        != ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA)
      throw new IllegalArgumentException("Expected 0x68 fixture");
    Map<String, Object> checks =
        switch (scenario) {
          case "compression-consumption" -> malformedStream(input, false);
          case "compression-size-mismatch" -> malformedStream(input, true);
          case "compression-mixed" -> mixed(input);
          case "source-later-part-collision" -> collision();
          case "source-split-names" -> splitNames();
          default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        };
    System.out.println(BsaPublicObservation.json(Map.of("scenario", scenario, "checks", checks)));
  }

  /** Adds bounded trailing stream data or contradicts the decoded-size prefix in a known recipe. */
  private static Map<String, Object> malformedStream(Path input, boolean mismatch)
      throws Exception {
    byte[] original = Files.readAllBytes(input);
    byte[] raw = Arrays.copyOf(original, original.length + (mismatch ? 0 : 1));
    ByteBuffer words = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    // The independently bound two-entry recipe has its final file record at byte 76.
    int finalPayload = words.getInt(88);
    if (mismatch) words.putInt(finalPayload, words.getInt(finalPayload) + 1);
    else words.putInt(84, words.getInt(84) + 1);
    Path bad = Files.write(Path.of("malformed.bsa"), raw);
    String kind = "NONE";
    try (var archive = BethesdaArchives.standard().open(bad, OpenOptions.standard());
        var content = archive.entry(1).openContent()) {
      Channels.newInputStream(content).readAllBytes();
    } catch (ArchiveException failure) {
      kind = failure.kind().name();
    }
    return Map.of("failure_kind", kind, "mutated_size", mismatch ? "decoded" : "record");
  }

  /** Observes each independent mixed fixture record through terminal EOF. */
  private static Map<String, Object> mixed(Path input) throws Exception {
    List<Boolean> compressed = new ArrayList<>();
    List<Integer> decoded = new ArrayList<>();
    List<String> names = new ArrayList<>();
    try (var archive = BethesdaArchives.standard().open(input, OpenOptions.standard())) {
      for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
        var entry = archive.entry(ordinal);
        compressed.add(((EntryMetadata.VersionedBsa) entry.metadata().facts()).compressed());
        names.add(entry.metadata().displayName());
        try (var content = entry.openContent()) {
          decoded.add(Channels.newInputStream(content).readAllBytes().length);
        }
      }
    }
    return Map.of("compressed", compressed, "decoded_sizes", decoded, "names", names);
  }

  /** A collision in a compressed-size-derived later split must prevent publishing earlier parts. */
  private static Map<String, Object> collision() throws Exception {
    Set<String> before;
    try (var files = Files.list(Path.of("."))) {
      before = new HashSet<>(files.map(path -> path.getFileName().toString()).toList());
    }
    Path target = Path.of("out.bsa"), later = Path.of("out2.bsa");
    Files.writeString(later, "existing");
    String kind = "NONE";
    Path scratch = Files.createDirectory(Path.of("scratch")).toAbsolutePath();
    String previousTemporary = System.getProperty("java.io.tmpdir");
    List<ProgressSnapshot> progress = new ArrayList<>();
    long residuals = 0;
    // Isolate this process's spool files so cleanup observations cannot include other tasks' files.
    System.setProperty("java.io.tmpdir", scratch.toString());
    try {
      BethesdaArchives.standard()
          .pack(
              request(target, new PackOptions.Splitting.UpToBytes(250)),
              new OperationControl(progress::add, () -> false));
    } catch (ArchiveException failure) {
      kind = failure.kind().name();
      residuals =
          failure.artifacts().stream()
              .filter(artifact -> artifact.state() == ArtifactState.RESIDUAL_STAGING)
              .count();
    } finally {
      System.setProperty("java.io.tmpdir", previousTemporary);
    }
    long scratchFiles;
    try (var files = Files.list(scratch)) {
      scratchFiles = files.count();
    }
    List<String> outputs;
    try (var files = Files.list(Path.of("."))) {
      outputs =
          files
              .filter(Files::isRegularFile)
              .map(path -> path.getFileName().toString())
              .filter(name -> !before.contains(name))
              .sorted()
              .toList();
    }
    return Map.of(
        "failure_kind",
        kind,
        "first_exists",
        Files.exists(target),
        "later_content",
        Files.readString(later),
        "scratch_files",
        scratchFiles,
        "residual_artifacts",
        residuals,
        "output_files",
        outputs,
        "publishing_entered",
        progress.stream().anyMatch(p -> p.phase() == OperationPhase.PUBLISHING));
  }

  /** Reports the actual numbered sibling names and the decoded entry count of every split. */
  private static Map<String, Object> splitNames() throws Exception {
    Map<String, Object> names = new LinkedHashMap<>();
    List<Long> counts = new ArrayList<>();
    for (String base : List.of("packed.part.bsa", "archive", ".archive")) {
      var report =
          BethesdaArchives.standard()
              .pack(
                  request(Path.of(base), new PackOptions.Splitting.UpToBytes(1)),
                  OperationControl.standard());
      names.put(
          base,
          report.archiveParts().stream()
              .map(part -> part.path().getFileName().toString())
              .toList());
      for (var part : report.archiveParts()) {
        try (var archive = BethesdaArchives.standard().open(part.path(), OpenOptions.standard())) {
          counts.add(archive.entryCount());
          try (var content = archive.entry(0).openContent()) {
            if (Channels.newInputStream(content).readAllBytes().length != 1024)
              throw new IllegalStateException("Split content differs");
          }
        }
      }
    }
    String trailing = "NONE";
    Path trailingDirectory = Files.createDirectory(Path.of("trailing"));
    try {
      BethesdaArchives.standard()
          .pack(
              request(
                  trailingDirectory.resolve("archive."), new PackOptions.Splitting.UpToBytes(1)),
              OperationControl.standard());
    } catch (ArchiveException failure) {
      trailing = failure.kind().name();
    }
    long trailingFiles;
    try (var files = Files.list(trailingDirectory)) {
      trailingFiles = files.count();
    }
    return Map.of(
        "part_names",
        names,
        "entry_counts",
        counts,
        "trailing_failure",
        trailing,
        "trailing_files",
        trailingFiles);
  }

  /** Uses three compressible entries so split planning depends on stabilized packed sizes. */
  private static PackRequest request(Path target, PackOptions.Splitting splitting) {
    byte[] bytes = new byte[1024];
    Arrays.fill(bytes, (byte) 'A');
    List<PackSource> sources = new ArrayList<>();
    for (String name : List.of("a", "b", "c"))
      sources.add(
          new PackSource.GeneratedEntry(
              "meshes/" + name + ".nif",
              bytes.length,
              () -> Channels.newChannel(new ByteArrayInputStream(bytes))));
    return new PackRequest(
        target,
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
            PackOptions.Compression.ZLIB,
            false,
            splitting,
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC),
        Optional.empty());
  }
}
