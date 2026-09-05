package io.github.evildarkarchon.jbsa.internal.io;

import com.sun.nio.file.ExtendedOpenOption;
import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * One owned positional file handle with Windows deny-write/delete sharing for its entire lifetime.
 */
public final class ArchiveInput implements AutoCloseable {
  private final FileChannel channel;
  private final long size;
  private final IoContext context;

  /**
   * Takes ownership of a channel; construction failure also closes it. Internal fault-test seam.
   */
  ArchiveInput(FileChannel channel, IoContext context) throws IOException {
    this.channel = channel;
    this.context = context;
    try {
      size = channel.size();
    } catch (IOException cause) {
      try {
        channel.close();
      } catch (IOException cleanup) {
        cause.addSuppressed(cleanup);
      }
      throw context.failure(FailureKind.SOURCE, "operation.source-io", cause);
    }
  }

  /** Opens an archive on the qualified provider; unsupported sharing never falls back silently. */
  public static ArchiveInput open(Path path, Operation operation) throws ArchiveException {
    return open(path, operation, false);
  }

  /**
   * Opens a revalidated pack source without following a last-moment final-component indirection.
   */
  public static ArchiveInput openSource(Path path) throws ArchiveException {
    return open(path, Operation.PACK, true);
  }

  /**
   * Applies extended sharing only on the baseline provider, where their semantics are qualified.
   */
  private static ArchiveInput open(Path path, Operation operation, boolean noFollow)
      throws ArchiveException {
    IoContext context = IoContext.of(path, operation);
    if (path.getFileSystem() != FileSystems.getDefault()
        || !System.getProperty("os.name", "").startsWith("Windows")) {
      throw context.failure(FailureKind.CAPABILITY, "io.input-sharing-unavailable", null);
    }
    Set<OpenOption> options =
        new HashSet<>(
            Set.of(
                StandardOpenOption.READ,
                ExtendedOpenOption.NOSHARE_WRITE,
                ExtendedOpenOption.NOSHARE_DELETE));
    if (noFollow) options.add(LinkOption.NOFOLLOW_LINKS);
    try {
      return new ArchiveInput(FileChannel.open(path, options), context);
    } catch (UnsupportedOperationException cause) {
      throw context.failure(FailureKind.CAPABILITY, "io.input-sharing-unavailable", cause);
    } catch (ArchiveException cause) {
      throw cause;
    } catch (IOException | SecurityException cause) {
      throw context.failure(FailureKind.SOURCE, "operation.source-io", cause);
    }
  }

  /** Returns the extent captured under sharing denial, without narrowing large input lengths. */
  public long size() {
    return size;
  }

  /** Reads a checked input span exactly; channel lifetime exceptions remain unwrapped. */
  public void readExact(long offset, ByteBuffer destination) throws IOException {
    ExactIo.read(channel, size, offset, destination, context);
  }

  /** Observes whether a direct interruption or explicit close invalidated the shared channel. */
  boolean isOpen() {
    return channel.isOpen();
  }

  /** Closes the sole handle, releasing its sharing denials; FileChannel makes this idempotent. */
  @Override
  public void close() throws IOException {
    try {
      channel.close();
    } catch (IOException cause) {
      throw new IoContext(
              context.path(), context.operation(), OperationPhase.CLEANUP, context.ordinal())
          .failure(FailureKind.SOURCE, "operation.source-io", cause);
    }
  }
}
