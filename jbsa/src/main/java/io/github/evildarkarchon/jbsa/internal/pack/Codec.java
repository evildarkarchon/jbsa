package io.github.evildarkarchon.jbsa.internal.pack;

/**
 * A payload encoding an adapter selects per planned entry. The pipeline's private codec map owns
 * each encoder invocation and its worst-case bound and heap/native costs; adapters only name one.
 */
public enum Codec {
  /** The payload is copied unchanged. */
  STORED,
  /** A zlib stream (versioned BSA 0x67/0x68 and BA2). */
  ZLIB,
  /** The versioned BSA 0x69 LZ4 frame. */
  BSA_LZ4_FRAME,
  /** Starfield raw LZ4 blocks. */
  LZ4_RAW
}
