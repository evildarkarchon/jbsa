package io.github.evildarkarchon.jbsa;

/** Format-intrinsic classification independent of policy and extraction eligibility. */
public enum ArchiveDisposition {
  /** Encoded content conforms within the declared validation extent. */
  CONFORMING,
  /** Content is bounded and decodable but is not canonical encoder output. */
  TOLERATED_NONCANONICAL,
  /** Encoded content is rejected within the declared validation extent. */
  REJECTED
}
