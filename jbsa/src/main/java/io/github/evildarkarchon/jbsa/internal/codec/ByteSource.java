package io.github.evildarkarchon.jbsa.internal.codec;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Borrows an exact bounded positional source without owning its lifetime. Codec decoders and
 * encoders read payload bytes through it at stream-relative positions; the pack pipeline's payload
 * windows read absolute ranges of spill and staged files through it ({@code SpillBuffer} and {@code
 * StagedFile} both fit this shape).
 */
@FunctionalInterface
public interface ByteSource {
  /** Fills the window's remaining bytes from {@code offset}. */
  void read(long offset, ByteBuffer bytes) throws IOException;
}
