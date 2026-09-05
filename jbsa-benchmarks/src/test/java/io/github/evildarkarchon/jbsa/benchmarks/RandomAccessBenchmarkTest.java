package io.github.evildarkarchon.jbsa.benchmarks;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RandomAccessBenchmarkTest {
  /** Ensures missing production archive APIs cannot produce synthetic qualification evidence. */
  @Test
  void refusesSyntheticQualificationWhenTheProductionProviderDoesNotExist() {
    var benchmark = new RandomAccessBenchmark();
    benchmark.seed = "32";
    benchmark.providerIdentity = "production-unimplemented";
    benchmark.archivePath = "archive.bsa";
    benchmark.manifestPath = "entries.json";
    assertThrows(IllegalStateException.class, () -> benchmark.setup(null));
  }

  /** Rejects an unconfigured selection seed before opening trial resources. */
  @Test
  void requiresAnExplicitManifestSeedBeforeOpeningAnArchive() {
    var benchmark = new RandomAccessBenchmark();
    benchmark.seed = "UNCONFIGURED";
    assertThrows(NumberFormatException.class, () -> benchmark.setup(null));
  }
}
