package io.github.evildarkarchon.jbsa;

import java.util.Arrays;
import java.util.Objects;

/** Immutable original wire bytes for a name component or other bounded selector metadata. */
public final class WireName {
  private final byte[] bytes;

  /** Copies the supplied metadata bytes; the input array never becomes owned by this value. */
  public WireName(byte[] bytes) {
    this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
  }

  /** Returns an independent copy of the original metadata bytes, never payload content. */
  public byte[] bytes() {
    return bytes.clone();
  }

  /** Returns the number of retained metadata bytes. */
  public long length() {
    return bytes.length;
  }

  /** Compares retained bytes by value. */
  @Override
  public boolean equals(Object other) {
    return other instanceof WireName that && Arrays.equals(bytes, that.bytes);
  }

  /** Returns a byte-value hash independent of array identity. */
  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  /** Returns a lossless hexadecimal representation of the metadata. */
  @Override
  public String toString() {
    return java.util.HexFormat.of().formatHex(bytes);
  }
}
