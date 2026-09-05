package io.github.evildarkarchon.jbsa;

/** Logical work units reported by a progress snapshot. */
public enum ProgressMetric {
  /** Logical entries, independent of retries and replay. */
  ENTRIES,
  /** Uncompressed logical payload bytes, independent of codec framing and sharing. */
  BYTES,
  /** Artifacts whose commit or cleanup state has settled. */
  ARTIFACTS
}
