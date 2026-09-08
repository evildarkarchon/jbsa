package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Explicit local development measurements; these are not the normative PV1 qualification. */
@Tag("ba2")
@EnabledIfSystemProperty(named = "jbsa.ba2.performance", matches = "true")
final class Ba2PerformanceCheckpointIT {
  @TempDir Path directory;

  /** Measures a large metadata index independently of bulk payload size and verifies every name. */
  @Test
  void recordsTenThousandEntryMetadataCheckpoint() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    var sources = new java.util.ArrayList<PackSource>();
    for (int index = 0; index < 10_000; index++) {
      sources.add(
          new PackSource.GeneratedEntry(
              String.format(Locale.ROOT, "Data/F%05d.bin", index),
              1,
              () ->
                  java.nio.channels.Channels.newChannel(
                      new java.io.ByteArrayInputStream(new byte[] {7}))));
    }
    Path evidence =
        Path.of(System.getProperty("jbsa.reactor.root"))
            .resolve("target/ba2-performance-checkpoint/" + directory.getFileName());
    Files.createDirectories(evidence);
    StringBuilder rows =
        new StringBuilder("round,entries,archive_bytes,pack_seconds,inspect_seconds\n");
    for (int round = 0; round < 4; round++) {
      Path target = directory.resolve("metadata-" + round + ".ba2");
      long started = System.nanoTime();
      BethesdaArchives.standard()
          .pack(
              PackRequest.standard(
                  target,
                  ArchiveFamily.FO4_GENERAL_BA2,
                  new ArchiveEncoding(
                      Optional.of(new WireVersion(1)),
                      Optional.of(Ba2Subtype.GNRL),
                      OptionalLong.empty()),
                  sources,
                  Optional.empty()),
              OperationControl.standard());
      double pack = (System.nanoTime() - started) / 1e9;
      started = System.nanoTime();
      ArchiveInspection inspection = BethesdaArchives.standard().inspect(target);
      double inspect = (System.nanoTime() - started) / 1e9;
      assertEquals(10_000, inspection.entries().size());
      for (int index = 0; index < 10_000; index++) {
        EntryMetadata entry = inspection.entries().get(index);
        assertEquals(String.format(Locale.ROOT, "Data\\F%05d.bin", index), entry.displayName());
        assertEquals(1, entry.decodedSize());
      }
      if (round > 0)
        rows.append(
            String.format(
                Locale.ROOT, "%d,10000,%d,%.9f,%.9f%n", round, Files.size(target), pack, inspect));
    }
    Files.writeString(evidence.resolve("metadata.csv"), rows);
  }

  /** Records three warm measurements per codec after a discarded round on the current machine. */
  @Test
  void recordsCurrentMachineCheckpoint() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    Path sources = Files.createDirectories(directory.resolve("sources/meshes"));
    Random random = new Random(39001);
    for (int index = 0; index < 8; index++) {
      byte[] bytes = new byte[2 * 1024 * 1024];
      if (index % 2 == 0) Arrays.fill(bytes, (byte) ('A' + index));
      else random.nextBytes(bytes);
      Files.write(sources.resolve("file" + index + ".nif"), bytes);
    }
    Files.copy(sources.resolve("file0.nif"), sources.resolve("duplicate.nif"));
    Path evidence =
        Path.of(System.getProperty("jbsa.reactor.root"))
            .resolve("target/ba2-performance-checkpoint/" + directory.getFileName());
    Files.createDirectories(evidence);
    StringBuilder rows =
        new StringBuilder(
            "scenario,round,input_bytes,archive_bytes,part_count,pack_seconds,extract_seconds,inspect_seconds,random_prefix_seconds,random_prefix_count,heap_pool_peak_sum_bytes,scratch_limit_bytes\n");
    for (String scenario : List.of("stored", "zlib", "mixed", "shared-split")) {
      for (int round = 0; round < 4; round++) {
        ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .forEach(pool -> pool.resetPeakUsage());
        Path archive = directory.resolve(scenario + "-" + round + ".ba2");
        long before = System.nanoTime();
        var report =
            BethesdaArchives.standard()
                .pack(request(sources.getParent(), archive, scenario), OperationControl.standard());
        double packSeconds = (System.nanoTime() - before) / 1e9;
        assertFalse(report.archiveParts().isEmpty());
        long archiveBytes = 0;
        for (var part : report.archiveParts()) archiveBytes += Files.size(part.path());
        Path extracted =
            Files.createDirectory(directory.resolve("extracted-" + scenario + "-" + round));
        before = System.nanoTime();
        for (int partIndex = 0; partIndex < report.archiveParts().size(); partIndex++) {
          BethesdaArchives.standard()
              .extract(
                  ExtractRequest.standard(
                      report.archiveParts().get(partIndex).path(),
                      extracted.resolve("part" + partIndex)),
                  OperationControl.standard());
        }
        double extractSeconds = (System.nanoTime() - before) / 1e9;
        // Validate exact bytes outside timing; an output corruption cannot become a speed result.
        int verified = 0;
        var observedNames = new java.util.HashSet<String>();
        try (var files = Files.walk(extracted)) {
          for (Path file : files.filter(Files::isRegularFile).toList()) {
            assertEquals(-1L, Files.mismatch(sources.resolve(file.getFileName()), file));
            assertTrue(observedNames.add(file.getFileName().toString()));
            verified++;
          }
        }
        assertEquals(9, verified);
        before = System.nanoTime();
        for (var part : report.archiveParts()) {
          assertEquals(
              ArchiveDisposition.CONFORMING,
              BethesdaArchives.standard().inspect(part.path()).assessment().disposition());
        }
        double inspectSeconds = (System.nanoTime() - before) / 1e9;
        double randomSeconds;
        try (OpenArchive opened =
            BethesdaArchives.standard().open(archive, OpenOptions.standard())) {
          ByteBuffer buffer = ByteBuffer.allocate(4096);
          before = System.nanoTime();
          for (int index = 0; index < 64; index++) {
            buffer.clear();
            try (EntryContent content =
                opened.entry((index * 5) % opened.entryCount()).openContent()) {
              while (buffer.hasRemaining()) assertTrue(content.read(buffer) > 0);
            }
          }
          randomSeconds = (System.nanoTime() - before) / 1e9;
        }
        long peakSum =
            ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
        if (round > 0) {
          rows.append(
              String.format(
                  Locale.ROOT,
                  "%s,%d,%d,%d,%d,%.9f,%.9f,%.9f,%.9f,64,%d,%d%n",
                  scenario,
                  round,
                  18L * 1024 * 1024,
                  archiveBytes,
                  report.archiveParts().size(),
                  packSeconds,
                  extractSeconds,
                  inspectSeconds,
                  randomSeconds,
                  peakSum,
                  64L * 1024 * 1024));
          // Preserve completed observations before beginning the next measured operation.
          Files.writeString(evidence.resolve("measurements.csv"), rows);
        }
      }
    }
    Files.writeString(
        evidence.resolve("conditions.txt"),
        "Development checkpoint; NOT formal PV1 qualification.\n"
            + "Current-machine development observation without idle or reboot gating.\n"
            + "Concurrent development work may affect timings.\n"
            + "One warmup and three measured rounds; sequential library calls; 18 MiB mixed corpus.\n"
            + "Stored, zlib, per-entry mixed codecs, and zlib sharing with 4 MiB advisory splitting.\n"
            + "Random access measures 64 entry opens with 4 KiB prefix reads, not seeking.\n"
            + "Heap is the sum of memory-pool peaks, not simultaneous live heap or process memory.\n"
            + "Scratch is the enforced request ceiling; no observed peak is claimed.\n"
            + "No oracle timing comparison, JMH, confidence interval, or release qualification.\n"
            + "codec_profile_id=jbsa-jdk-zlib-v1\n"
            + "codec_profile_sha256=b9515f305ba223111b790ad06c98580360ac85c40235258316aad2ba001a3fda\n"
            + "java.version="
            + System.getProperty("java.version")
            + "\n"
            + "os.name="
            + System.getProperty("os.name")
            + "\n"
            + "availableProcessors="
            + Runtime.getRuntime().availableProcessors()
            + "\n");
  }

  /** Uses explicit sequential scenario choices and a bounded scratch ceiling for the checkpoint. */
  private static PackRequest request(Path source, Path archive, String scenario) {
    var limits = ResourceLimits.standard();
    return new PackRequest(
        archive,
        ArchiveFamily.FO4_GENERAL_BA2,
        new ArchiveEncoding(
            Optional.of(new WireVersion(1)), Optional.of(Ba2Subtype.GNRL), OptionalLong.empty()),
        Optional.empty(),
        List.of(new PackSource.DetectedPath(source)),
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        new ResourceLimits(
            limits.maxEntries(),
            limits.maxMetadataBytes(),
            limits.maxDecodedBytes(),
            64L * 1024 * 1024,
            limits.maxOutputs(),
            limits.maxDiagnostics(),
            limits.maxSecondaryFailures()),
        new WorkerSelection.UpTo(1),
        new PackOptions(
            List.of(),
            scenario.equals("stored")
                ? PackOptions.Compression.STORED
                : PackOptions.Compression.ZLIB,
            scenario.equals("shared-split"),
            new PackOptions.Splitting.UpToBytes(
                scenario.equals("shared-split") ? 4L * 1024 * 1024 : 0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC,
            scenario.equals("mixed")
                ? Map.of(
                    new NormalizedNameIdentity("meshes\\file1.nif"), PackOptions.Compression.STORED)
                : Map.of()),
        Optional.empty());
  }
}
