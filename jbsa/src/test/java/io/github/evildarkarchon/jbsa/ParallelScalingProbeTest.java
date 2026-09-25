package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Manual issue-48 performance checkpoint through the public archive operation API. */
@EnabledOnOs(OS.WINDOWS)
class ParallelScalingProbeTest {
  private static final int ENTRIES = 32;
  private static final int BYTES_PER_ENTRY = 1024 * 1024;
  @TempDir Path temporary;

  /** Records paired worker-count throughput, sampled heap, output size, and byte identity. */
  @Test
  void recordWorkerScalingWhenRequested() throws Exception {
    Assumptions.assumeTrue("1".equals(System.getenv("JBSA_ISSUE48_SCALING")));
    byte[] payload = new byte[BYTES_PER_ENTRY];
    for (int index = 0; index < payload.length; index++)
      payload[index] = (byte) (index * 31 + (index >>> 10));
    List<PackSource> sources = new ArrayList<>();
    for (int index = 0; index < ENTRIES; index++)
      sources.add(
          new PackSource.GeneratedEntry(
              "Data/Entry" + index + ".bin",
              payload.length,
              () -> Channels.newChannel(new ByteArrayInputStream(payload))));
    List<String> observations = new ArrayList<>();
    String expectedHash = null;
    long inputBytes = (long) ENTRIES * BYTES_PER_ENTRY;
    for (int round = 0; round < 3; round++) {
      for (int workers : new int[] {1, 2, 4, 8, 16}) {
        Path target = temporary.resolve("round-" + round + "-workers-" + workers + ".ba2");
        PackRequest request = request(target, sources, workers);
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        long baselineHeapUsed = runtime.totalMemory() - runtime.freeMemory();
        AtomicBoolean sampling = new AtomicBoolean(true);
        AtomicLong peakHeapUsed = new AtomicLong();
        AtomicLong peakHeapCommitted = new AtomicLong();
        Thread sampler =
            Thread.ofPlatform()
                .daemon(true)
                .name("issue48-heap-sampler")
                .start(
                    () -> {
                      while (sampling.get()) {
                        long committed = runtime.totalMemory();
                        peakHeapCommitted.accumulateAndGet(committed, Math::max);
                        peakHeapUsed.accumulateAndGet(committed - runtime.freeMemory(), Math::max);
                        LockSupport.parkNanos(5_000_000L);
                      }
                    });
        long start = System.nanoTime();
        OperationReport report;
        try {
          report = BethesdaArchives.standard().pack(request, OperationControl.standard());
        } catch (ArchiveException failure) {
          throw new AssertionError(
              "round="
                  + round
                  + " workers="
                  + workers
                  + " primary="
                  + failure.primaryFailure()
                  + " secondary="
                  + failure.secondaryFailures(),
              failure);
        } finally {
          sampling.set(false);
          sampler.join();
        }
        long elapsed = System.nanoTime() - start;
        assertEquals(1, report.archiveParts().size());
        String hash = sha256(target);
        if (expectedHash == null) expectedHash = hash;
        else assertEquals(expectedHash, hash, "worker count changed deterministic bytes");
        double seconds = elapsed / 1_000_000_000.0;
        double mibPerSecond = inputBytes / (1024.0 * 1024.0) / seconds;
        observations.add(
            "{\"round\":"
                + round
                + ",\"workers\":"
                + workers
                + ",\"wallMillis\":"
                + Math.round(elapsed / 1_000_000.0)
                + ",\"throughputMiBPerSecond\":"
                + String.format(java.util.Locale.ROOT, "%.3f", mibPerSecond)
                + ",\"peakHeapUsedBytes\":"
                + peakHeapUsed.get()
                + ",\"baselineHeapUsedBytes\":"
                + baselineHeapUsed
                + ",\"peakAdditionalHeapBytes\":"
                + Math.max(0, peakHeapUsed.get() - baselineHeapUsed)
                + ",\"peakHeapCommittedBytes\":"
                + peakHeapCommitted.get()
                + ",\"outputBytes\":"
                + Files.size(target)
                + ",\"sha256\":\""
                + hash
                + "\"}");
      }
    }
    Path evidence = Path.of("target", "issue48-scaling.json");
    Files.createDirectories(evidence.getParent());
    Files.writeString(
        evidence,
        "{\"recordedUtc\":\""
            + Instant.now()
            + "\",\"javaVersion\":\""
            + System.getProperty("java.version")
            + "\",\"availableProcessors\":"
            + Runtime.getRuntime().availableProcessors()
            + ",\"inputBytes\":"
            + inputBytes
            + ",\"observations\":["
            + String.join(",", observations)
            + "]}\n");
  }

  /** Selects General BA2 zlib compression without sharing or splitting. */
  private static PackRequest request(Path target, List<PackSource> sources, int workers) {
    return new PackRequest(
        target,
        ArchiveFamily.FO4_GENERAL_BA2,
        new ArchiveEncoding(
            Optional.of(new WireVersion(1)), Optional.of(Ba2Subtype.GNRL), OptionalLong.empty()),
        Optional.empty(),
        sources,
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        ResourceLimits.standard(),
        new WorkerSelection.UpTo(workers),
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC),
        Optional.empty());
  }

  /** Streams the output digest without allocating a whole-archive byte array. */
  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
      input.transferTo(java.io.OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
