package io.github.evildarkarchon.jbsa.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class PayloadReadTest {
  /** Verifies completion counts after normal EOF, including a valid empty payload. */
  @Test
  void countsOnlyReadsThatReachEofAtTheManifestLength() throws IOException {
    assertEquals(
        3L, PayloadRead.consume(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, new byte[2]));
    assertEquals(0L, PayloadRead.consume(new ByteArrayInputStream(new byte[0]), 0, new byte[2]));
  }

  /** Rejects payloads whose actual byte count differs from the external manifest. */
  @Test
  void rejectsTruncatedAndOverlongPayloads() {
    assertThrows(
        IOException.class,
        () -> PayloadRead.consume(new ByteArrayInputStream(new byte[2]), 3, new byte[2]));
    assertThrows(
        IOException.class,
        () -> PayloadRead.consume(new ByteArrayInputStream(new byte[4]), 3, new byte[2]));
  }

  /** Ensures reaching the declared count alone cannot conceal a subsequent stream failure. */
  @Test
  void propagatesAnErrorAfterTheDeclaredBytesInsteadOfCountingCompletion() {
    var stream =
        new ByteArrayInputStream(new byte[3]) {
          /** Simulates a failure on the read that must establish normal EOF. */
          @Override
          public synchronized int read(byte[] bytes, int offset, int length) {
            if (available() == 0) {
              throw new IllegalStateException("failure before normal EOF");
            }
            return super.read(bytes, offset, length);
          }
        };
    assertThrows(IllegalStateException.class, () -> PayloadRead.consume(stream, 3, new byte[2]));
  }
}
