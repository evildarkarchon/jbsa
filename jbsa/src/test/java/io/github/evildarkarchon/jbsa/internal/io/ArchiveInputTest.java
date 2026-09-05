package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks real file bounds and Windows input lifetimes below the exported archive interface. */
final class ArchiveInputTest {
  @TempDir Path directory;

  /** Sparse offsets stay long, while a read never reaches beyond its declared span. */
  @Test
  void readsAboveTwoGiBAndRejectsInvalidSpans() throws Exception {
    Path path = directory.resolve("sparse.bin");
    long offset = 3L * 1024 * 1024 * 1024;
    try (FileChannel writer =
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      writer.write(ByteBuffer.wrap(new byte[] {41, 42}), offset);
    }
    try (ArchiveInput input = ArchiveInput.open(path, Operation.OPEN)) {
      ByteBuffer bytes = ByteBuffer.allocate(2);
      input.readExact(offset, bytes);
      assertArrayEquals(new byte[] {41, 42}, bytes.array());
      input.readExact(offset + 2, ByteBuffer.allocate(0));
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(
                  ArchiveException.class, () -> input.readExact(offset + 1, ByteBuffer.allocate(2)))
              .kind());
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(
                  ArchiveException.class,
                  () -> input.readExact(Long.MAX_VALUE, ByteBuffer.allocate(2)))
              .kind());
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(ArchiveException.class, () -> input.readExact(-1, ByteBuffer.allocate(0)))
              .kind());
    }
  }
}
