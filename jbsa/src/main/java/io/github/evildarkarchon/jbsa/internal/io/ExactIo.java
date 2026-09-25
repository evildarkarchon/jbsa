package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;

/** Checked long spans and finite-progress positional transfers; never changes channel position. */
public final class ExactIo {
  static final int WINDOW_BYTES = 64 * 1024;
  private static final int MAX_ZERO_PROGRESS = 16;

  private ExactIo() {}

  /**
   * Returns a checked nonnegative end within the input extent, including empty end-of-file spans.
   */
  public static long end(long offset, long length, long extent, IoContext context)
      throws ArchiveException {
    try {
      long end = Math.addExact(offset, length);
      if (offset < 0 || length < 0 || end > extent) {
        throw context.failure(FailureKind.FORMAT, "io.invalid-span", null);
      }
      return end;
    } catch (ArithmeticException cause) {
      throw context.failure(FailureKind.FORMAT, "io.span-overflow", cause);
    }
  }

  /** Multiplies encoded counts before narrowing or allocating any table storage. */
  public static long multiply(long count, long width, IoContext context) throws ArchiveException {
    try {
      if (count < 0 || width < 0) {
        throw context.failure(FailureKind.FORMAT, "io.invalid-span", null);
      }
      return Math.multiplyExact(count, width);
    } catch (ArithmeticException cause) {
      throw context.failure(FailureKind.FORMAT, "io.span-overflow", cause);
    }
  }

  /** Fills the caller's remaining buffer through bounded windows or reports FORMAT/SOURCE. */
  public static void read(
      FileChannel channel, long extent, long offset, ByteBuffer destination, IoContext context)
      throws IOException {
    end(offset, destination.remaining(), extent, context);
    transfer(channel, offset, destination, false, context);
  }

  /** Writes every remaining byte positionally, including backpatches, or reports DESTINATION. */
  public static void write(FileChannel channel, long offset, ByteBuffer source, IoContext context)
      throws IOException {
    try {
      end(offset, source.remaining(), Long.MAX_VALUE, context);
    } catch (ArchiveException cause) {
      throw context.failure(FailureKind.DESTINATION, "io.invalid-output-span", cause);
    }
    transfer(channel, offset, source, true, context);
  }

  /** Caps each native transfer and bounds zero progress without spinning indefinitely. */
  private static void transfer(
      FileChannel channel, long offset, ByteBuffer buffer, boolean write, IoContext context)
      throws IOException {
    int zeros = 0;
    while (buffer.hasRemaining()) {
      ByteBuffer window = buffer.slice();
      window.limit(Math.min(window.remaining(), WINDOW_BYTES));
      int count;
      try {
        count = write ? channel.write(window, offset) : channel.read(window, offset);
      } catch (ClosedChannelException cause) {
        // Child callers need the original channel-lifetime exception, not an operation diagnostic.
        if (write) {
          throw context.failure(FailureKind.DESTINATION, "operation.destination-io", cause);
        }
        throw cause;
      } catch (IOException cause) {
        throw context.failure(
            write ? FailureKind.DESTINATION : FailureKind.SOURCE,
            write ? "operation.destination-io" : "operation.source-io",
            cause);
      }
      if (count < 0) {
        throw context.failure(
            write ? FailureKind.DESTINATION : FailureKind.FORMAT,
            write ? "io.short-write" : "io.unexpected-eof",
            null);
      }
      if (count == 0) {
        if (++zeros == MAX_ZERO_PROGRESS) {
          throw context.failure(
              write ? FailureKind.DESTINATION : FailureKind.SOURCE, "io.no-progress", null);
        }
      } else {
        zeros = 0;
        offset = Math.addExact(offset, count);
        buffer.position(buffer.position() + count);
      }
    }
  }
}
