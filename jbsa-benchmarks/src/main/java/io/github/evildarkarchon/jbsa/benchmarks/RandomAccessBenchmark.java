package io.github.evildarkarchon.jbsa.benchmarks;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;

/** Trial-scoped public archive access with the performance-v1 JMH protocol defaults. */
@BenchmarkMode({Mode.SampleTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(
    value = 3,
    jvmArgs = {"-Xms4g", "-Xmx4g", "-XX:+AlwaysPreTouch", "-XX:+UseG1GC"})
@State(Scope.Thread)
public class RandomAccessBenchmark {
  @Param({"UNCONFIGURED"})
  public String archivePath;

  @Param({"UNCONFIGURED"})
  public String manifestPath;

  @Param({"UNCONFIGURED"})
  public String providerIdentity;

  @Param({"UNCONFIGURED"})
  public String seed;

  private ArchiveAccessProvider.ArchiveAccess archive;
  private List<ArchiveAccessProvider.Entry> entries;
  private SplittableRandom random;
  private byte[] buffer;

  /**
   * Opens a single manifest-identified public adapter and validates a fixed-size payload class.
   * Setup fails when production APIs are unavailable; synthetic throughput must not become release
   * evidence.
   */
  @Setup(Level.Trial)
  public void setup(BenchmarkParams parameters) throws IOException {
    random = new SplittableRandom(Long.parseLong(seed));
    var providers =
        ServiceLoader.load(ArchiveAccessProvider.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(provider -> provider.identity().equals(providerIdentity))
            .toList();
    if (providers.size() != 1) {
      throw new IllegalStateException(
          "Exactly one production archive provider is required: " + providerIdentity);
    }
    archive = providers.getFirst().open(Path.of(archivePath), Path.of(manifestPath));
    try {
      entries = List.copyOf(archive.entries());
      if (entries.isEmpty()) {
        throw new IllegalArgumentException("The manifest has no entries");
      }
      // A fixed-size class converts each actual JMH throughput sample to logical MiB/s exactly.
      long size = entries.getFirst().uncompressedBytes();
      if (parameters.getBenchmark().endsWith(".payload")
          && entries.stream().anyMatch(entry -> entry.uncompressedBytes() != size)) {
        throw new IllegalArgumentException(
            "Random-access cases require one fixed-size payload class");
      }
      buffer = new byte[64 * 1024];
    } catch (RuntimeException failure) {
      archive.close();
      archive = null;
      throw failure;
    }
  }

  /** Measures one selected metadata lookup, separately from payload access. */
  @Benchmark
  public void metadata(Blackhole blackhole) throws IOException {
    var entry = entries.get(random.nextInt(entries.size()));
    Object metadata = archive.lookup(entry.key());
    if (metadata == null) {
      throw new IOException("Manifest entry missing from archive index: " + entry.key());
    }
    blackhole.consume(metadata);
  }

  /** Measures a read only after normal EOF and exact manifest length, including stream close. */
  @Benchmark
  public void payload(Blackhole blackhole) throws IOException {
    var entry = entries.get(random.nextInt(entries.size()));
    try (var stream = archive.read(entry.key())) {
      blackhole.consume(PayloadRead.consume(stream, entry.uncompressedBytes(), buffer));
      blackhole.consume(buffer);
    }
  }

  /** Closes trial-owned resources after all warmup and measurement iterations. */
  @TearDown(Level.Trial)
  public void tearDown() throws IOException {
    if (archive != null) {
      archive.close();
    }
  }
}
