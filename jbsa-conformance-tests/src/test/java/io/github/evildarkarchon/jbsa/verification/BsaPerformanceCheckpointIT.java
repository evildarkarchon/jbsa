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
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Explicit local development measurements; these are not the normative PV1 qualification. */
@Tag("bsa")
@EnabledIfSystemProperty(named = "jbsa.bsa.performance", matches = "true")
final class BsaPerformanceCheckpointIT {
  @TempDir Path directory;

  /** Records matched versioned-BSA workloads, discarding the first round of each family codec. */
  @ParameterizedTest
  @ValueSource(ints = {0x67, 0x68, 0x69})
  void recordsCurrentMachineCheckpoint(int version) throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    Path sources = Files.createDirectories(directory.resolve("sources/meshes"));
    Random random = new Random(38067);
    for (int index = 0; index < 8; index++) {
      byte[] bytes = new byte[2 * 1024 * 1024];
      if (index % 2 == 0) Arrays.fill(bytes, (byte) ('A' + index));
      else random.nextBytes(bytes);
      Files.write(sources.resolve("file" + index + ".nif"), bytes);
    }
    Path evidence =
        Path.of(System.getProperty("jbsa.reactor.root"))
            .resolve(
                "target/bsa-performance-checkpoint/" + version + "-" + directory.getFileName());
    Files.createDirectories(evidence);
    StringBuilder rows =
        new StringBuilder(
            "codec,round,input_bytes,archive_bytes,pack_seconds,extract_seconds,random_prefix_seconds,random_prefix_count,heap_pool_peak_sum_bytes,scratch_limit_bytes\n");
    List<PackOptions.Compression> codecs =
        List.of(
            PackOptions.Compression.STORED,
            version == 0x69 ? PackOptions.Compression.LZ4_FRAME : PackOptions.Compression.ZLIB);
    for (var compression : codecs) {
      for (int round = 0; round < 4; round++) {
        ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .forEach(pool -> pool.resetPeakUsage());
        Path archive = directory.resolve(compression + "-" + round + ".bsa");
        long before = System.nanoTime();
        BethesdaArchives.standard()
            .pack(
                request(sources.getParent(), archive, compression, version),
                OperationControl.standard());
        double packSeconds = (System.nanoTime() - before) / 1e9;
        Path extracted = directory.resolve("extracted-" + compression + "-" + round);
        before = System.nanoTime();
        BethesdaArchives.standard()
            .extract(ExtractRequest.standard(archive, extracted), OperationControl.standard());
        double extractSeconds = (System.nanoTime() - before) / 1e9;
        // Validate exact bytes outside timing; an output corruption cannot become a speed result.
        for (int index = 0; index < 8; index++) {
          assertEquals(
              -1L,
              Files.mismatch(
                  sources.resolve("file" + index + ".nif"),
                  extracted.resolve("meshes/file" + index + ".nif")));
        }
        double randomSeconds;
        try (OpenArchive opened =
            BethesdaArchives.standard().open(archive, OpenOptions.standard())) {
          ByteBuffer buffer = ByteBuffer.allocate(4096);
          before = System.nanoTime();
          for (int index = 0; index < 64; index++) {
            buffer.clear();
            try (EntryContent content = opened.entry((index * 5) % 8).openContent()) {
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
                  "%s,%d,%d,%d,%.9f,%.9f,%.9f,64,%d,%d%n",
                  compression,
                  round,
                  16L * 1024 * 1024,
                  Files.size(archive),
                  packSeconds,
                  extractSeconds,
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
            + "Requested issue regression measurement; no idle or reboot attestation.\n"
            + "wire_version="
            + version
            + "\n"
            + "Concurrent development work may affect timings.\n"
            + "One warmup and three measured rounds; sequential library calls; 16 MiB mixed corpus.\n"
            + "Random access measures 64 entry opens with 4 KiB prefix reads, not seeking.\n"
            + "Heap is the sum of memory-pool peaks, not simultaneous live heap or process memory.\n"
            + "Scratch is the enforced request ceiling; no observed peak is claimed.\n"
            + "No oracle timing comparison, JMH, confidence interval, or release qualification.\n"
            + "codec_profile_id="
            + (version == 0x69 ? "jbsa-bsa-069-lz4-v1" : "jbsa-lz4-v1")
            + "\n"
            + "codec_profile_sha256="
            + (version == 0x69
                ? "3eb01cfdf11f0052406682b4cb0ffd7814de095d7dd2a543ed9d9441fa41ef85"
                : "f7221b24458804a454716fb89bbad9f6b3947f8484158e3950e1608e93b4732e")
            + "\n"
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

  /**
   * Uses an explicit sequential, unshared plan and a bounded scratch ceiling for the checkpoint.
   */
  private static PackRequest request(
      Path source, Path archive, PackOptions.Compression compression, int version) {
    var limits = ResourceLimits.standard();
    return new PackRequest(
        archive,
        switch (version) {
          case 0x67 -> ArchiveFamily.TES4_BSA;
          case 0x68 -> ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA;
          case 0x69 -> ArchiveFamily.SSE_BSA;
          default -> throw new IllegalArgumentException("Unsupported BSA version");
        },
        new ArchiveEncoding(
            Optional.of(new WireVersion(version)), Optional.empty(), OptionalLong.empty()),
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
            compression,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC),
        Optional.empty());
  }
}
