package io.github.evildarkarchon.jbsa.internal.pack;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.Operation;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The codec map's own costing: the credits a readback declaration charges (#92, user story 4). */
final class CodecsTest {
  private static final IoContext CONTEXT =
      IoContext.of(Path.of("unused.mem").toAbsolutePath(), Operation.PACK);

  /**
   * A readback charges the largest decode cost of any codec it declares, under the codecs pack
   * admission yielded, plus the largest source for raw LZ4, whose reader holds each block's decoded
   * copy. A codec the request never admitted is a JBSA bug.
   */
  @Test
  void readbackChargesTheAdmittedDecodeCostAndTheDecodedCopy() throws Exception {
    // The codecs pack admission yields: the JDK codecs always, and raw LZ4 when required.
    Codecs codecs = Codecs.admit(Set.of(PayloadCodec.LZ4_RAW), CONTEXT);
    AdmittedCodec zlib = codecs.admitted(PayloadCodec.ZLIB);
    AdmittedCodec raw = codecs.admitted(PayloadCodec.LZ4_RAW);
    assertEquals(zlib.maxDecodeCost(), codecs.readbackCost(readback(Set.of(PayloadCodec.ZLIB))));
    // Raw block buffers are native under LWJGL and heap under the portable provider; the decoded
    // copy of the largest source is always heap.
    AdmittedCodec.Cost rawCost =
        new AdmittedCodec.Cost(
            700 + raw.maxDecodeCost().heapBytes(), raw.maxDecodeCost().nativeBytes());
    assertEquals(rawCost, codecs.readbackCost(readback(Set.of(PayloadCodec.LZ4_RAW))));
    // One entry is open at a time, so two declared codecs charge the larger of each dimension.
    assertEquals(
        new AdmittedCodec.Cost(
            Math.max(zlib.maxDecodeCost().heapBytes(), rawCost.heapBytes()),
            Math.max(zlib.maxDecodeCost().nativeBytes(), rawCost.nativeBytes())),
        codecs.readbackCost(readback(Set.of(PayloadCodec.ZLIB, PayloadCodec.LZ4_RAW))));
    assertThrows(
        IllegalStateException.class,
        () -> codecs.readbackCost(readback(Set.of(PayloadCodec.BSA_LZ4_FRAME))));
  }

  /** Returns a readback declaration of the given codecs whose largest source is 700 bytes. */
  private static Admitted.Readback readback(Set<PayloadCodec> decodes) {
    return new Admitted.Readback("ba2.noncanonical-staged-output", 0, 0, 1, decodes, 700);
  }
}
