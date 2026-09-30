package io.github.evildarkarchon.jbsa.internal.io;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * No-follow file identity and destination pins, selected by host platform.
 *
 * <p>On the qualified Windows baseline every observation comes from {@link WindowsPathIdentity},
 * whose pins deny deletion and rename until closed. Elsewhere the portable provider reads NIO
 * no-follow attributes and their provider file key in one call. It cannot deny namespace changes,
 * so its pins only record the observed identity; replacement is detected by the callers' existing
 * revalidation instead of being prevented. A provider without a stable file key fails closed with
 * {@link UnsupportedOperationException}, and timestamps or paths never substitute for identity; a
 * regular file's birth time only disambiguates a reused key.
 */
public final class PathIdentity {
  private PathIdentity() {}

  /**
   * Reads the named entry itself, returning null only when it or a parent does not exist.
   *
   * @throws IOException if the entry cannot be opened or inspected
   * @throws UnsupportedOperationException if the provider cannot establish a stable identity
   */
  public static Snapshot inspect(Path path) throws IOException {
    return windows() ? WindowsPathIdentity.inspect(path) : portableInspect(path);
  }

  /**
   * Pins the entry without following its final component. On Windows the pin denies deletion and
   * rename until closed; the portable pin only records the identity observed now. Callers must
   * inspect the snapshot before accepting the entry as a directory.
   *
   * @throws IOException if the entry is absent or cannot be inspected
   * @throws UnsupportedOperationException if the provider cannot establish a stable identity
   */
  public static Pin pin(Path path) throws IOException {
    if (windows()) return WindowsPathIdentity.pin(path);
    Snapshot snapshot = portableInspect(path);
    if (snapshot == null) {
      throw new IOException(
          "The entry to pin does not exist", new NoSuchFileException(path.toString()));
    }
    return new ObservedPin(snapshot);
  }

  /** A detached entry identity and its no-follow type; no operating-system handle is retained. */
  public record Snapshot(
      Object identity, boolean directory, boolean regular, boolean indirection) {}

  /** An owned destination pin. Closing is idempotent. */
  public interface Pin extends AutoCloseable {
    /** Returns the detached no-follow attributes captured when the pin was taken. */
    Snapshot snapshot();

    /** Releases whatever the pin holds; a failed close is reported and may be retried. */
    @Override
    void close() throws IOException;
  }

  /**
   * Returns whether the Windows native provider owns identity. It is chosen by host alone and never
   * falls back: the Windows NIO provider has no file key, so a portable read would only fail later.
   */
  private static boolean windows() {
    return System.getProperty("os.name", "").startsWith("Windows");
  }

  /**
   * Reads type and file key from one no-follow attribute snapshot, so both describe the same entry.
   */
  private static Snapshot portableInspect(Path path) throws IOException {
    // Only the default provider's file keys are known to be stable, as on Windows.
    if (path.getFileSystem() != FileSystems.getDefault()) {
      throw new UnsupportedOperationException("Stable file identity is unavailable");
    }
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException absent) {
      // A missing entry or missing parent is an observation, as it is on Windows.
      return null;
    }
    Object key = attributes.fileKey();
    if (key == null) {
      throw new UnsupportedOperationException("Stable file identity is unavailable");
    }
    // POSIX reuses a deleted file's inode at once, so delete-and-recreate can reproduce the key;
    // the birth time tells the two files apart. Directories keep the bare key: a provider without
    // birth time reports the modification time, which changes whenever a directory's entries do.
    Object identity =
        attributes.isRegularFile() ? new FileIdentity(key, attributes.creationTime()) : key;
    return new Snapshot(
        identity,
        attributes.isDirectory(),
        attributes.isRegularFile(),
        attributes.isSymbolicLink());
  }

  /** A portable regular-file identity: the provider file key plus its birth time. */
  private record FileIdentity(Object key, FileTime created) {}

  /**
   * A portable pin: holds no handle, so it cannot deny replacement and closing releases nothing.
   */
  private record ObservedPin(Snapshot snapshot) implements Pin {
    @Override
    public void close() {
      // Nothing is held; revalidation against the snapshot is the portable protection.
    }
  }
}
