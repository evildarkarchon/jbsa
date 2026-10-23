package io.github.evildarkarchon.jbsa.internal.codec;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Borrows a positional bounded write destination for encoded or copied bytes, shared by the codecs
 * and the pack pipeline's payload windows.
 */
@FunctionalInterface
public interface ByteSink {
  /** Consumes the window's remaining bytes at a position relative to the payload start. */
  void write(long offset, ByteBuffer bytes) throws IOException;
}
