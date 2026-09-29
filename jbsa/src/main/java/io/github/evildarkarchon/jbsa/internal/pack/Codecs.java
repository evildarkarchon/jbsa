package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

/**
 * The pipeline's private codec map. It is the only place that invokes a payload encoder and knows
 * its worst-case output bound and coordinator heap/native working set. Only {@link Codec#STORED} is
 * routed here so far; zlib and the LZ4 codecs join when versioned BSA (#71) and BA2 (#72) migrate,
 * which is also when one conservative zlib worst-case bound replaces the per-family bounds (D3).
 */
final class Codecs {
  private Codecs() {}

  /**
   * One encoding's admission costs.
   *
   * @param bound the worst-case encoded size
   * @param heap coordinator heap the encoder allocates beyond the transfer window
   * @param nativeBytes native memory the encoder allocates
   */
  record Cost(long bound, long heap, long nativeBytes) {}

  /** Returns the admission costs of encoding {@code decodedSize} bytes with {@code codec}. */
  static Cost cost(Codec codec, long decodedSize) {
    return switch (codec) {
      case STORED -> new Cost(decodedSize, 0, 0);
      case ZLIB, BSA_LZ4_FRAME, LZ4_RAW -> throw unrouted(codec);
    };
  }

  /**
   * Encodes one declared source into positional sink writes relative to the payload start.
   *
   * @throws IOException a structured source-length, stall, or sink failure
   */
  static void encode(
      Codec codec,
      ReadableByteChannel input,
      long decodedSize,
      PayloadWindows.Sink sink,
      PayloadWindows.Checkpoint checkpoint,
      IoContext processing)
      throws IOException {
    switch (codec) {
      case STORED -> PayloadWindows.transfer(input, decodedSize, sink, checkpoint, processing);
      case ZLIB, BSA_LZ4_FRAME, LZ4_RAW -> throw unrouted(codec);
    }
  }

  /** A programming error: an adapter selected a codec before its family migrated. */
  private static UnsupportedOperationException unrouted(Codec codec) {
    return new UnsupportedOperationException(
        codec + " is not routed through the Pack Pipeline yet");
  }
}
