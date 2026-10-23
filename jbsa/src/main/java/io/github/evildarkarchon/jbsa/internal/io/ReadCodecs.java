package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import java.util.EnumMap;
import java.util.Map;

/**
 * The Payload Codecs one reader has admitted for decoding, each admitted the first time the reader
 * needs it and reused for every later payload, chunk and cursor (#92: "when IndexSession or
 * OwnedArchive first needs a codec"). Admission pins the process's LZ4 provider, so a reused value
 * charges and decodes under the same provider a fresh admission would resolve.
 *
 * <p>Only successful admissions are kept. A capability failure is thrown and not remembered, so the
 * next payload that needs the codec admits it again and its failure names that payload's location,
 * as every failure did before the codec was cached.
 *
 * <p>Not thread-safe: {@link IndexSession} confines it to the opening thread, and {@link
 * OwnedArchive} guards it with its lifetime lock.
 */
final class ReadCodecs {
  private final Map<PayloadCodec, AdmittedCodec> admitted = new EnumMap<>(PayloadCodec.class);
  private int admissions;

  /**
   * Returns {@code codec} admitted for decoding, admitting it on first use.
   *
   * @param context the location a capability failure names
   * @throws ArchiveException {@code CAPABILITY codec.unavailable} when no LZ4 provider can be
   *     admitted
   */
  AdmittedCodec admit(PayloadCodec codec, IoContext context) throws ArchiveException {
    AdmittedCodec found = admitted.get(codec);
    if (found == null) {
      found = AdmittedCodec.admit(codec, "decode", context);
      admissions++;
      admitted.put(codec, found);
    }
    return found;
  }

  /** Returns how many admissions this reader has performed; a test seam. */
  int admissions() {
    return admissions;
  }
}
