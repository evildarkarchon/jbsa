package io.github.evildarkarchon.jbsa.internal.pack;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.Operation;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec;
import io.github.evildarkarchon.jbsa.internal.codec.Lz4Runtime;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The codec map's own costing: the credits a readback declaration charges (#92, user story 4). */
final class CodecsTest {
  private static final IoContext CONTEXT =
      IoContext.destination(Path.of("unused.mem").toAbsolutePath(), Operation.PACK);

  /**
   * A readback charges the largest decode cost of any codec it declares, under the codecs pack
   * admission yielded. Raw LZ4 is costed for the part's largest actual block, as the reader costs
   * it, not for a dispatch-limit block (#102), plus that block's decoded copy, which its reader
   * holds. A codec the request never admitted is a JBSA bug.
   *
   * <p>The declaration's largest raw block stores 1,200 bytes and decodes to 1,000. Raw LZ4 holds
   * both plus 16 bytes in one working set, native under LWJGL and heap under lz4-java, beside 4,096
   * heap bytes of bookkeeping; the 1,000-byte decoded copy is always heap. Zlib's decode costs the
   * same at any size.
   */
  @Test
  void readbackChargesTheActualRawBlockAndItsDecodedCopy() throws Exception {
    // The codecs pack admission yields: the JDK codecs always, and raw LZ4 when required. The
    // provider is whichever this host pins, so the raw literals are chosen by it.
    Codecs codecs = Codecs.admit(Set.of(PayloadCodec.LZ4_RAW), CONTEXT);
    AdmittedCodec.Cost zlib = new AdmittedCodec.Cost(66_560, 65_536);
    assertEquals(zlib, codecs.readbackCost(readback(Set.of(PayloadCodec.ZLIB))));
    AdmittedCodec.Cost raw =
        switch (Lz4Runtime.selected()) {
          case NATIVE -> new AdmittedCodec.Cost(4_096 + 1_000, 2_216);
          case PORTABLE -> new AdmittedCodec.Cost(4_096 + 2_216 + 1_000, 0);
        };
    assertEquals(raw, codecs.readbackCost(readback(Set.of(PayloadCodec.LZ4_RAW))));
    // One entry is open at a time, so two declared codecs charge the larger of each dimension.
    assertEquals(
        new AdmittedCodec.Cost(
            Math.max(zlib.heapBytes(), raw.heapBytes()),
            Math.max(zlib.nativeBytes(), raw.nativeBytes())),
        codecs.readbackCost(readback(Set.of(PayloadCodec.ZLIB, PayloadCodec.LZ4_RAW))));
    assertThrows(
        IllegalStateException.class,
        () -> codecs.readbackCost(readback(Set.of(PayloadCodec.BSA_LZ4_FRAME))));
  }

  /**
   * Returns a readback declaration of the given codecs whose largest raw block stores 1,200 bytes
   * and decodes to 1,000.
   */
  private static Admitted.Readback readback(Set<PayloadCodec> decodes) {
    return new Admitted.Readback("ba2.noncanonical-staged-output", 0, 0, 1, decodes, 1_200, 1_000);
  }
}
