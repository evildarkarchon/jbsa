package io.github.evildarkarchon.jbsa;

/** An observed or requested unsigned 32-bit wire version, including unsupported versions. */
public record WireVersion(long value) {
  /** Rejects values that cannot be represented by the wire selector. */
  public WireVersion {
    if (value < 0 || value > 0xffff_ffffL) {
      throw new IllegalArgumentException("Wire version must fit unsigned 32 bits");
    }
  }
}
