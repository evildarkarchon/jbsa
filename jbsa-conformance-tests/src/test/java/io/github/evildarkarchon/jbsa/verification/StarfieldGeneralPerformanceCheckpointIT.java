package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Local risk-based Starfield General measurements; never a release-wide performance claim. */
@Tag("ba2")
@EnabledIfSystemProperty(named = "jbsa.ba2.performance", matches = "true")
@EnabledOnOs(OS.WINDOWS)
final class StarfieldGeneralPerformanceCheckpointIT {
  @TempDir Path directory;

  /** Records one warmup and three measured zlib/raw-LZ4 rounds with exact output validation. */
  @Test
  void recordsZlibAndRawLz4Checkpoint() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    Path sources = Files.createDirectories(directory.resolve("sources/data"));
    Random random = new Random(45003);
    for (int index = 0; index < 4; index++) {
      byte[] bytes = new byte[1024 * 1024];
      if (index % 2 == 0) Arrays.fill(bytes, (byte) ('A' + index));
      else random.nextBytes(bytes);
      Files.write(sources.resolve("file" + index + ".bin"), bytes);
    }
    Path evidence =
        Path.of(System.getProperty("jbsa.reactor.root"))
            .resolve("target/starfield-general-performance/" + directory.getFileName());
    Files.createDirectories(evidence);
    StringBuilder rows =
        new StringBuilder(
            "codec,round,input_bytes,archive_bytes,pack_seconds,extract_seconds,random_prefix_seconds,random_prefix_count,heap_pool_peak_sum_bytes,process_peak_working_set_bytes,scratch_limit_bytes\n");
    for (PackOptions.Compression codec :
        List.of(PackOptions.Compression.ZLIB, PackOptions.Compression.LZ4_RAW)) {
      for (int round = 0; round < 4; round++) {
        ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .forEach(pool -> pool.resetPeakUsage());
        Path archive = directory.resolve(codec + "-" + round + ".ba2");
        long started = System.nanoTime();
        BethesdaArchives.standard()
            .pack(request(sources.getParent(), archive, codec), OperationControl.standard());
        double packSeconds = (System.nanoTime() - started) / 1e9;
        ByteBuffer header =
            ByteBuffer.wrap(Files.readAllBytes(archive), 0, 36).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(codec == PackOptions.Compression.LZ4_RAW ? 3 : 2, header.getInt(4));
        if (codec == PackOptions.Compression.LZ4_RAW) assertEquals(3, header.getInt(32));
        Path extracted = Files.createDirectory(directory.resolve(codec + "-out-" + round));
        started = System.nanoTime();
        BethesdaArchives.standard()
            .extract(ExtractRequest.standard(archive, extracted), OperationControl.standard());
        double extractSeconds = (System.nanoTime() - started) / 1e9;
        for (int index = 0; index < 4; index++)
          assertEquals(
              -1L,
              Files.mismatch(
                  sources.resolve("file" + index + ".bin"),
                  extracted.resolve("data/file" + index + ".bin")));
        double randomPrefixSeconds;
        try (OpenArchive opened =
            BethesdaArchives.standard().open(archive, OpenOptions.standard())) {
          ByteBuffer prefix = ByteBuffer.allocate(4096);
          started = System.nanoTime();
          for (int index = 0; index < 16; index++) {
            prefix.clear();
            try (EntryContent content = opened.entry(index % 4).openContent()) {
              while (prefix.hasRemaining()) assertTrue(content.read(prefix) > 0);
            }
          }
          randomPrefixSeconds = (System.nanoTime() - started) / 1e9;
        }
        long heapPeak =
            ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
        long processPeak = processPeakWorkingSet();
        if (round > 0) {
          rows.append(
              String.format(
                  Locale.ROOT,
                  "%s,%d,%d,%d,%.9f,%.9f,%.9f,16,%d,%d,%d%n",
                  codec,
                  round,
                  4L * 1024 * 1024,
                  Files.size(archive),
                  packSeconds,
                  extractSeconds,
                  randomPrefixSeconds,
                  heapPeak,
                  processPeak,
                  64L * 1024 * 1024));
          Files.writeString(evidence.resolve("measurements.csv"), rows);
        }
      }
    }
    Files.writeString(
        evidence.resolve("conditions.txt"),
        "Development checkpoint; NOT release-wide performance qualification.\n"
            + "One discarded warmup and three measured rounds; sequential public operations.\n"
            + "Four 1 MiB entries alternate compressible and seeded-random bytes.\n"
            + "Archive bytes derive output size from the measured pack; no duplicate size run.\n"
            + "Extraction bytes are compared outside timing; 16 random 4 KiB prefix reads are timed.\n"
            + "Heap is the sum of pool peaks; process peak is Windows PeakWorkingSet64 since JVM start.\n"
            + "Process peak is sampled after each round and is cumulative, so later rows cannot decrease.\n"
            + "Scratch is the enforced request ceiling; no observed scratch peak is claimed.\n"
            + "native_loading_test=io.github.evildarkarchon.jbsa.internal.io.Lz4LaunchTest#qualifiesClasspathLaunchPolicyAndLazyMissingArtifacts\n"
            + "native_loading_result=PASS\n"
            + "zlib_provider=java.util.zip.Deflater/Inflater\n"
            + "zlib_profile=RFC1950-level9-default-strategy-nowrap-false\n"
            + "shared_codec_manifest_id=jbsa-lz4-v1\n"
            + "shared_codec_manifest_sha256=f7221b24458804a454716fb89bbad9f6b3947f8484158e3950e1608e93b4732e\n"
            + "raw_lz4_profile_id=jbsa-starfield-general-lz4-v1\n"
            + "raw_lz4_profile_sha256=7bf05e18f8baf343fd7ff3252b8544b6782c5940ec883bbf7e931254ba354bc9\n"
            + "java.version="
            + System.getProperty("java.version")
            + "\nos.name="
            + System.getProperty("os.name")
            + "\navailableProcessors="
            + Runtime.getRuntime().availableProcessors()
            + "\n");
  }

  /** Reads the Windows process-lifetime peak working set without estimating native allocations. */
  private static long processPeakWorkingSet() throws Exception {
    Process process =
        new ProcessBuilder(
                "pwsh",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "(Get-Process -Id " + ProcessHandle.current().pid() + ").PeakWorkingSet64")
            .redirectErrorStream(true)
            .start();
    byte[] output = process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(
        0, process.exitValue(), new String(output, java.nio.charset.StandardCharsets.UTF_8));
    return Long.parseLong(new String(output, java.nio.charset.StandardCharsets.UTF_8).trim());
  }

  /** Constructs one bounded sequential Starfield request with codec-derived wire selectors. */
  private static PackRequest request(
      Path source, Path archive, PackOptions.Compression compression) {
    boolean raw = compression == PackOptions.Compression.LZ4_RAW;
    ResourceLimits standard = ResourceLimits.standard();
    return new PackRequest(
        archive,
        ArchiveFamily.STARFIELD_GENERAL_BA2,
        new ArchiveEncoding(
            Optional.of(new WireVersion(raw ? 3 : 2)),
            Optional.of(Ba2Subtype.GNRL),
            raw ? OptionalLong.of(3) : OptionalLong.empty()),
        Optional.empty(),
        List.of(new PackSource.DetectedPath(source)),
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        new ResourceLimits(
            standard.maxEntries(),
            standard.maxMetadataBytes(),
            standard.maxDecodedBytes(),
            64L * 1024 * 1024,
            standard.maxOutputs(),
            standard.maxDiagnostics(),
            standard.maxSecondaryFailures()),
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
