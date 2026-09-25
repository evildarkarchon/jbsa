package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Explicit-only, reproducible targeted native qualification; not the formal Performance-v1 gate.
 */
final class Lz4Qualification {
  private static final IoContext CONTEXT =
      IoContext.of(Path.of("qualification.bin"), Operation.OPEN);
  private static final long NATIVE_CEILING = 40L * 1024 * 1024;
  private final StringBuilder rows =
      new StringBuilder(
          "codec\tcorpus\tdecoded_bytes\tstored_bytes\tinput_sha256\tencoded_sha256\trepetition\tencode_ms\tdecode_ms\tencode_mib_s\tdecode_mib_s\tencode_max_checkpoint_gap_ms\tdecode_max_checkpoint_gap_ms\tencode_native_credit_bytes\tdecode_native_credit_bytes\tprocess_peak_working_set_bytes\n");

  /** Runs fixed-seed semantic, deterministic, memory-credit and cancellation qualification. */
  @Test
  void qualifiesPinnedNativeProfiles() throws Exception {
    Path directory = Path.of("../docs/reviews/issue43-lz4-runtime");
    Files.createDirectories(directory);
    for (String codec : new String[] {"raw-lz4", "lz4-frame"}) {
      for (int size : new int[] {0, 65536, 16 * 1024 * 1024}) {
        for (boolean random : new boolean[] {false, true}) {
          byte[] data = new byte[size];
          if (random) new Random(43).nextBytes(data);
          else for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
          qualify(codec, random ? "random-seed43" : "repeated-251", data);
        }
      }
    }
    qualifyLargeFrameBlocks();
    Files.writeString(directory.resolve("measurements.tsv"), rows, StandardCharsets.UTF_8);
    byte[] profile;
    try (var resource = Lz4Runtime.class.getResourceAsStream("/META-INF/jbsa-codec-profile.json")) {
      assertNotNull(resource);
      profile = resource.readAllBytes();
    }
    Files.write(directory.resolve("qualified-codec-profile.json"), profile);
    Files.writeString(
        directory.resolve("environment.txt"),
        "timestamp="
            + java.time.Instant.now()
            + "\njava="
            + System.getProperty("java.runtime.version")
            + "\nvm="
            + System.getProperty("java.vm.name")
            + "\nos="
            + System.getProperty("os.name")
            + "\narch="
            + System.getProperty("os.arch")
            + "\nprocessors="
            + Runtime.getRuntime().availableProcessors()
            + "\nprofile="
            + Lz4Runtime.PROFILE
            + "\nprofile_sha256="
            + hash(profile)
            + "\n",
        StandardCharsets.UTF_8);
  }

  /**
   * Measures adapter calls with borrowed memory I/O, independently verifies bytes, and repeats
   * hashes.
   */
  private void qualify(String codec, String corpus, byte[] data) throws Exception {
    byte[] first = null;
    for (int repetition = 0; repetition < 3; repetition++) {
      var encoded = new ByteArrayOutputStream();
      var encodeClock = new Checkpoints();
      long start = System.nanoTime();
      try (ResourceBudget budget = budget(NATIVE_CEILING)) {
        encode(codec, data, sink(encoded), encodeClock, budget);
        assertAllCreditsReturned(budget);
      }
      long encodeNanos = System.nanoTime() - start;
      byte[] frame = encoded.toByteArray();
      if (first == null) first = frame;
      else assertArrayEquals(first, frame, "repeatable compressed bytes for " + codec);
      var decoded = new ByteArrayOutputStream(data.length);
      var decodeClock = new Checkpoints();
      start = System.nanoTime();
      try (ResourceBudget budget = budget(NATIVE_CEILING)) {
        decode(codec, frame, data.length, sink(decoded), decodeClock, budget);
        assertAllCreditsReturned(budget);
      }
      long decodeNanos = System.nanoTime() - start;
      assertArrayEquals(data, decoded.toByteArray(), "semantic round trip for " + codec);
      long encodeCredit =
          codec.equals("raw-lz4")
              ? data.length * 2L + data.length / 255 + 16 + 524288 + 32
              : Lz4Frame.ENCODE_NATIVE_BYTES;
      long decodeCredit =
          codec.equals("raw-lz4")
              ? data.length + (long) frame.length + 16
              : Lz4Frame.DECODE_NATIVE_BYTES;
      assertInsufficientCredit(codec, data, frame, encodeCredit, decodeCredit);
      assertCancellationReturnsCredits(codec, data, frame);
      append(
          codec,
          corpus,
          data,
          frame,
          repetition,
          encodeNanos,
          decodeNanos,
          encodeClock.maxGap,
          decodeClock.maxGap,
          encodeCredit,
          decodeCredit);
    }
  }

  /** Exercises the decoder's worst upstream block size independently of the profile encoder. */
  private void qualifyLargeFrameBlocks() throws Exception {
    byte[] data = new byte[16 * 1024 * 1024];
    new Random(44).nextBytes(data);
    try (var arena = java.lang.foreign.Arena.ofConfined()) {
      var preferences =
          new org.lwjgl.util.lz4.LZ4FPreferences(
              arena.allocate(org.lwjgl.util.lz4.LZ4FPreferences.SIZEOF, 8).asByteBuffer());
      preferences.frameInfo().blockSizeID(org.lwjgl.util.lz4.LZ4Frame.LZ4F_max4MB);
      var input = arena.allocate(data.length, 8).asByteBuffer();
      input.put(data).flip();
      var output =
          arena
              .allocate(
                  org.lwjgl.util.lz4.LZ4Frame.LZ4F_compressFrameBound(data.length, preferences), 8)
              .asByteBuffer();
      long count = org.lwjgl.util.lz4.LZ4Frame.LZ4F_compressFrame(output, input, preferences);
      assertFalse(org.lwjgl.util.lz4.LZ4Frame.LZ4F_isError(count));
      byte[] frame = new byte[Math.toIntExact(count)];
      output.get(frame);
      for (int repetition = 0; repetition < 3; repetition++) {
        var decoded = new ByteArrayOutputStream(data.length);
        var checkpoints = new Checkpoints();
        long start = System.nanoTime();
        try (ResourceBudget budget = budget(Lz4Frame.DECODE_NATIVE_BYTES)) {
          decode("lz4-frame", frame, data.length, sink(decoded), checkpoints, budget);
          try (var returned = budget.reserve(4096, Lz4Frame.DECODE_NATIVE_BYTES, 0, 0)) {
            assertNotNull(returned);
          }
        }
        long elapsed = System.nanoTime() - start;
        assertArrayEquals(data, decoded.toByteArray());
        append(
            "lz4-frame",
            "upstream-max4MB-random-seed44",
            data,
            frame,
            repetition,
            0,
            elapsed,
            0,
            checkpoints.maxGap,
            0,
            Lz4Frame.DECODE_NATIVE_BYTES);
      }
    }
  }

  /** Refuses the exact one-byte-short native envelope before any borrowed output effect. */
  private static void assertInsufficientCredit(
      String codec, byte[] data, byte[] frame, long encodeCredit, long decodeCredit)
      throws Exception {
    try (ResourceBudget budget = budget(encodeCredit - 1)) {
      ArchiveException error =
          assertThrows(
              ArchiveException.class,
              () ->
                  encode(
                      codec, data, (offset, bytes) -> fail("unadmitted sink"), () -> {}, budget));
      assertEquals(FailureKind.POLICY, error.primaryFailure().kind());
    }
    try (ResourceBudget budget = budget(decodeCredit - 1)) {
      ArchiveException error =
          assertThrows(
              ArchiveException.class,
              () ->
                  decode(
                      codec,
                      frame,
                      data.length,
                      (offset, bytes) -> fail("unadmitted sink"),
                      () -> {},
                      budget));
      assertEquals(FailureKind.POLICY, error.primaryFailure().kind());
    }
  }

  /** Requests cancellation at the first post-dispatch raw checkpoint or second frame window. */
  private static void assertCancellationReturnsCredits(String codec, byte[] data, byte[] frame)
      throws Exception {
    // Tiny/empty frames may finish before a third decode checkpoint, so cancellation is covered by
    // substantial streams; empty handling is still covered by round-trip and credit qualification.
    if (data.length < 65536 || (codec.equals("lz4-frame") && data.length <= 131072)) return;
    for (boolean encoding : new boolean[] {true, false}) {
      IOException cancelled = new IOException("qualification cancellation");
      int[] checks = {0};
      JdkZlib.Checkpoint checkpoint =
          () -> {
            if (++checks[0] == 3) throw cancelled;
          };
      try (ResourceBudget budget = budget(NATIVE_CEILING)) {
        assertSame(
            cancelled,
            assertThrows(
                IOException.class,
                () -> {
                  if (encoding) encode(codec, data, (offset, bytes) -> {}, checkpoint, budget);
                  else decode(codec, frame, data.length, (offset, bytes) -> {}, checkpoint, budget);
                }));
        assertAllCreditsReturned(budget);
      }
    }
  }

  /**
   * Writes one machine-readable observation without interpreting process peak memory as codec-only.
   */
  private void append(
      String codec,
      String corpus,
      byte[] data,
      byte[] frame,
      int repetition,
      long encodeNanos,
      long decodeNanos,
      long encodeGap,
      long decodeGap,
      long encodeCredit,
      long decodeCredit)
      throws Exception {
    rows.append(
        String.format(
            java.util.Locale.ROOT,
            "%s\t%s\t%d\t%d\t%s\t%s\t%d\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%d\t%d\t%d%n",
            codec,
            corpus,
            data.length,
            frame.length,
            hash(data),
            hash(frame),
            repetition,
            encodeNanos / 1e6,
            decodeNanos / 1e6,
            throughput(data.length, encodeNanos),
            throughput(data.length, decodeNanos),
            encodeGap / 1e6,
            decodeGap / 1e6,
            encodeCredit,
            decodeCredit,
            peakWorkingSet()));
  }

  /** Reads the Windows process lifetime peak, including JVM, corpus and measurement allocations. */
  private static long peakWorkingSet() throws Exception {
    Process process =
        new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-Command",
                "(Get-Process -Id " + ProcessHandle.current().pid() + ").PeakWorkingSet64")
            .start();
    String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, process.waitFor());
    return Long.parseLong(output);
  }

  private static double throughput(int bytes, long nanos) {
    return nanos == 0 ? 0 : bytes / 1048576.0 / (nanos / 1e9);
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static ResourceBudget budget(long nativeBytes) {
    return new ResourceBudget(ResourceLimits.standard(), CONTEXT, 4096, nativeBytes, 0);
  }

  /** Checks that neither successful operations nor cancelled work retain any admitted credit. */
  private static void assertAllCreditsReturned(ResourceBudget budget) throws Exception {
    try (var returned = budget.reserve(4096, NATIVE_CEILING, 0, 0)) {
      assertNotNull(returned);
    }
  }

  private static JdkZlib.ByteSource source(byte[] data) {
    return (offset, bytes) -> bytes.put(data, Math.toIntExact(offset), bytes.remaining());
  }

  /** Uses bounded positional output copying, whose cost is included in measured adapter time. */
  private static JdkZlib.ByteSink sink(ByteArrayOutputStream output) {
    return (offset, bytes) -> {
      assertEquals(output.size(), offset);
      byte[] chunk = new byte[bytes.remaining()];
      bytes.get(chunk);
      output.write(chunk);
    };
  }

  /** Dispatches only to the release-pinned raw or frame profile under test. */
  private static void encode(
      String codec,
      byte[] data,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoints,
      ResourceBudget budget)
      throws IOException {
    if (codec.equals("raw-lz4"))
      Lz4Raw.encode(source(data), data.length, sink, checkpoints, budget, CONTEXT);
    else Lz4Frame.encode(source(data), data.length, sink, checkpoints, budget, CONTEXT);
  }

  /** Dispatches exact on-wire and logical sizes to the release-pinned decoder under test. */
  private static void decode(
      String codec,
      byte[] frame,
      long size,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoints,
      ResourceBudget budget)
      throws IOException {
    if (codec.equals("raw-lz4"))
      Lz4Raw.decode(source(frame), frame.length, size, sink, checkpoints, budget, CONTEXT);
    else Lz4Frame.decode(source(frame), frame.length, size, sink, checkpoints, budget, CONTEXT);
  }

  /**
   * Measures observed cancellation checkpoint gaps, conservatively including Java I/O and pauses.
   */
  private static final class Checkpoints implements JdkZlib.Checkpoint {
    private long previous;
    private long maxGap;

    /** Records elapsed time between observations, including callbacks and thread scheduling. */
    @Override
    public void check() {
      long now = System.nanoTime();
      if (previous != 0) maxGap = Math.max(maxGap, now - previous);
      previous = now;
    }
  }
}
