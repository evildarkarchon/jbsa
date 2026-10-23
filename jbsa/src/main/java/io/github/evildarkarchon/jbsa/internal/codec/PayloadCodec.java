package io.github.evildarkarchon.jbsa.internal.codec;

/**
 * The encoding of one entry payload, shared by pack and read. On pack, an adapter selects one per
 * planned entry and the pipeline's private codec map owns each encoder invocation and its
 * worst-case bound and heap/native costs; adapters only name one. On read, it is the complete
 * internal codec identity of one ordinary payload span.
 */
public enum PayloadCodec {
  /** The payload is copied unchanged. */
  STORED,
  /** A zlib stream (versioned BSA 0x67/0x68 and BA2). */
  ZLIB,
  /** The versioned BSA 0x69 LZ4 frame. */
  BSA_LZ4_FRAME,
  /** Starfield raw LZ4 blocks. */
  LZ4_RAW
}
