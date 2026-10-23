package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * zlib evidence names the Codec Profile of the LZ4 provider set pinned for this process (#89). The
 * in-process JVM pins native on Windows x64 and portable everywhere else; {@code Lz4LaunchTest}
 * covers the portable provider on Windows in a fresh JVM.
 */
final class JdkZlibTest {
  private static final IoContext CONTEXT = IoContext.of(Path.of("zlib.bin"), Operation.OPEN);

  /** Invalid data reports the pinned provider's profile, never a constant native alias. */
  @Test
  void invalidDataNamesThePinnedProviderProfile() throws Exception {
    byte[] stored = {1, 2, 3, 4};
    ArchiveException failure;
    try (var decoder =
        JdkZlib.decoder(
            (offset, bytes) -> bytes.put(stored, (int) offset, bytes.remaining()),
            stored.length,
            8,
            CONTEXT)) {
      failure = assertThrows(ArchiveException.class, () -> decoder.read(ByteBuffer.allocate(8)));
    }
    // Resolved after the failure, so an unpinned process proves the failure pinned the same set.
    String profile =
        switch (Lz4Runtime.selected()) {
          case NATIVE -> "jbsa-lz4-v1";
          case PORTABLE -> "jbsa-lz4-portable-v1";
        };
    assertEquals(FailureKind.FORMAT, failure.primaryFailure().kind());
    Diagnostic diagnostic = failure.diagnostics().getFirst();
    assertEquals("codec.invalid-data", diagnostic.identifier());
    assertEquals(
        Map.of(
            "codec",
            "zlib",
            "direction",
            "decode",
            "expected",
            "8",
            "actual",
            "0",
            "profile",
            profile),
        diagnostic.values());
  }
}
