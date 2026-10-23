package io.github.evildarkarchon.jbsa.internal.codec;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * One payload's sequential decoder, created by {@link AdmittedCodec#decoder} for every codec that
 * streams: stored, zlib and the versioned BSA LZ4 frame. Its owner reserves the admitted codec's
 * {@link AdmittedCodec#decodeCost} before creating it, lends it that lease, and closes it exactly
 * once; raw LZ4 has no streaming decoder, because each block decodes whole.
 */
public interface PayloadDecoder extends AutoCloseable {
  /**
   * Fills at most one bounded window of {@code destination} and advances its position.
   *
   * @return the number of bytes decoded, zero when {@code destination} has no room, or -1 once the
   *     payload's declared size has been served and validated
   * @throws IOException a structured codec, source, or closed-channel failure
   */
  int read(ByteBuffer destination) throws IOException;

  /** Releases the decoder's state; closing is idempotent. */
  @Override
  void close();
}
