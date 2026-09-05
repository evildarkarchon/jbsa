package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real I/O lifetime with an explicitly simulated stable-identity provider. */
final class SourceFileTest {
  @TempDir Path directory;

  /** Planning retains metadata only; consuming an unchanged file reads its expected bytes. */
  @Test
  void consumesUnchangedSourceAndReleasesItsHandle() throws Exception {
    Path source = directory.resolve("input.bin");
    Files.write(source, new byte[] {4, 5, 6});
    SourceFile planned = knownPlan(source);
    assertEquals(3, planned.size());
    byte[] actual =
        planned.consume(
            input -> {
              ByteBuffer bytes = ByteBuffer.allocate(3);
              input.readExact(0, bytes);
              return bytes.array();
            });
    assertArrayEquals(new byte[] {4, 5, 6}, actual);
    Files.delete(source);
    assertFalse(Files.exists(source));
  }

  /** Planning must not pin the source, and changed contents must fail before invoking a reader. */
  @Test
  void rejectsChangedSizeBeforeConsumption() throws Exception {
    Path source = directory.resolve("changed.bin");
    Files.write(source, new byte[] {1});
    SourceFile planned = knownPlan(source);
    Files.write(source, new byte[] {1, 2});
    ArchiveException failure =
        assertThrows(ArchiveException.class, () -> planned.consume(input -> fail("Must not read")));
    assertEquals(FailureKind.SOURCE, failure.kind());
    assertEquals("source.changed", failure.primaryFailure().diagnosticIdentifier().orElseThrow());
  }

  /** A new file at the same path cannot reuse a plan even when size and timestamp match. */
  @Test
  void rejectsReplacementWithMatchingMetadata() throws Exception {
    Path source = directory.resolve("replaced.bin");
    Files.write(source, new byte[] {1});
    FileTime modified = Files.getLastModifiedTime(source);
    AtomicReference<Object> identity = new AtomicReference<>("original");
    SourceFile planned = SourceFile.plan(source, path -> attributes(path, identity.get()));
    Files.move(source, directory.resolve("original.bin"));
    Files.write(source, new byte[] {2});
    Files.setLastModifiedTime(source, modified);
    identity.set("replacement");
    ArchiveException failure =
        assertThrows(ArchiveException.class, () -> planned.consume(input -> fail("Must not read")));
    assertEquals(FailureKind.SOURCE, failure.kind());
  }

  /** A timestamp change is significant even when the source's size remains unchanged. */
  @Test
  void rejectsModifiedMetadataBeforeConsumption() throws Exception {
    Path source = directory.resolve("modified.bin");
    Files.write(source, new byte[] {1});
    SourceFile planned = knownPlan(source);
    Files.setLastModifiedTime(source, FileTime.fromMillis(1_000));
    ArchiveException failure =
        assertThrows(ArchiveException.class, () -> planned.consume(input -> fail("Must not read")));
    assertEquals(FailureKind.SOURCE, failure.kind());
  }

  /** The consumed handle permits compatible readers but denies writers and namespace removal. */
  @Test
  void deniesMutationThroughoutConsumptionAndClosesAfterReaderFailure() throws Exception {
    Path source = directory.resolve("held.bin");
    Files.write(source, new byte[] {1});
    SourceFile planned = knownPlan(source);
    IOException expected = new IOException("reader stopped");
    ArchiveException observed =
        assertThrows(
            ArchiveException.class,
            () ->
                planned.consume(
                    input -> {
                      try (FileChannel reader = FileChannel.open(source, StandardOpenOption.READ)) {
                        assertEquals(1, reader.size());
                      }
                      assertThrows(
                          IOException.class,
                          () -> {
                            try (FileChannel writer =
                                FileChannel.open(source, StandardOpenOption.WRITE)) {
                              fail(
                                  "Write handle must be denied, but opened with size "
                                      + writer.size());
                            }
                          });
                      assertThrows(IOException.class, () -> Files.delete(source));
                      throw expected;
                    }));
    assertEquals(FailureKind.SOURCE, observed.kind());
    assertSame(expected, observed.getCause());
    Files.delete(source);
    assertFalse(Files.exists(source));
  }

  /** A short loose-source stream is a source failure retaining the reader's original EOF cause. */
  @Test
  void classifiesConsumedEofAsSourceFailure() throws Exception {
    Path source = directory.resolve("consumed-eof.bin");
    Files.write(source, new byte[] {1});
    SourceFile planned = knownPlan(source);
    EOFException expected = new EOFException("provider EOF detail");
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                planned.consume(
                    input -> {
                      throw expected;
                    }));
    assertEquals(FailureKind.SOURCE, failure.kind());
    assertSame(expected, failure.getCause());
    assertFalse(failure.getMessage().contains("provider EOF detail"));
    Files.delete(source);
  }

  /**
   * Bounds checking during loose-source consumption cannot misclassify a short source as FORMAT.
   */
  @Test
  void classifiesShortExactSourceReadAsSourceFailure() throws Exception {
    Path source = directory.resolve("short-exact-read.bin");
    Files.write(source, new byte[] {1});
    SourceFile planned = knownPlan(source);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                planned.consume(
                    input -> {
                      input.readExact(0, ByteBuffer.allocate(2));
                      return 1;
                    }));
    assertEquals(FailureKind.SOURCE, failure.kind());
    assertInstanceOf(ArchiveException.class, failure.getCause());
    Files.delete(source);
  }

  /** PACK interruption is a structured SOURCE outcome with the original channel exception. */
  @Test
  void classifiesInterruptedSourceReadWithoutCooperativeCancellation() throws Exception {
    Path source = directory.resolve("interrupted-source.bin");
    Files.write(source, new byte[] {1});
    SourceFile planned = knownPlan(source);
    AtomicReference<ClosedByInterruptException> original = new AtomicReference<>();
    try {
      ArchiveException failure =
          assertThrows(
              ArchiveException.class,
              () ->
                  planned.consume(
                      input -> {
                        Thread.currentThread().interrupt();
                        try {
                          input.readExact(0, ByteBuffer.allocate(1));
                          return fail("Interrupted channel read must fail");
                        } catch (ClosedByInterruptException cause) {
                          original.set(cause);
                          throw cause;
                        }
                      }));
      assertEquals(FailureKind.SOURCE, failure.kind());
      assertSame(original.get(), failure.getCause());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      // Clear this deliberate test interruption before JUnit performs unrelated resource cleanup.
      Thread.interrupted();
    }
    Files.delete(source);
  }

  /** Missing input and directory replacement are source failures, with no consumption attempted. */
  @Test
  void rejectsMissingAndNonRegularSources() throws Exception {
    assertEquals(
        FailureKind.SOURCE,
        assertThrows(ArchiveException.class, () -> SourceFile.plan(directory.resolve("missing")))
            .kind());
    assertEquals(
        FailureKind.SOURCE,
        assertThrows(ArchiveException.class, () -> SourceFile.plan(directory)).kind());
    Path source = directory.resolve("became-directory");
    Files.write(source, new byte[0]);
    SourceFile planned = knownPlan(source);
    Files.delete(source);
    Files.createDirectory(source);
    assertEquals(
        FailureKind.SOURCE,
        assertThrows(ArchiveException.class, () -> planned.consume(input -> fail("Must not read")))
            .kind());
  }

  /** Providers without stable file identity cannot silently weaken pack-source consistency. */
  @Test
  void rejectsProviderWithoutStableIdentity() throws Exception {
    Path archive = directory.resolve("provider.zip");
    try (var filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
      Path source = filesystem.getPath("/input.bin");
      Files.write(source, new byte[] {1});
      ArchiveException failure =
          assertThrows(ArchiveException.class, () -> SourceFile.plan(source));
      assertEquals(FailureKind.CAPABILITY, failure.kind());
      assertEquals(
          "source.identity-unavailable",
          failure.primaryFailure().diagnosticIdentifier().orElseThrow());
    }
  }

  /** The Windows baseline's absent file key is a capability failure, never a weaker identity. */
  @Test
  void defaultWindowsProviderFailsClosedWhenFileIdentityIsUnavailable() throws Exception {
    Path source = directory.resolve("native-identity-required.bin");
    Files.write(source, new byte[] {1});
    assertNull(Files.readAttributes(source, BasicFileAttributes.class).fileKey());
    ArchiveException failure = assertThrows(ArchiveException.class, () -> SourceFile.plan(source));
    assertEquals(FailureKind.CAPABILITY, failure.kind());
    assertEquals(
        "source.identity-unavailable",
        failure.primaryFailure().diagnosticIdentifier().orElseThrow());
  }

  /**
   * Final revalidation rejects identity changes reported after streaming and releases the handle.
   */
  @Test
  void rejectsPostConsumptionIdentityChange() throws Exception {
    Path source = directory.resolve("changed-during-consumption.bin");
    Files.write(source, new byte[] {1});
    AtomicReference<Object> identity = new AtomicReference<>("original");
    SourceFile planned = SourceFile.plan(source, path -> attributes(path, identity.get()));
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                planned.consume(
                    input -> {
                      identity.set("changed");
                      return 1;
                    }));
    assertEquals(FailureKind.SOURCE, failure.kind());
    Files.delete(source);
    assertFalse(Files.exists(source));
  }

  /** A provider that cannot perform no-follow classification must not receive a weaker retry. */
  @Test
  void rejectsUnavailableNoFollowClassification() throws Exception {
    UnsupportedOperationException cause = new UnsupportedOperationException("provider detail");
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                SourceFile.plan(
                    directory.resolve("input"),
                    path -> {
                      throw cause;
                    }));
    assertEquals(FailureKind.CAPABILITY, failure.kind());
    assertSame(cause, failure.getCause());
    assertFalse(failure.getMessage().contains("provider detail"));
  }

  /**
   * A provider-observed late indirection is rejected before the borrowed handle reaches a reader.
   */
  @Test
  void rejectsRevalidatedIndirectionBeforeConsumption() throws Exception {
    Path source = directory.resolve("became-indirection.bin");
    Files.write(source, new byte[] {1});
    AtomicReference<Boolean> indirection = new AtomicReference<>(false);
    SourceFile planned =
        SourceFile.plan(
            source,
            path ->
                new IdentifiedAttributes(
                    Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS),
                    "original",
                    indirection.get()));
    indirection.set(true);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> planned.consume(input -> fail("Must not open an indirection")));
    assertEquals(FailureKind.SOURCE, failure.kind());
    assertEquals(
        "source.not-regular", failure.primaryFailure().diagnosticIdentifier().orElseThrow());
  }

  /**
   * Supplies a known identity only to test internal validation, not to qualify Windows identity.
   */
  private static SourceFile knownPlan(Path source) throws ArchiveException {
    return SourceFile.plan(source, path -> attributes(path, "known-file"));
  }

  /** Adds deterministic identity to real no-follow attributes for the fault-injection seam. */
  private static BasicFileAttributes attributes(Path path, Object identity) throws IOException {
    return new IdentifiedAttributes(
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS),
        identity,
        false);
  }

  /** Delegates real classification and metadata while simulating a provider-supplied file key. */
  private record IdentifiedAttributes(
      BasicFileAttributes delegate, Object fileKey, boolean indirection)
      implements BasicFileAttributes {
    /** Returns the real modification timestamp. */
    @Override
    public FileTime lastModifiedTime() {
      return delegate.lastModifiedTime();
    }

    /** Returns the real access timestamp. */
    @Override
    public FileTime lastAccessTime() {
      return delegate.lastAccessTime();
    }

    /** Returns the real creation timestamp. */
    @Override
    public FileTime creationTime() {
      return delegate.creationTime();
    }

    /** Returns real regular-file classification. */
    @Override
    public boolean isRegularFile() {
      return !indirection && delegate.isRegularFile();
    }

    /** Returns real directory classification. */
    @Override
    public boolean isDirectory() {
      return delegate.isDirectory();
    }

    /** Returns real no-follow symbolic-link classification. */
    @Override
    public boolean isSymbolicLink() {
      return indirection || delegate.isSymbolicLink();
    }

    /** Returns real provider-specific file classification. */
    @Override
    public boolean isOther() {
      return delegate.isOther();
    }

    /** Returns the real file size. */
    @Override
    public long size() {
      return delegate.size();
    }
  }
}
