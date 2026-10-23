package io.github.evildarkarchon.jbsa.internal.codec;

/**
 * The encoding of one entry payload, shared by pack and read. On pack, an adapter selects one per
 * planned entry and the pipeline's private codec map owns each encoder invocation; adapters only
 * name one. On read, it is the complete internal codec identity of one ordinary payload span. In
 * both directions its worst-case bound and heap/native costs belong to its {@link AdmittedCodec}.
 */
public enum PayloadCodec {
  /** The payload is copied unchanged. */
  STORED,
  /** A zlib stream (versioned BSA 0x67/0x68 and BA2). */
  ZLIB,
  /** The versioned BSA 0x69 LZ4 frame. */
  BSA_LZ4_FRAME,
  /** Starfield raw LZ4 blocks. */
  LZ4_RAW;

  /**
   * Returns whether a payload under this codec decodes only as one whole block, through {@link
   * AdmittedCodec#decode}, rather than streaming through {@link AdmittedCodec#decoder}. Only raw
   * LZ4 does. Its reader holds the decoded block as its own copy and charges that copy itself, on
   * top of the admitted codec's cost. It needs no admission, so a reader can decide how to charge a
   * payload before it admits the codec.
   */
  public boolean decodesWholeBlocks() {
    return this == LZ4_RAW;
  }
}
