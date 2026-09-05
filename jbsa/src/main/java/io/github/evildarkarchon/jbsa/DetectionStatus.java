package io.github.evildarkarchon.jbsa;

/** Outcome of bounded recognition; no variant assigns an archive disposition. */
public enum DetectionStatus {
  UNRECOGNIZED,
  INDETERMINATE,
  SUPPORTED_FAMILY,
  UNSUPPORTED_VARIANT
}
