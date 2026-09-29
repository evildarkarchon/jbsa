package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java admission and preflight contract of the unpromoted jlibdeflate candidate. None of these
 * tests may initialize a provider class: loading extracts undeletable Windows temporary DLLs, so
 * native behavior stays in the explicit {@code JlibdeflateQualificationTest} lane.
 */
final class JlibdeflateZlibTest {
  private static final IoContext CONTEXT = IoContext.of(Path.of("candidate.bin"), Operation.OPEN);

  /** Matches libdeflate 1.25's per-5000-byte block allowance plus the RFC 1950 envelope. */
  @Test
  void storedBoundMatchesTheProviderWorstCase() {
    assertEquals(11, JlibdeflateZlib.storedBound(0));
    assertEquals(12, JlibdeflateZlib.storedBound(1));
    assertEquals(5011, JlibdeflateZlib.storedBound(5000));
    assertEquals(5017, JlibdeflateZlib.storedBound(5001));
    assertEquals(1_049_632, JlibdeflateZlib.storedBound(1 << 20));
    assertEquals(67_175_980, JlibdeflateZlib.storedBound(64 << 20));
    assertThrows(IllegalArgumentException.class, () -> JlibdeflateZlib.storedBound(-1));
  }

  /** Whole-buffer credit covers the complete source, worst-case output and one overrun probe. */
  @Test
  void heapCreditsCoverEveryWholeBufferArray() {
    assertEquals(11, JlibdeflateZlib.encodeHeapBytes(0));
    assertEquals((1 << 20) + 1_049_632L, JlibdeflateZlib.encodeHeapBytes(1 << 20));
    assertEquals(100 + 4096 + 1, JlibdeflateZlib.decodeHeapBytes(100, 4096));
    // The measured level-12 compressor state is roughly 9.03 MB per instance.
    assertTrue(JlibdeflateZlib.ENCODE_NATIVE_BYTES >= 9_033_088);
    assertTrue(JlibdeflateZlib.DECODE_NATIVE_BYTES >= 13_632);
  }

  /**
   * The qualified range is inclusive; provider and dispatch limits are checked before narrowing.
   */
  @Test
  void admissionRejectsOutOfRangeSizesBeforeNarrowing() throws Exception {
    JlibdeflateZlib.admitEncode(0, CONTEXT);
    JlibdeflateZlib.admitEncode(JlibdeflateZlib.DISPATCH_LIMIT, CONTEXT);
    JlibdeflateZlib.admitDecode(
        JlibdeflateZlib.storedBound(JlibdeflateZlib.DISPATCH_LIMIT),
        JlibdeflateZlib.DISPATCH_LIMIT,
        CONTEXT);

    assertPolicy(
        "codec.dispatch-limit",
        () -> JlibdeflateZlib.admitEncode(JlibdeflateZlib.DISPATCH_LIMIT + 1, CONTEXT));
    assertPolicy(
        "codec.dispatch-limit",
        () ->
            JlibdeflateZlib.admitDecode(
                JlibdeflateZlib.storedBound(JlibdeflateZlib.DISPATCH_LIMIT) + 1, 1, CONTEXT));
    assertPolicy("codec.size-limit", () -> JlibdeflateZlib.admitEncode(-1, CONTEXT));
    assertPolicy(
        "codec.size-limit", () -> JlibdeflateZlib.admitEncode(1L + Integer.MAX_VALUE, CONTEXT));
    assertPolicy(
        "codec.size-limit", () -> JlibdeflateZlib.admitDecode(1L + Integer.MAX_VALUE, 1, CONTEXT));
    assertPolicy("codec.size-limit", () -> JlibdeflateZlib.admitDecode(1, -1, CONTEXT));
  }

  /**
   * jlibdeflate 0.1.0 would {@code System.load} a DLL from this caller-controlled directory, which
   * JBSA-CODEC-011 forbids; the candidate must refuse before any provider class initializes.
   */
  @Test
  void preflightRejectsTheProviderLibraryPathOverride() {
    String previous = System.getProperty(JlibdeflateZlib.LIBRARY_PATH_PROPERTY);
    System.setProperty(JlibdeflateZlib.LIBRARY_PATH_PROPERTY, "C:/caller-supplied");
    try {
      ArchiveException error =
          assertThrows(ArchiveException.class, () -> JlibdeflateZlib.preflight("decode", CONTEXT));
      assertEquals(FailureKind.CAPABILITY, error.primaryFailure().kind());
      assertEquals("codec.unavailable", error.diagnostics().getFirst().identifier());
      var values = error.diagnostics().getFirst().values();
      assertEquals("native-configuration", values.get("capabilityCause"));
      assertEquals("zlib", values.get("codec"));
      assertEquals("decode", values.get("direction"));
      assertEquals(JlibdeflateZlib.CANDIDATE_PROFILE, values.get("profile"));
    } finally {
      if (previous == null) System.clearProperty(JlibdeflateZlib.LIBRARY_PATH_PROPERTY);
      else System.setProperty(JlibdeflateZlib.LIBRARY_PATH_PROPERTY, previous);
    }
  }

  /**
   * An unavailable candidate decoder may fall back to JDK streaming before any work, which proves
   * the fallback leg without loading the provider; the stream must still decode exactly.
   */
  @Test
  void unavailableCandidateDecodeFallsBackToJdkStreamingBeforeWork() throws Exception {
    byte[] data = new byte[70_000];
    for (int index = 0; index < data.length; index++) data[index] = (byte) (index % 251);
    byte[] stored = jdkEncode(data);
    withLibraryPathOverride(
        () -> {
          var decoded = new java.io.ByteArrayOutputStream();
          try (var budget = budget()) {
            long count =
                JlibdeflateZlib.dispatchDecode(
                    source(stored),
                    stored.length,
                    data.length,
                    sink(decoded),
                    () -> {},
                    budget,
                    CONTEXT);
            assertEquals(data.length, count);
          }
          assertArrayEquals(data, decoded.toByteArray());
        });
  }

  /**
   * The JDK fallback reports invalid data itself; it is never a second attempt after the native
   * one.
   */
  @Test
  void fallbackDecodeReportsJdkFormatFailures() throws Exception {
    byte[] stored = jdkEncode(new byte[4096]);
    stored[stored.length - 1] ^= 1;
    withLibraryPathOverride(
        () -> {
          try (var budget = budget()) {
            ArchiveException error =
                assertThrows(
                    ArchiveException.class,
                    () ->
                        JlibdeflateZlib.dispatchDecode(
                            source(stored),
                            stored.length,
                            4096,
                            (offset, bytes) -> {},
                            () -> {},
                            budget,
                            CONTEXT));
            assertEquals(FailureKind.FORMAT, error.primaryFailure().kind());
            assertEquals("codec.invalid-data", error.diagnostics().getFirst().identifier());
            assertEquals(JdkZlib.PROFILE, error.diagnostics().getFirst().values().get("profile"));
          }
        });
  }

  /** Sizes beyond the measured envelope keep JDK streaming without consulting the candidate. */
  @Test
  void oversizedDecodeUsesJdkStreamingWithoutPreflight() throws Exception {
    byte[] data = new byte[(int) JlibdeflateZlib.DISPATCH_LIMIT + 1];
    byte[] stored = jdkEncode(data);
    var decoded = new java.io.ByteArrayOutputStream(data.length);
    // The override would fail candidate preflight, so success proves the candidate was not chosen.
    withLibraryPathOverride(
        () -> {
          try (var budget = budget()) {
            JlibdeflateZlib.dispatchDecode(
                source(stored),
                stored.length,
                data.length,
                sink(decoded),
                () -> {},
                budget,
                CONTEXT);
          }
        });
    assertArrayEquals(data, decoded.toByteArray());
  }

  /**
   * Encoding pins the candidate before reading input and never falls back to a different encoder.
   */
  @Test
  void unavailableCandidateEncodeFailsBeforeReadingOrWriting() throws Exception {
    withLibraryPathOverride(
        () -> {
          try (var budget = budget()) {
            ArchiveException error =
                assertThrows(
                    ArchiveException.class,
                    () ->
                        JlibdeflateZlib.encode(
                            (offset, bytes) -> fail("candidate read before pinning"),
                            4096,
                            (offset, bytes) -> fail("candidate wrote before pinning"),
                            () -> {},
                            budget,
                            CONTEXT));
            assertEquals(FailureKind.CAPABILITY, error.primaryFailure().kind());
          }
        });
  }

  /** Runs a check with the forbidden provider override present, then restores the host value. */
  private static void withLibraryPathOverride(ThrowingRunnable check) throws Exception {
    String previous = System.getProperty(JlibdeflateZlib.LIBRARY_PATH_PROPERTY);
    System.setProperty(JlibdeflateZlib.LIBRARY_PATH_PROPERTY, "C:/caller-supplied");
    try {
      check.run();
    } finally {
      if (previous == null) System.clearProperty(JlibdeflateZlib.LIBRARY_PATH_PROPERTY);
      else System.setProperty(JlibdeflateZlib.LIBRARY_PATH_PROPERTY, previous);
    }
  }

  /** Produces baseline JDK level-9 bytes through the production streaming adapter. */
  private static byte[] jdkEncode(byte[] data) throws Exception {
    var encoded = new java.io.ByteArrayOutputStream();
    JdkZlib.encode(
        java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream(data)),
        data.length,
        sink(encoded),
        () -> {},
        CONTEXT);
    return encoded.toByteArray();
  }

  private static ResourceBudget budget() {
    return new ResourceBudget(
        ResourceLimits.standard(), CONTEXT, 256L * 1024 * 1024, 64L * 1024 * 1024, 0);
  }

  private static JdkZlib.ByteSource source(byte[] data) {
    return (offset, bytes) -> bytes.put(data, Math.toIntExact(offset), bytes.remaining());
  }

  private static JdkZlib.ByteSink sink(java.io.ByteArrayOutputStream output) {
    return (offset, bytes) -> {
      assertEquals(output.size(), offset);
      byte[] chunk = new byte[bytes.remaining()];
      bytes.get(chunk);
      output.write(chunk);
    };
  }

  private static void assertPolicy(String identifier, ThrowingRunnable call) {
    ArchiveException error = assertThrows(ArchiveException.class, call::run);
    assertEquals(FailureKind.POLICY, error.primaryFailure().kind());
    assertEquals(identifier, error.diagnostics().getFirst().identifier());
  }

  /** An admission call whose checked failure is the observation under test. */
  @FunctionalInterface
  private interface ThrowingRunnable {
    /** Runs one admission check. */
    void run() throws Exception;
  }
}
