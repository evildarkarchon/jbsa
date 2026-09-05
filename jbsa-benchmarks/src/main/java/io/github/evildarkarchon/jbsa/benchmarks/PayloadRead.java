package io.github.evildarkarchon.jbsa.benchmarks;

import java.io.IOException;
import java.io.InputStream;

/** Validates completion at the public payload stream boundary. */
public final class PayloadRead {
  private PayloadRead() {}

  /**
   * Consumes normal EOF and returns the validated logical byte count. The caller owns the stream
   * and scratch buffer; premature EOF, extra bytes, and read failures never count as completed
   * reads.
   */
  public static long consume(InputStream stream, long expectedBytes, byte[] buffer)
      throws IOException {
    if (expectedBytes < 0 || buffer.length == 0) {
      throw new IllegalArgumentException("A nonnegative length and nonempty buffer are required");
    }
    long count = 0;
    int read;
    while ((read = stream.read(buffer)) != -1) {
      if (read == 0) {
        throw new IOException("Payload stream made no progress");
      }
      count = Math.addExact(count, read);
      if (count > expectedBytes) {
        throw new IOException("Payload exceeds manifest length");
      }
    }
    if (count != expectedBytes) {
      throw new IOException("Payload ended before manifest length");
    }
    return count;
  }
}
