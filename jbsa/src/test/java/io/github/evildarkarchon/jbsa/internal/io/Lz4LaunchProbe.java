package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.file.Path;

/** Fresh-process probe; never linked from production or the public archive interface. */
public final class Lz4LaunchProbe {
  private Lz4LaunchProbe() {}

  /**
   * Verifies lazy zlib independence, then reports normalized LZ4 capability without destination
   * effects.
   */
  public static void main(String[] args) throws Exception {
    var context = IoContext.of(Path.of("probe.bin"), Operation.OPEN);
    BethesdaArchives.standard();
    long size =
        JdkZlib.encode(
            Channels.newChannel(new ByteArrayInputStream(new byte[] {42})),
            1,
            (offset, bytes) -> bytes.position(bytes.limit()),
            () -> {},
            context);
    if (size == 0) throw new AssertionError("zlib disabled");
    if (args[0].equals("zlib-only")) {
      System.out.println("ZLIB_OK");
      return;
    }
    try {
      Lz4Runtime.preflight("raw-lz4", "encode", context);
      Lz4Runtime.preflight("lz4-frame", "decode", context);
      System.out.println("LZ4_OK");
    } catch (ArchiveException failure) {
      if (failure.primaryFailure().kind() != FailureKind.CAPABILITY) throw failure;
      System.out.println(
          "CAPABILITY:" + failure.diagnostics().getFirst().values().get("capabilityCause"));
    }
  }
}
