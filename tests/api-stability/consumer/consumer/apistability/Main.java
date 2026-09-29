package consumer.apistability;

import io.github.evildarkarchon.jbsa.*;
import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** A fixed external caller compiled once to probe binary linkage across library changes. */
public final class Main {
  private Main() {}

  /**
   * Exercises the compiled facade, requests, results, owned channels, and checked failure types.
   *
   * @param args a writable directory used only for this consumer run
   * @throws Exception if any public operation or compatibility assertion fails
   */
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    byte[] expected = {1, 2, 3, 4};
    Path archivePath = root.resolve("sample.bsa");
    PackSource.GeneratedEntry source =
        new PackSource.GeneratedEntry(
            "meshes/sample.txt",
            expected.length,
            () -> Channels.newChannel(new ByteArrayInputStream(expected)));
    BethesdaArchives library = BethesdaArchives.standard();
    PackRequest packRequest =
        PackRequest.standard(
            archivePath,
            ArchiveFamily.TES3_BSA,
            ArchiveEncoding.tes3(),
            List.of(source),
            Optional.empty());
    requirePublished(library.pack(packRequest, OperationControl.standard()), Operation.PACK);

    ArchiveDetection detection = library.detect(archivePath);
    require(detection.status() == DetectionStatus.SUPPORTED_FAMILY, "detection status");
    require(detection.family().orElseThrow() == ArchiveFamily.TES3_BSA, "archive family");
    ArchiveInspection inspection = library.inspect(archivePath);
    require(inspection.equals(library.inspect(archivePath, OpenOptions.standard())), "inspection");
    require(inspection.entries().size() == 1, "inspection entries");
    try (OpenArchive archive = library.open(archivePath, OpenOptions.standard())) {
      require(archive.entryCount() == 1L, "owned entry count");
      ArchiveEntry entry = archive.entry(0L);
      require(entry.metadata().decodedSize() == expected.length, "detached metadata");
      try (EntryContent content = entry.openContent()) {
        require(
            Arrays.equals(expected, Channels.newInputStream(content).readAllBytes()),
            "owned payload bytes");
        require(content.assessment().isPresent(), "terminal assessment");
      }
    }

    Path extracted = root.resolve("extracted");
    ExtractRequest extractRequest = ExtractRequest.standard(archivePath, extracted);
    requirePublished(library.extract(extractRequest, OperationControl.standard()), Operation.EXTRACT);
    require(
        Arrays.equals(expected, Files.readAllBytes(extracted.resolve("meshes/sample.txt"))),
        "extracted bytes");
    try {
      library.detect(root.resolve("missing.bsa"));
      throw new AssertionError("missing source was accepted");
    } catch (ArchiveException failure) {
      require(failure.kind() == FailureKind.SOURCE, "checked failure kind");
    }
  }

  /** Requires a synchronous mutation to publish at least one artifact. */
  private static void requirePublished(OperationReport report, Operation operation) {
    require(report.operation() == operation, "reported operation");
    require(!report.artifacts().isEmpty(), "published artifact");
    require(report.artifacts().getFirst().state() == ArtifactState.PUBLISHED, "artifact state");
  }

  /** Makes a broken binary or semantic contract fail the external process. */
  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
