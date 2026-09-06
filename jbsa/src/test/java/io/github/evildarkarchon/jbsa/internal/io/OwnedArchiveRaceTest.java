package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Barrier-controlled lifetime evidence, without sleeps or format-parser claims. */
final class OwnedArchiveRaceTest {
  /** A failed eager open retains owned-handle cleanup as structured secondary evidence. */
  @Test
  void indexFailureRetainsStructuredCleanup() throws Exception {
    ControlledChannel channel = new ControlledChannel();
    channel.failClose = true;
    IoContext context = IoContext.of(Path.of("synthetic.bin"), Operation.OPEN);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                OwnedArchive.load(
                    new ArchiveInput(channel, context),
                    ResourceLimits.standard(),
                    context,
                    builder -> {
                      throw context.failure(FailureKind.FORMAT, "test.invalid-index", null);
                    }));
    assertEquals(FailureKind.FORMAT, failure.kind());
    assertEquals(0, failure.getSuppressed().length);
    assertEquals(1, failure.secondaryFailures().size());
    assertEquals(OperationPhase.CLEANUP, failure.secondaryFailures().getFirst().phase());
    assertEquals(FailureKind.SOURCE, failure.secondaryFailures().getFirst().kind());
    assertTrue(failure.secondaryFailures().getFirst().cause().isPresent());
    assertEquals(1, channel.closes);
  }

  /** Cleanup failure cannot replace the required direct-interruption channel exception. */
  @Test
  void interruptionKeepsItsIdentityWhenSharedCloseFails() throws Exception {
    ControlledChannel channel = new ControlledChannel();
    channel.failClose = true;
    try (OpenArchive parent = open(channel)) {
      EntryContent child = parent.entry(0).openContent();
      try {
        Thread.currentThread().interrupt();
        ClosedByInterruptException failure =
            assertThrows(
                ClosedByInterruptException.class, () -> child.read(ByteBuffer.allocate(1)));
        assertEquals(1, failure.getSuppressed().length);
        assertFalse(child.isOpen());
      } finally {
        // This test simulates caller interruption; do not carry it into JUnit cleanup.
        Thread.interrupted();
      }
    }
  }

  /** Close wins over successful, short-EOF and failed provider completions of an admitted read. */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
  void parentCloseWinsAnInFlightReadAndClosesInputExactlyOnce(int completion) throws Exception {
    ControlledChannel channel = new ControlledChannel();
    channel.block = true;
    channel.completion = completion;
    try (OpenArchive parent = open(channel)) {
      EntryContent child = parent.entry(0).openContent();
      assertEquals(0, channel.reads, "index and child creation must be payload-lazy");
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread reader =
          new Thread(
              () -> {
                try {
                  child.read(ByteBuffer.allocate(1));
                } catch (Throwable cause) {
                  failure.set(cause);
                }
              });
      reader.start();
      try {
        assertTrue(channel.entered.await(5, TimeUnit.SECONDS));
        parent.close();
        parent.close();
      } finally {
        channel.release.countDown();
      }
      reader.join(5000);
      assertFalse(reader.isAlive());
      assertInstanceOf(AsynchronousCloseException.class, failure.get());
      assertThrows(ClosedChannelException.class, () -> child.read(ByteBuffer.allocate(1)));
      assertEquals(1, channel.closes);
      assertFalse(child.isOpen());
    }
  }

  /**
   * A late short payload closes only the affected child and cannot revoke earlier returned bytes.
   */
  @Test
  void lateEofDoesNotInvalidateParentOrSibling() throws Exception {
    ControlledChannel channel = new ControlledChannel();
    channel.truncate = true;
    try (OpenArchive parent = open(channel)) {
      EntryContent child = parent.entry(0).openContent();
      ByteBuffer first = ByteBuffer.allocate(1);
      assertEquals(1, child.read(first));
      assertEquals(41, first.get(0));
      ArchiveException failure =
          assertThrows(ArchiveException.class, () -> child.read(ByteBuffer.allocate(1)));
      assertEquals(FailureKind.FORMAT, failure.kind());
      assertEquals(Operation.READ_CONTENT, failure.diagnostics().getFirst().operation());
      ArchiveAssessment latest = failure.assessment().orElseThrow();
      assertEquals(ArchiveDisposition.REJECTED, latest.disposition());
      assertEquals(new ValidationExtent.Payloads(Set.of(0L)), latest.extent());
      assertEquals(ArchiveDisposition.CONFORMING, parent.inspection().assessment().disposition());
      assertEquals(new ValidationExtent.Structure(), parent.inspection().assessment().extent());
      assertFalse(child.isOpen());
      assertTrue(child.assessment().isEmpty());
      try (EntryContent sibling = parent.entry(0).openContent()) {
        assertEquals(1, sibling.read(ByteBuffer.allocate(1)));
      }
      assertEquals(0, channel.closes);
    }
  }

  /** Concurrent admission either fails closed or returns a child invalidated by completed close. */
  @Test
  void openingAndClosingShareOneLinearizationPoint() throws Exception {
    for (int iteration = 0; iteration < 50; iteration++) {
      ControlledChannel channel = new ControlledChannel();
      try (OpenArchive parent = open(channel)) {
        ArchiveEntry entry = parent.entry(0);
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicReference<EntryContent> child = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread opener =
            new Thread(
                () -> {
                  try {
                    start.await(5, TimeUnit.SECONDS);
                    child.set(entry.openContent());
                  } catch (Throwable cause) {
                    failure.set(cause);
                  }
                });
        opener.start();
        start.await(5, TimeUnit.SECONDS);
        parent.close();
        opener.join(5000);
        assertFalse(opener.isAlive());
        if (failure.get() != null) assertInstanceOf(ClosedChannelException.class, failure.get());
        else assertFalse(child.get().isOpen());
        assertEquals(1, channel.closes);
      }
    }
  }

  /** Supplies a structural index directly to the unexported constructor seam. */
  private static OpenArchive open(ControlledChannel channel) throws Exception {
    IoContext context = IoContext.of(Path.of("synthetic.bin"), Operation.OPEN);
    ArchiveDetection detection =
        new ArchiveDetection(
            DetectionStatus.SUPPORTED_FAMILY,
            new WireName(new byte[] {0, 1, 0, 0}),
            Optional.of(ArchiveFamily.TES3_BSA),
            Optional.empty(),
            Optional.empty(),
            OptionalLong.empty());
    return OwnedArchive.load(
        new ArchiveInput(channel, context),
        ResourceLimits.standard(),
        context,
        builder -> {
          builder.declareEntries(1);
          builder.addStored(
              new EntryMetadata(
                  ArchiveFamily.TES3_BSA,
                  ArchiveEncoding.tes3(),
                  0,
                  "a",
                  Optional.empty(),
                  Map.of(),
                  2,
                  2,
                  new EntryMetadata.Tes3(0, 0, 0, 0)),
              0);
          return new ArchiveInspection(
              detection,
              new ArchiveMetadata.Tes3(1, 0, 0),
              new ArchiveAssessment(
                  ArchiveDisposition.CONFORMING, new ValidationExtent.Structure(), List.of()));
        });
  }

  /**
   * Models a provider completing after close and a late truncation, independently of cursor code.
   */
  private static final class ControlledChannel extends FileChannel {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    boolean block;
    boolean truncate;
    boolean failClose;
    int completion;
    volatile int closes;
    volatile int reads;

    /** Signals read admission and optionally waits for the parent-close barrier. */
    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
      reads++;
      entered.countDown();
      if (block) {
        try {
          if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test barrier timeout");
        } catch (InterruptedException cause) {
          Thread.currentThread().interrupt();
          throw new ClosedByInterruptException();
        }
      }
      if (completion == 1) return -1;
      if (completion == 2) throw new IOException("simulated provider read failure");
      if (position >= (truncate ? 1 : 2)) return -1;
      dst.put((byte) (41 + position));
      return 1;
    }

    @Override
    public long size() {
      return 2;
    }

    /** Releases the simulated blocked read, as a real interruptible handle close would. */
    @Override
    protected void implCloseChannel() throws IOException {
      closes++;
      release.countDown();
      if (failClose) throw new IOException("simulated close failure");
    }

    @Override
    public int read(ByteBuffer dst) {
      throw new AssertionError("relative read");
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) {
      throw new AssertionError("relative read");
    }

    @Override
    public int write(ByteBuffer src) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int write(ByteBuffer src, long position) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long position() {
      throw new AssertionError("shared position");
    }

    @Override
    public FileChannel position(long position) {
      throw new AssertionError("shared position");
    }

    @Override
    public FileChannel truncate(long size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void force(boolean metadata) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) {
      throw new UnsupportedOperationException();
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) {
      throw new UnsupportedOperationException();
    }
  }
}
