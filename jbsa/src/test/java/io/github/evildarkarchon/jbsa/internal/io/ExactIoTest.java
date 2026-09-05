package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.Operation;
import io.github.evildarkarchon.jbsa.OperationPhase;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Fault-injected qualification of the agreed exact-I/O boundary without archive-format fixtures.
 */
final class ExactIoTest {
  private static final IoContext CONTEXT =
      new IoContext(
          Path.of("archive.bin"), Operation.OPEN, OperationPhase.PREFLIGHT, OptionalLong.of(7));

  /** Positive short reads and intermittent zero progress fill only the caller's remaining slice. */
  @Test
  void fillsBufferAcrossShortReadsAndTemporaryZeroProgress() throws Exception {
    try (ControlledChannel channel =
        new ControlledChannel(new byte[] {9, 10, 11, 12, 13, 14}, 0, 2, 0, 1, 2)) {
      ByteBuffer destination = ByteBuffer.wrap(new byte[] {99, 99, 99, 99, 99, 99, 99});
      destination.position(1).limit(6);
      ExactIo.read(channel, 6, 1, destination, CONTEXT);
      assertArrayEquals(new byte[] {99, 10, 11, 12, 13, 14, 99}, destination.array());
      assertEquals(6, destination.position());
      assertEquals(6, destination.limit());
      assertEquals(List.of(1L, 1L, 3L, 3L, 4L), channel.offsets);
      assertEquals(42, channel.position());
    }
  }

  /** Short positional writes also support backpatches without disturbing the shared cursor. */
  @Test
  void completesShortWritesAndBackpatches() throws Exception {
    try (ControlledChannel channel = new ControlledChannel(new byte[8], 0, 2, 0, 1, 2)) {
      ByteBuffer source = ByteBuffer.wrap(new byte[] {99, 10, 11, 12, 13, 14, 99});
      source.position(1).limit(6);
      ExactIo.write(channel, 2, source, CONTEXT);
      ExactIo.write(channel, 3, ByteBuffer.wrap(new byte[] {55, 56}), CONTEXT);
      assertArrayEquals(new byte[] {0, 0, 10, 55, 56, 13, 14, 0}, channel.bytes);
      assertEquals(6, source.position());
      assertEquals(6, source.limit());
      assertEquals(List.of(2L, 2L, 4L, 4L, 5L, 3L), channel.offsets);
      assertEquals(42, channel.position());
    }
  }

  /** Large caller buffers are transferred through fixed native windows, for both directions. */
  @Test
  void capsEveryReadAndWriteWindow() throws Exception {
    byte[] bytes = new byte[150_000];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
    try (ControlledChannel reader = new ControlledChannel(bytes);
        ControlledChannel writer = new ControlledChannel(new byte[bytes.length])) {
      ByteBuffer destination = ByteBuffer.allocate(bytes.length);
      ExactIo.read(reader, bytes.length, 0, destination, CONTEXT);
      assertArrayEquals(bytes, destination.array());
      destination.flip();
      ExactIo.write(writer, 0, destination, CONTEXT);
      assertArrayEquals(bytes, writer.bytes);
      assertEquals(List.of(65_536, 65_536, 18_928), reader.windows);
      assertEquals(List.of(65_536, 65_536, 18_928), writer.windows);
      assertEquals(List.of(0L, 65_536L, 131_072L), reader.offsets);
      assertEquals(reader.offsets, writer.offsets);
    }
  }

  /** Permanently stalled providers fail in finite calls without consuming caller bytes. */
  @Test
  void rejectsPermanentZeroProgressInBothDirections() throws Exception {
    for (boolean write : new boolean[] {false, true}) {
      try (ControlledChannel channel = new ControlledChannel(new byte[1])) {
        channel.stalled = true;
        ByteBuffer bytes = ByteBuffer.allocate(1);
        ArchiveException failure =
            assertThrows(ArchiveException.class, () -> transfer(channel, bytes, write));
        assertEquals(write ? FailureKind.DESTINATION : FailureKind.SOURCE, failure.kind());
        assertEquals(
            "io.no-progress", failure.primaryFailure().diagnosticIdentifier().orElseThrow());
        assertTrue(channel.offsets.size() <= 32, "Zero progress must have a finite retry bound");
        assertEquals(0, bytes.position());
      }
    }
  }

  /** Progress resets the retry budget; intermittent stalls must not accumulate across transfers. */
  @Test
  void resetsZeroProgressBudgetAfterSuccessfulTransfer() throws Exception {
    int[] schedule = new int[31];
    schedule[15] = 1;
    try (ControlledChannel channel = new ControlledChannel(new byte[] {3, 4}, schedule)) {
      ByteBuffer destination = ByteBuffer.allocate(2);
      ExactIo.read(channel, 2, 0, destination, CONTEXT);
      assertArrayEquals(new byte[] {3, 4}, destination.array());
    }
  }

  /** EOF inside a previously checked extent is a format failure, not silent partial success. */
  @Test
  void rejectsPrematureEofAndInvalidOutputSpans() throws Exception {
    try (ControlledChannel channel = new ControlledChannel(new byte[] {3}, 1, -1)) {
      ByteBuffer destination = ByteBuffer.allocate(2);
      ArchiveException eof =
          assertThrows(
              ArchiveException.class, () -> ExactIo.read(channel, 2, 0, destination, CONTEXT));
      assertEquals(FailureKind.FORMAT, eof.kind());
      assertEquals("io.unexpected-eof", eof.primaryFailure().diagnosticIdentifier().orElseThrow());
      assertEquals(1, destination.position());
      int calls = channel.offsets.size();
      for (long offset : new long[] {-1, Long.MAX_VALUE}) {
        ArchiveException invalid =
            assertThrows(
                ArchiveException.class,
                () -> ExactIo.write(channel, offset, ByteBuffer.allocate(2), CONTEXT));
        assertEquals(FailureKind.DESTINATION, invalid.kind());
        assertEquals(
            "io.invalid-output-span",
            invalid.primaryFailure().diagnosticIdentifier().orElseThrow());
      }
      assertEquals(calls, channel.offsets.size());
    }
  }

  /** Count-width arithmetic stays in long space and rejects overflow before a provider call. */
  @Test
  void checksLongMultiplicationAndNegativeOperands() throws Exception {
    assertEquals(6_000_000_000L, ExactIo.multiply(3_000_000_000L, 2, CONTEXT));
    assertEquals(Long.MAX_VALUE, ExactIo.multiply(Long.MAX_VALUE, 1, CONTEXT));
    assertEquals(0, ExactIo.multiply(Long.MAX_VALUE, 0, CONTEXT));
    ArchiveException overflow =
        assertThrows(ArchiveException.class, () -> ExactIo.multiply(Long.MAX_VALUE, 2, CONTEXT));
    assertEquals(FailureKind.FORMAT, overflow.kind());
    assertEquals(
        "io.span-overflow", overflow.primaryFailure().diagnosticIdentifier().orElseThrow());
    for (long[] operands : new long[][] {{-1, 1}, {1, -1}, {-1, -1}}) {
      ArchiveException negative =
          assertThrows(
              ArchiveException.class, () -> ExactIo.multiply(operands[0], operands[1], CONTEXT));
      assertEquals(FailureKind.FORMAT, negative.kind());
      assertEquals(
          "io.invalid-span", negative.primaryFailure().diagnosticIdentifier().orElseThrow());
    }
  }

  /** Provider messages change only retained causes, never public diagnostic facts or locations. */
  @Test
  void keepsSourceAndDestinationFailuresIndependentOfProviderText() throws Exception {
    for (boolean write : new boolean[] {false, true}) {
      ArchiveException previous = null;
      for (String message : List.of("device failed", "Accès refusé: provider-specific detail")) {
        try (ControlledChannel channel = new ControlledChannel(new byte[1])) {
          IOException provider = new IOException(message);
          channel.failure = provider;
          ArchiveException failure =
              assertThrows(
                  ArchiveException.class, () -> transfer(channel, ByteBuffer.allocate(1), write));
          assertSame(provider, failure.getCause());
          assertSame(provider, failure.primaryFailure().cause().orElseThrow());
          assertEquals(write ? FailureKind.DESTINATION : FailureKind.SOURCE, failure.kind());
          assertEquals(
              write ? "operation.destination-io" : "operation.source-io",
              failure.primaryFailure().diagnosticIdentifier().orElseThrow());
          assertEquals(OptionalLong.of(7), failure.primaryFailure().ordinal());
          var location = failure.primaryFailure().location().orElseThrow();
          assertEquals(
              CONTEXT.path(), (write ? location.artifact() : location.archive()).orElseThrow());
          assertTrue((write ? location.archive() : location.artifact()).isEmpty());
          if (previous != null) {
            assertEquals(previous.diagnostics(), failure.diagnostics());
            assertEquals(previous.primaryFailure().location(), failure.primaryFailure().location());
            assertEquals(previous.getMessage(), failure.getMessage());
          }
          previous = failure;
        }
      }
    }
  }

  /** Content reads preserve lifetime identity; output writes preserve it as a destination cause. */
  @Test
  void classifiesClosedChannelsAtTheAppropriateConsumerBoundary() throws Exception {
    for (boolean write : new boolean[] {false, true}) {
      try (ControlledChannel channel = new ControlledChannel(new byte[1])) {
        ClosedChannelException closed = new ClosedChannelException();
        channel.failure = closed;
        if (write) {
          ArchiveException failure =
              assertThrows(
                  ArchiveException.class, () -> transfer(channel, ByteBuffer.allocate(1), true));
          assertEquals(FailureKind.DESTINATION, failure.kind());
          assertSame(closed, failure.getCause());
        } else {
          assertSame(
              closed,
              assertThrows(
                  ClosedChannelException.class,
                  () -> transfer(channel, ByteBuffer.allocate(1), false)));
        }
      }
    }
  }

  /** Exercises identical error scenarios through either exact-transfer direction. */
  private static void transfer(ControlledChannel channel, ByteBuffer buffer, boolean write)
      throws IOException {
    if (write) ExactIo.write(channel, 0, buffer, CONTEXT);
    else ExactIo.read(channel, buffer.remaining(), 0, buffer, CONTEXT);
  }

  /**
   * A positional byte store with scripted provider progress; sequential I/O is deliberately absent.
   */
  private static final class ControlledChannel extends FileChannel {
    final byte[] bytes;
    final int[] schedule;
    final List<Long> offsets = new ArrayList<>();
    final List<Integer> windows = new ArrayList<>();
    boolean stalled;
    IOException failure;
    private int step;

    /**
     * Takes a byte store and optional read/write counts, then defaults to full requested progress.
     */
    ControlledChannel(byte[] bytes, int... schedule) {
      this.bytes = bytes;
      this.schedule = schedule;
    }

    /** Applies the same fault schedule to reads and writes before moving any bytes. */
    private int count(ByteBuffer buffer, long position) throws IOException {
      offsets.add(position);
      windows.add(buffer.remaining());
      if (offsets.size() > 1000) throw new IOException("test provider retry ceiling exceeded");
      if (failure != null) throw failure;
      if (stalled) return 0;
      return step < schedule.length ? schedule[step++] : buffer.remaining();
    }

    /** Reads no more than the scripted progress and the available byte store. */
    @Override
    public int read(ByteBuffer destination, long position) throws IOException {
      int count = count(destination, position);
      if (count <= 0) return count;
      if (position >= bytes.length) return -1;
      count = Math.min(count, Math.min(destination.remaining(), bytes.length - (int) position));
      destination.put(bytes, (int) position, count);
      return count;
    }

    /** Writes no more than the scripted progress into the byte store. */
    @Override
    public int write(ByteBuffer source, long position) throws IOException {
      int count = count(source, position);
      if (count <= 0) return count;
      count = Math.min(count, Math.min(source.remaining(), bytes.length - (int) position));
      source.get(bytes, (int) position, count);
      return count;
    }

    @Override
    public long position() {
      return 42;
    }

    @Override
    public long size() {
      return bytes.length;
    }

    @Override
    protected void implCloseChannel() {
      /* The byte store owns no external resource. */
    }

    @Override
    public int read(ByteBuffer dst) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int write(ByteBuffer src) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileChannel position(long position) {
      throw new UnsupportedOperationException();
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
