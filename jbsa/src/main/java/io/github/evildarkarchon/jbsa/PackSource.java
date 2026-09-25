package io.github.evildarkarchon.jbsa;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Ordered pack input. Sources expand left to right: a later normalized-name match replaces the
 * earlier name, metadata, and payload while preserving first-insertion position until family
 * ordering. Source discovery, identity preflight, overlay resolution, and payload processing occur
 * at operation time; constructing these values performs no I/O.
 */
public sealed interface PackSource
    permits PackSource.DetectedPath, PackSource.NamedFile, PackSource.GeneratedEntry {
  /**
   * A directory, loose file, or existing archive, classified when packing begins. Directory names
   * are relative to this root, sorted by scalar-ordered normalized identity then exact name;
   * filesystem indirections are not followed. Loose files use their final path element; archives
   * use decoded entry order and retained display names. No ancestor or working directory becomes
   * the source root.
   */
  record DetectedPath(Path path) implements PackSource {
    /** Captures the supplied path without inspecting or normalizing it. */
    public DetectedPath {
      Objects.requireNonNull(path, "path");
    }
  }

  /** Loose file with an exact caller-supplied complete archive name. */
  record NamedFile(String name, Path path) implements PackSource {
    /** Captures a name and source path; family-specific name validity is operation preflight. */
    public NamedFile {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(path, "path");
    }
  }

  /**
   * Caller-generated canonical bytes with an exact complete name and declared uncompressed length.
   */
  record GeneratedEntry(String name, long length, ContentFactory contentFactory)
      implements PackSource {
    /** Captures a repeatable factory without invoking it; a negative declared length is invalid. */
    public GeneratedEntry {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(contentFactory, "contentFactory");
      if (length < 0) {
        throw new IllegalArgumentException("length must be nonnegative");
      }
    }
  }

  /** Repeatable input capability whose fresh sequential channels transfer ownership to JBSA. */
  @FunctionalInterface
  interface ContentFactory {
    /**
     * Opens a fresh channel from the beginning of the same canonical payload on every invocation.
     * JBSA may invoke this more than once for stabilization, hashing, comparison, retries, or
     * packing, and closes every returned channel. The factory must not return null or a previously
     * used channel.
     *
     * @return newly opened sequential payload channel
     * @throws IOException if the caller's payload cannot be opened
     */
    ReadableByteChannel open() throws IOException;
  }
}
