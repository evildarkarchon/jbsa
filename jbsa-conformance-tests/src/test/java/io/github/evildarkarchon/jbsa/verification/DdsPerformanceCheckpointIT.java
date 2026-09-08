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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Explicit DDS public-seam development checkpoint; never a normative PV1 qualification. */
@Tag("dds")
@EnabledIfSystemProperty(named = "jbsa.dds.performance", matches = "true")
final class DdsPerformanceCheckpointIT {
  @TempDir Path directory;

  /** Measures complete chunk packing/reconstruction and independent entry opens over known DDS. */
  @Test
  void recordsChunkingReconstructionAndRandomAccess() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path source = root.resolve("target/dds-validator-fixtures/source");
    assertTrue(Files.isDirectory(source), "Run build/generate-dds-fixtures.py first");
    Path evidence = root.resolve("target/dds-performance-checkpoint/" + directory.getFileName());
    Files.createDirectories(evidence);
    StringBuilder rows =
        new StringBuilder(
            "round,entries,input_bytes,archive_bytes,pack_seconds,reconstruction_seconds,inspect_seconds,random_prefix_seconds,random_prefix_count,heap_pool_peak_sum_bytes\n");
    long inputBytes;
    try (var files = Files.walk(source)) {
      inputBytes =
          files
              .filter(Files::isRegularFile)
              .mapToLong(
                  path -> {
                    try {
                      return Files.size(path);
                    } catch (java.io.IOException error) {
                      throw new java.io.UncheckedIOException(error);
                    }
                  })
              .sum();
    }
    for (int round = 0; round < 4; round++) {
      ManagementFactory.getMemoryPoolMXBeans().stream()
          .filter(pool -> pool.getType() == MemoryType.HEAP)
          .forEach(pool -> pool.resetPeakUsage());
      Path archive = directory.resolve("dds-" + round + ".ba2");
      long started = System.nanoTime();
      BethesdaArchives.standard()
          .pack(
              PackRequest.standard(
                  archive,
                  ArchiveFamily.FO4_DDS_BA2,
                  new ArchiveEncoding(
                      Optional.of(new WireVersion(1)),
                      Optional.of(Ba2Subtype.DX10),
                      OptionalLong.empty()),
                  List.of(new PackSource.DetectedPath(source)),
                  Optional.of(DdsTarget.PC)),
              OperationControl.standard());
      double pack = (System.nanoTime() - started) / 1e9;
      Path extracted = directory.resolve("extracted-" + round);
      started = System.nanoTime();
      BethesdaArchives.standard()
          .extract(ExtractRequest.standard(archive, extracted), OperationControl.standard());
      double reconstruction = (System.nanoTime() - started) / 1e9;
      started = System.nanoTime();
      ArchiveInspection inspection = BethesdaArchives.standard().inspect(archive);
      double inspect = (System.nanoTime() - started) / 1e9;
      assertEquals(33, inspection.entries().size());
      assertEquals(ArchiveDisposition.CONFORMING, inspection.assessment().disposition());
      // DDS envelopes normalize during packing. Compare opaque image bytes outside timing.
      for (EntryMetadata entry : inspection.entries()) {
        Path relative = Path.of(entry.displayName().replace('\\', '/'));
        byte[] original = Files.readAllBytes(source.resolve(relative));
        byte[] decoded = Files.readAllBytes(extracted.resolve(relative));
        int envelope =
            decoded[84] == 'D' && decoded[85] == 'X' && decoded[86] == '1' && decoded[87] == '0'
                ? 148
                : 128;
        assertArrayEquals(
            Arrays.copyOfRange(original, 148, original.length),
            Arrays.copyOfRange(decoded, envelope, decoded.length));
      }
      double random;
      try (OpenArchive opened = BethesdaArchives.standard().open(archive, OpenOptions.standard())) {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        started = System.nanoTime();
        for (int index = 0; index < 64; index++) {
          ArchiveEntry entry = opened.entry((index * 17) % opened.entryCount());
          buffer.clear();
          buffer.limit((int) Math.min(buffer.capacity(), entry.metadata().decodedSize()));
          try (EntryContent content = entry.openContent()) {
            while (buffer.hasRemaining()) assertTrue(content.read(buffer) > 0);
          }
        }
        random = (System.nanoTime() - started) / 1e9;
      }
      long peak =
          ManagementFactory.getMemoryPoolMXBeans().stream()
              .filter(pool -> pool.getType() == MemoryType.HEAP)
              .mapToLong(pool -> pool.getPeakUsage().getUsed())
              .sum();
      if (round > 0) {
        rows.append(
            String.format(
                Locale.ROOT,
                "%d,33,%d,%d,%.9f,%.9f,%.9f,%.9f,64,%d%n",
                round,
                inputBytes,
                Files.size(archive),
                pack,
                reconstruction,
                inspect,
                random,
                peak));
        Files.writeString(evidence.resolve("measurements.csv"), rows);
      }
    }
    Files.writeString(
        evidence.resolve("conditions.txt"),
        "Development checkpoint only; not PV1. One discarded warmup, three retained rounds.\n"
            + "33 generated DDS inputs, 25 formats, 1-4 chunks, small/odd/non-square/cubemap.\n"
            + "Public library family-default zlib; current machine with concurrent development.\n"
            + "Random reads are 64 fresh entry opens, up to 4 KiB prefixes, not seeking.\n"
            + "Heap pool peak sum is not simultaneous live heap or process peak memory.\n"
            + "No oracle timing ratio, confidence bound, scratch peak, or JMH result.\n"
            + "java.version="
            + System.getProperty("java.version")
            + "\n"
            + "os.name="
            + System.getProperty("os.name")
            + "\n");
  }
}
