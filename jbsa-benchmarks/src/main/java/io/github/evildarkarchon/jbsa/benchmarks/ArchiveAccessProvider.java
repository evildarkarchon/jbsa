package io.github.evildarkarchon.jbsa.benchmarks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Build-only service seam for benchmarks of public archive APIs. No implementation is shipped until
 * the production random-access API exists; missing providers must fail qualification setup.
 */
public interface ArchiveAccessProvider {
  /**
   * Returns the immutable codec/provider/configuration identity bound by the performance manifest.
   */
  String identity();

  /**
   * Opens the archive and verifies its external entry manifest outside the timed region.
   * Implementors must delegate lookup and payload reads to production public APIs, and must not
   * generate fixtures.
   */
  ArchiveAccess open(Path archive, Path manifest) throws IOException;

  /** One manifest-declared logical entry, independent of the archive's reported size. */
  record Entry(String key, long uncompressedBytes) {
    /** Rejects invalid manifest entries before any measurement begins. */
    public Entry {
      if (key == null || key.isEmpty() || uncompressedBytes < 0) {
        throw new IllegalArgumentException("Invalid manifest entry");
      }
    }
  }

  /** An archive owned by one JMH trial and used only by its benchmark thread. */
  interface ArchiveAccess extends AutoCloseable {
    /** Returns immutable manifest order, with exact names and declared logical sizes. */
    List<Entry> entries();

    /** Performs one public metadata lookup and returns its result for consumption by JMH. */
    Object lookup(String key) throws IOException;

    /** Opens the public payload stream; the benchmark closes it after verified normal EOF. */
    InputStream read(String key) throws IOException;

    /** Releases archive resources after the trial. */
    @Override
    void close() throws IOException;
  }
}
