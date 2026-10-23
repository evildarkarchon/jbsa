package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.Operation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * A no-handle loose-source plan, consumed under a revalidated deny-write/delete lifetime.
 *
 * <p>The Windows NIO provider's absent file key is supplied by the native no-follow identity
 * adapter; other default-filesystem hosts use the portable {@link PathIdentity} identity, not the
 * bare reusable file key. Providers without stable identity fail closed; timestamps never
 * substitute for identity.
 */
public final class SourceFile {
  private final Path path;
  private final Object fileKey;
  private final long size;
  private final FileTime lastModified;
  private final IoContext context;
  private final AttributeReader attributeReader;

  /** Retains only detached source identity and expected metadata, never a backing handle. */
  private SourceFile(
      Path path,
      BasicFileAttributes attributes,
      IoContext context,
      AttributeReader attributeReader) {
    this.path = path;
    fileKey = attributes.fileKey();
    size = attributes.size();
    lastModified = attributes.lastModifiedTime();
    this.context = context;
    this.attributeReader = attributeReader;
  }

  /**
   * Plans a regular loose input without following its final path component.
   *
   * @throws ArchiveException with SOURCE for invalid input or CAPABILITY for unavailable identity
   * @throws IOException only a stop signal, which the structuring entry hands back unchanged
   */
  public static SourceFile plan(Path path) throws IOException {
    return plan(path, SourceFile::nativeAttributes);
  }

  /**
   * Binds NIO length/time metadata to the same {@link PathIdentity} no-follow identity before and
   * after it. Off Windows this is the portable identity, whose birth time tells a recreated file
   * apart from the original even when POSIX hands it the same inode, length, and timestamp.
   */
  private static BasicFileAttributes nativeAttributes(Path path) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (path.getFileSystem() != java.nio.file.FileSystems.getDefault()) return attributes;
    PathIdentity.Snapshot before = PathIdentity.inspect(path);
    attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    PathIdentity.Snapshot after = PathIdentity.inspect(path);
    if (before == null || !before.equals(after))
      throw IoContext.source(path, Operation.PACK).roleFailure("source.changed", null);
    return new NativeAttributes(attributes, before);
  }

  /** Keeps native identity and reparse classification with the corresponding NIO timestamps. */
  private record NativeAttributes(BasicFileAttributes attributes, PathIdentity.Snapshot nativeInfo)
      implements BasicFileAttributes {
    @Override
    public FileTime lastModifiedTime() {
      return attributes.lastModifiedTime();
    }

    @Override
    public FileTime lastAccessTime() {
      return attributes.lastAccessTime();
    }

    @Override
    public FileTime creationTime() {
      return attributes.creationTime();
    }

    @Override
    public boolean isRegularFile() {
      return nativeInfo.regular();
    }

    @Override
    public boolean isDirectory() {
      return nativeInfo.directory();
    }

    @Override
    public boolean isSymbolicLink() {
      return nativeInfo.indirection();
    }

    @Override
    public boolean isOther() {
      return !isRegularFile() && !isDirectory() && !isSymbolicLink();
    }

    @Override
    public long size() {
      return attributes.size();
    }

    @Override
    public Object fileKey() {
      return nativeInfo.identity();
    }
  }

  /** Supplies an internal no-follow identity provider for deterministic validation fault tests. */
  static SourceFile plan(Path path, AttributeReader attributeReader) throws IOException {
    Objects.requireNonNull(attributeReader, "attributeReader");
    Path absolute = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    IoContext context = IoContext.source(absolute, Operation.PACK);
    return new SourceFile(
        absolute, attributes(absolute, context, attributeReader), context, attributeReader);
  }

  /** Returns the expected byte length recorded during planning. */
  public long size() {
    return size;
  }

  /**
   * Revalidates the plan, consumes it synchronously, and verifies consistency before closing. The
   * reader must not retain or close the borrowed input. The denial lasts through final checking.
   *
   * @throws ArchiveException on changed input, source I/O, or owned-handle cleanup failure; raw
   *     consumption I/O failures remain the original cause of a structured SOURCE failure
   */
  public <T> T consume(Reader<T> reader) throws IOException {
    Objects.requireNonNull(reader, "reader");
    revalidate();
    try (ArchiveInput input = ArchiveInput.openSource(path)) {
      // Rechecking under the denial closes the metadata-check/open race without following links.
      revalidate();
      if (input.size() != size) {
        throw context.roleFailure("source.changed", null);
      }
      T result = reader.read(input);
      revalidate();
      return result;
    } catch (ArchiveException cause) {
      String identifier = cause.primaryFailure().diagnosticIdentifier().orElse("");
      // Shared exact I/O reports malformed archive spans as FORMAT. For a loose input, the same
      // evidence means the source failed its expected-length contract, not archive decoding.
      if (cause.kind() == FailureKind.FORMAT
          && (identifier.equals("io.invalid-span")
              || identifier.equals("io.span-overflow")
              || identifier.equals("io.unexpected-eof"))) {
        throw context.roleFailure("source.length-mismatch", cause);
      }
      throw cause;
    } catch (IOException cause) {
      // PACK owns this read, so interruption is an operational source failure, never cancellation.
      // A reader's session checkpoint stop comes back unchanged from the entry.
      throw context.structure(cause);
    }
  }

  /** Rejects replacement, size changes, or modified content before admitting the next phase. */
  private void revalidate() throws IOException {
    BasicFileAttributes current = attributes(path, context, attributeReader);
    if (!fileKey.equals(current.fileKey())
        || size != current.size()
        || !lastModified.equals(current.lastModifiedTime())) {
      throw context.roleFailure("source.changed", null);
    }
  }

  /**
   * Obtains no-follow classification and a stable identity, preserving provider detail as cause.
   */
  private static BasicFileAttributes attributes(
      Path path, IoContext context, AttributeReader attributeReader) throws IOException {
    BasicFileAttributes attributes;
    try {
      attributes = attributeReader.read(path);
    } catch (UnsupportedOperationException cause) {
      throw context.failure(FailureKind.CAPABILITY, "source.identity-unavailable", cause);
    } catch (SecurityException cause) {
      throw context.roleFailure("source.attributes", cause);
    } catch (IOException cause) {
      // A structured identity check, such as a source changed while it was read, keeps its own.
      throw context.structure(cause, "source.attributes");
    }
    if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
      throw context.roleFailure("source.not-regular", null);
    }
    if (attributes.fileKey() == null) {
      throw context.failure(FailureKind.CAPABILITY, "source.identity-unavailable", null);
    }
    return attributes;
  }

  /** A synchronous bounded consumption step borrowing one source handle. */
  @FunctionalInterface
  public interface Reader<T> {
    /** Returns the consumption result without retaining the input beyond this invocation. */
    T read(ArchiveInput input) throws IOException;
  }

  /** A non-exported no-follow attribute boundary for qualified providers and fault tests. */
  @FunctionalInterface
  interface AttributeReader {
    /** Returns detached identity and metadata without following the final path component. */
    BasicFileAttributes read(Path path) throws IOException;
  }
}
