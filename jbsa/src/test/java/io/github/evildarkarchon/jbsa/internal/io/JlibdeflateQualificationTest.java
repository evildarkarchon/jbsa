package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Explicit issue 52 Final Profile Gate evidence for the jlibdeflate candidate against the {@link
 * JdkZlib} baseline. Opt-in because loading jlibdeflate 0.1.0 leaves undeletable DLL copies in the
 * Windows temporary directory. Each method rewrites one evidence file under {@code
 * docs/development/evidence/issue52-jlibdeflate}; hard assertions guard only harness integrity and
 * adapter contracts, while gate verdicts are recorded by the reviewed decision document.
 */
@EnabledIfSystemProperty(named = "jbsa.zlib.qualification", matches = "true")
@TestMethodOrder(MethodOrderer.MethodName.class)
final class JlibdeflateQualificationTest {
  private static final IoContext CONTEXT =
      IoContext.of(Path.of("qualification.bin"), Operation.OPEN);
  private static final String[] CORPORA = {
    "repeated-251", "text-seed52", "float-mesh-seed52", "mixed-seed52", "random-seed52"
  };
  private static final int[] CONFORMANCE_SIZES = {
    0, 1, 4096, 65536, 1 << 20, 8 << 20, (8 << 20) + 1, 16 << 20
  };
  private static final int[] MEASURED_SIZES = {65536, 1 << 20, 8 << 20, 16 << 20};
  private static final int WARMUPS = 1;
  private static final int REPETITIONS = 5;
  private static final String JDK = "jdk-zlib-level9-streaming";
  private static final String CANDIDATE = "jlibdeflate-level12-whole-buffer";

  @TempDir Path temporary;

  /** Semantic Decode/Encode Conformance in both directions plus in-process repeatability. */
  @Test
  void a_conformanceMatchesTheJdkBaseline() throws Exception {
    var rows =
        new StringBuilder(
            "corpus\tdecoded_bytes\tinput_sha256\tjdk_stored_bytes\tjdk_sha256\tcandidate_stored_bytes\tcandidate_sha256\tcandidate_decoded_by_jdk\tjdk_decoded_by_candidate\tcandidate_round_trip\tjdk_repeatable_x3\tcandidate_repeatable_x3\n");
    for (String corpus : CORPORA) {
      for (int size : CONFORMANCE_SIZES) {
        byte[] data = JlibdeflateLaunchProbe.corpus(corpus, size);
        byte[] jdk = jdkEncode(data);
        byte[] candidate = candidateEncode(data);
        boolean jdkRepeatable = true, candidateRepeatable = true;
        for (int repetition = 1; repetition < 3; repetition++) {
          jdkRepeatable &= Arrays.equals(jdk, jdkEncode(data));
          candidateRepeatable &= Arrays.equals(candidate, candidateEncode(data));
        }
        boolean candidateByJdk = Arrays.equals(data, jdkDecode(candidate, size));
        boolean jdkByCandidate = Arrays.equals(data, candidateDecode(jdk, size));
        boolean roundTrip = Arrays.equals(data, candidateDecode(candidate, size));
        assertTrue(candidateByJdk && jdkByCandidate && roundTrip, corpus + "/" + size);
        assertTrue(jdkRepeatable && candidateRepeatable, corpus + "/" + size);
        rows.append(
            String.join(
                    "\t",
                    corpus,
                    Integer.toString(size),
                    hash(data),
                    Integer.toString(jdk.length),
                    hash(jdk),
                    Integer.toString(candidate.length),
                    hash(candidate),
                    Boolean.toString(candidateByJdk),
                    Boolean.toString(jdkByCandidate),
                    Boolean.toString(roundTrip),
                    Boolean.toString(jdkRepeatable),
                    Boolean.toString(candidateRepeatable))
                + "\n");
      }
    }
    rows.append(concurrentRow());
    write("conformance.tsv", rows);
  }

  /**
   * Four workers with independent provider state must produce identical bytes and exact decodes,
   * establishing reentrancy under JBSA-CODEC-010 for the candidate.
   */
  private static String concurrentRow() throws Exception {
    byte[] data = JlibdeflateLaunchProbe.corpus("text-seed52", 1 << 20);
    byte[] expected = candidateEncode(data);
    ExecutorService workers = Executors.newFixedThreadPool(4);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int worker = 0; worker < 4; worker++)
        results.add(
            workers.submit(
                () -> {
                  boolean same = true;
                  for (int round = 0; round < 8; round++) {
                    byte[] encoded = candidateEncode(data);
                    same &= Arrays.equals(expected, encoded);
                    same &= Arrays.equals(data, candidateDecode(encoded, data.length));
                  }
                  return same;
                }));
      for (var result : results) assertTrue(result.get(), "concurrent candidate calls");
    } finally {
      workers.shutdownNow();
    }
    return "# concurrent\t4 workers x 8 rounds text-seed52 1048576: identical bytes and exact decodes\n";
  }

  /**
   * Malformed-input parity. Every case must fail with the same Failure Kind as the JDK baseline and
   * the whole-buffer candidate must publish nothing first. Diagnostic identifier parity is recorded
   * rather than asserted: jlibdeflate reports a truncated Adler-32 trailer with the same "Output
   * buffer too small" message as genuine over-expansion, so a bounded single call cannot separate
   * {@code codec.invalid-data} from {@code codec.size-mismatch} there.
   */
  @Test
  void b_malformedInputMatchesTheJdkBaseline() throws Exception {
    byte[] data = JlibdeflateLaunchProbe.corpus("text-seed52", 65536);
    byte[] stored = jdkEncode(data);
    var cases = new LinkedHashMap<String, Object[]>();
    cases.put("valid-control", new Object[] {stored, data.length});
    byte[] header = stored.clone();
    header[0] = 0;
    cases.put("header-invalid", new Object[] {header, data.length});
    cases.put("preset-dictionary", new Object[] {presetDictionary(stored), data.length});
    byte[] body = stored.clone();
    body[body.length / 2] ^= 0x55;
    cases.put("body-corrupt", new Object[] {body, data.length});
    byte[] adler = stored.clone();
    adler[adler.length - 1] ^= 1;
    cases.put("adler-mismatch", new Object[] {adler, data.length});
    cases.put(
        "truncated-trailer", new Object[] {Arrays.copyOf(stored, stored.length - 1), data.length});
    cases.put(
        "truncated-trailer-two",
        new Object[] {Arrays.copyOf(stored, stored.length - 2), data.length});
    cases.put(
        "truncated-trailer-three",
        new Object[] {Arrays.copyOf(stored, stored.length - 3), data.length});
    cases.put(
        "truncated-half", new Object[] {Arrays.copyOf(stored, stored.length / 2), data.length});
    cases.put(
        "trailing-bytes", new Object[] {Arrays.copyOf(stored, stored.length + 3), data.length});
    cases.put("declared-short-by-one", new Object[] {stored, data.length - 1});
    cases.put("declared-long-by-one", new Object[] {stored, data.length + 1});
    cases.put("declared-half", new Object[] {stored, data.length / 2});
    cases.put("empty-stored", new Object[] {new byte[0], data.length});
    cases.put("empty-stream-declared-one", new Object[] {jdkEncode(new byte[0]), 1});
    var rows =
        new StringBuilder(
            "case\tstored_bytes\tdeclared_decoded_bytes\tjdk_outcome\tcandidate_outcome\tcandidate_published_bytes\tfailure_kind_parity\tidentifier_parity\n");
    for (var entry : cases.entrySet()) {
      byte[] input = (byte[]) entry.getValue()[0];
      int declared = (int) entry.getValue()[1];
      String jdk = outcome(() -> jdkDecode(input, declared));
      long[] published = {0};
      String candidate =
          outcome(
              () -> {
                try (ResourceBudget budget = budget()) {
                  JlibdeflateZlib.decode(
                      source(input),
                      input.length,
                      declared,
                      (offset, bytes) -> published[0] += bytes.remaining(),
                      () -> {},
                      budget,
                      CONTEXT);
                }
                return null;
              });
      boolean kindParity = jdk.split("/")[0].equals(candidate.split("/")[0]);
      boolean identifierParity = jdk.equals(candidate);
      assertTrue(kindParity, entry.getKey() + ": jdk=" + jdk + " candidate=" + candidate);
      if (!candidate.equals("PASS")) assertEquals(0, published[0], entry.getKey());
      rows.append(
          String.join(
                  "\t",
                  entry.getKey(),
                  Integer.toString(input.length),
                  Integer.toString(declared),
                  jdk,
                  candidate,
                  Long.toString(published[0]),
                  Boolean.toString(kindParity),
                  Boolean.toString(identifierParity))
              + "\n");
    }
    rows.append(noRetryRow(body, data.length));
    write("malformed.tsv", rows);
  }

  /**
   * Invalid data after a successful candidate preflight must surface the candidate identity; any
   * JDK profile identity would reveal a forbidden retry through the fallback provider.
   */
  private static String noRetryRow(byte[] corrupt, int declared) throws Exception {
    try (ResourceBudget budget = budget()) {
      ArchiveException error =
          assertThrows(
              ArchiveException.class,
              () ->
                  JlibdeflateZlib.dispatchDecode(
                      source(corrupt),
                      corrupt.length,
                      declared,
                      (offset, bytes) -> fail("published corrupt output"),
                      () -> {},
                      budget,
                      CONTEXT));
      assertEquals(
          JlibdeflateZlib.CANDIDATE_PROFILE,
          error.diagnostics().getFirst().values().get("profile"));
      return "# no-retry\tdispatchDecode(body-corrupt) failed "
          + error.primaryFailure().kind()
          + "/"
          + error.diagnostics().getFirst().identifier()
          + " under "
          + JlibdeflateZlib.CANDIDATE_PROFILE
          + " without a JDK retry\n";
    }
  }

  /**
   * Accounts for libdeflate 1.25 versus the Reference Snapshot's 1.24 on the pinned oracle zlib
   * streams, which BSArch produced through its at-most-8-MiB libdeflate level-12 path.
   */
  @Test
  void c_referenceOracleStreamsAccountForTheLibdeflateVersion() throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    List<String> oracles =
        List.of(
            "docs/reviews/issue38-cv1/oracle/zlib.hex",
            "docs/reviews/issue39-cv1/oracle/zlib.hex",
            "docs/reviews/issue42-cv1/oracle/zlib.hex");
    byte[] repeated = new byte[1024];
    Arrays.fill(repeated, (byte) 'A');
    var cases = new LinkedHashMap<String, Object[]>();
    // Reference streams are the zlib records embedded in each pinned oracle archive.
    cases.put("A*1024", new Object[] {repeated, "78da73741c05a360148c540000a4780410"});
    cases.put(
        "000102ff",
        new Object[] {new byte[] {0, 1, 2, (byte) 0xff}, "78da010400fbff000102ff010a0103"});
    var rows =
        new StringBuilder(
            "case\treference_libdeflate_1.24_stream\tcandidate_libdeflate_1.25_stream\tjdk_stream\tcandidate_equals_reference\tjdk_equals_reference\toracle_archives\n");
    for (var entry : cases.entrySet()) {
      String reference = (String) entry.getValue()[1];
      for (String oracle : oracles)
        assertTrue(
            Files.readString(root.resolve(oracle), StandardCharsets.US_ASCII).contains(reference),
            oracle + " must embed the reference stream");
      byte[] input = (byte[]) entry.getValue()[0];
      String candidate = HexFormat.of().formatHex(candidateEncode(input));
      String jdk = HexFormat.of().formatHex(jdkEncode(input));
      rows.append(
          String.join(
                  "\t",
                  entry.getKey(),
                  reference,
                  candidate,
                  jdk,
                  Boolean.toString(candidate.equals(reference)),
                  Boolean.toString(jdk.equals(reference)),
                  String.join(",", oracles))
              + "\n");
    }
    write("oracle.tsv", rows);
  }

  /**
   * Measures codec-seam throughput, output size, heap allocation, credits and checkpoint gaps for
   * both providers, discarding one warmup round per case. Only the provider calls are timed; the
   * sinks copy into preallocated arrays and exact bytes are verified after each timing.
   */
  @Test
  void d_measuresThroughputSizeAndMemory() throws Exception {
    var rows =
        new StringBuilder(
            "provider\tcorpus\tdecoded_bytes\tstored_bytes\trepetition\twarmup\tencode_ms\tdecode_ms\tencode_mib_s\tdecode_mib_s\tencode_heap_allocated_bytes\tdecode_heap_allocated_bytes\tencode_heap_credit_bytes\tdecode_heap_credit_bytes\tencode_native_credit_bytes\tdecode_native_credit_bytes\tencode_max_checkpoint_gap_ms\tdecode_max_checkpoint_gap_ms\tinput_sha256\tstored_sha256\n");
    var summary =
        new StringBuilder(
            "corpus\tdecoded_bytes\tjdk_stored_bytes\tcandidate_stored_bytes\tcandidate_size_ratio\tjdk_encode_mib_s\tcandidate_encode_mib_s\tencode_speedup\tjdk_decode_mib_s\tcandidate_decode_mib_s\tdecode_speedup\tcandidate_encode_max_checkpoint_gap_ms\n");
    var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    for (String corpus : CORPORA) {
      for (int size : MEASURED_SIZES) {
        byte[] data = JlibdeflateLaunchProbe.corpus(corpus, size);
        var medians = new LinkedHashMap<String, double[]>();
        var stored = new LinkedHashMap<String, Integer>();
        double candidateGap = 0;
        for (String provider : List.of(JDK, CANDIDATE)) {
          byte[] encodedBuffer = new byte[(int) JlibdeflateZlib.storedBound(size)];
          byte[] decodedBuffer = new byte[size];
          byte[] first = null;
          double[] encodeRates = new double[REPETITIONS];
          double[] decodeRates = new double[REPETITIONS];
          for (int round = 0; round < WARMUPS + REPETITIONS; round++) {
            var encodeClock = new Checkpoints();
            int[] encodedLength = {0};
            JdkZlib.ByteSink encodeSink =
                (offset, bytes) -> {
                  int count = bytes.remaining();
                  bytes.get(encodedBuffer, Math.toIntExact(offset), count);
                  encodedLength[0] = Math.max(encodedLength[0], Math.toIntExact(offset) + count);
                };
            var channel = Channels.newChannel(new ByteArrayInputStream(data));
            long allocated = threads.getCurrentThreadAllocatedBytes();
            long start = System.nanoTime();
            try (ResourceBudget budget = budget()) {
              if (provider.equals(JDK)) {
                try (var lease =
                    budget.reserve(JdkZlib.ENCODE_HEAP_BYTES, JdkZlib.ENCODE_NATIVE_BYTES, 0, 0)) {
                  JdkZlib.encode(channel, size, encodeSink, encodeClock, CONTEXT);
                }
              } else {
                JlibdeflateZlib.encode(
                    source(data), size, encodeSink, encodeClock, budget, CONTEXT);
              }
            }
            long encodeNanos = System.nanoTime() - start;
            long encodeAllocated = threads.getCurrentThreadAllocatedBytes() - allocated;
            byte[] encoded = Arrays.copyOf(encodedBuffer, encodedLength[0]);
            if (first == null) first = encoded;
            else assertArrayEquals(first, encoded, provider + " repeatable bytes");

            var decodeClock = new Checkpoints();
            JdkZlib.ByteSink decodeSink =
                (offset, bytes) ->
                    bytes.get(decodedBuffer, Math.toIntExact(offset), bytes.remaining());
            Arrays.fill(decodedBuffer, (byte) 0);
            allocated = threads.getCurrentThreadAllocatedBytes();
            start = System.nanoTime();
            try (ResourceBudget budget = budget()) {
              if (provider.equals(JDK))
                jdkDecodeInto(encoded, size, decodeSink, decodeClock, budget);
              else
                JlibdeflateZlib.decode(
                    source(encoded),
                    encoded.length,
                    size,
                    decodeSink,
                    decodeClock,
                    budget,
                    CONTEXT);
            }
            long decodeNanos = System.nanoTime() - start;
            long decodeAllocated = threads.getCurrentThreadAllocatedBytes() - allocated;
            assertArrayEquals(data, decodedBuffer, provider + " exact decode");

            boolean warmup = round < WARMUPS;
            if (!warmup) {
              encodeRates[round - WARMUPS] = throughput(size, encodeNanos);
              decodeRates[round - WARMUPS] = throughput(size, decodeNanos);
              if (provider.equals(CANDIDATE))
                candidateGap = Math.max(candidateGap, encodeClock.maxGap / 1e6);
            }
            boolean jdk = provider.equals(JDK);
            rows.append(
                String.format(
                    Locale.ROOT,
                    "%s\t%s\t%d\t%d\t%d\t%b\t%.3f\t%.3f\t%.3f\t%.3f\t%d\t%d\t%d\t%d\t%d\t%d\t%.3f\t%.3f\t%s\t%s%n",
                    provider,
                    corpus,
                    size,
                    encoded.length,
                    round,
                    warmup,
                    encodeNanos / 1e6,
                    decodeNanos / 1e6,
                    throughput(size, encodeNanos),
                    throughput(size, decodeNanos),
                    encodeAllocated,
                    decodeAllocated,
                    jdk ? JdkZlib.ENCODE_HEAP_BYTES : JlibdeflateZlib.encodeHeapBytes(size),
                    jdk
                        ? JdkZlib.DECODE_HEAP_BYTES
                        : JlibdeflateZlib.decodeHeapBytes(encoded.length, size),
                    jdk ? JdkZlib.ENCODE_NATIVE_BYTES : JlibdeflateZlib.ENCODE_NATIVE_BYTES,
                    jdk ? JdkZlib.DECODE_NATIVE_BYTES : JlibdeflateZlib.DECODE_NATIVE_BYTES,
                    encodeClock.maxGap / 1e6,
                    decodeClock.maxGap / 1e6,
                    hash(data),
                    hash(encoded)));
          }
          medians.put(provider, new double[] {median(encodeRates), median(decodeRates)});
          stored.put(provider, first.length);
          if (provider.equals(CANDIDATE)) assertInsufficientCredit(data, first);
        }
        double[] jdk = medians.get(JDK), candidate = medians.get(CANDIDATE);
        summary.append(
            String.format(
                Locale.ROOT,
                "%s\t%d\t%d\t%d\t%.4f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f%n",
                corpus,
                size,
                stored.get(JDK),
                stored.get(CANDIDATE),
                stored.get(CANDIDATE) / (double) stored.get(JDK),
                jdk[0],
                candidate[0],
                candidate[0] / jdk[0],
                jdk[1],
                candidate[1],
                candidate[1] / jdk[1],
                candidateGap));
      }
    }
    write("measurements.tsv", rows);
    write("summary.tsv", summary);
  }

  /**
   * Measures committed native state per provider instance and cancellation credit return, then
   * checks that each declared native credit covers the observation.
   */
  @Test
  void e_nativeStateFitsTheDeclaredCredits() throws Exception {
    var system =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    JlibdeflateZlib.preflight("encode", CONTEXT);
    int instances = 32;
    var compressors = new ArrayList<com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor>();
    long before = system.getCommittedVirtualMemorySize();
    for (int index = 0; index < instances; index++)
      compressors.add(
          new com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor(JlibdeflateZlib.LEVEL));
    long compressor = (system.getCommittedVirtualMemorySize() - before) / instances;
    compressors.forEach(com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor::close);
    var decompressors = new ArrayList<com.fulcrumgenomics.jlibdeflate.LibdeflateDecompressor>();
    before = system.getCommittedVirtualMemorySize();
    for (int index = 0; index < instances; index++)
      decompressors.add(new com.fulcrumgenomics.jlibdeflate.LibdeflateDecompressor());
    long decompressor = (system.getCommittedVirtualMemorySize() - before) / instances;
    decompressors.forEach(com.fulcrumgenomics.jlibdeflate.LibdeflateDecompressor::close);
    assertTrue(compressor <= JlibdeflateZlib.ENCODE_NATIVE_BYTES, "compressor " + compressor);
    assertTrue(decompressor <= JlibdeflateZlib.DECODE_NATIVE_BYTES, "decompressor " + decompressor);
    assertCancellationReturnsCredits();
    write(
        "native-state.tsv",
        new StringBuilder(
                "provider_state\tinstances\tcommitted_bytes_per_instance\tdeclared_credit_bytes\tjdk_baseline_credit_bytes\n")
            .append(
                String.format(
                    Locale.ROOT,
                    "level-12-compressor\t%d\t%d\t%d\t%d%n",
                    instances,
                    compressor,
                    JlibdeflateZlib.ENCODE_NATIVE_BYTES,
                    JdkZlib.ENCODE_NATIVE_BYTES))
            .append(
                String.format(
                    Locale.ROOT,
                    "decompressor\t%d\t%d\t%d\t%d%n",
                    instances,
                    decompressor,
                    JlibdeflateZlib.DECODE_NATIVE_BYTES,
                    JdkZlib.DECODE_NATIVE_BYTES))
            .append(
                "# cancellation\tcheckpoint failures before and after the provider call returned every credit\n"));
  }

  /**
   * Observes the provider's own loading in fresh JVMs: extraction count and leftovers in an
   * isolated temporary directory, the caller-path override, the native-access warning, and
   * cross-process repeatability of the candidate bytes.
   */
  @Test
  void f_nativeLoadingInFreshProcesses() throws Exception {
    byte[] data = JlibdeflateLaunchProbe.corpus("text-seed52", 1 << 20);
    String expected = hash(candidateEncode(data));
    var rows =
        new StringBuilder(
            "probe\texit_status\tjlibdeflate_libraries_loaded\tloaded_from_override\ttemporary_files_left_after_exit\tnative_access_warning\tstored_sha256_matches_in_process\tdetail\n");
    for (String probe :
        List.of("default", "repeat", "no-native-access", "override-missing", "override-present")) {
      Path isolated = Files.createDirectories(temporary.resolve(probe + "-tmp"));
      Path override = Files.createDirectories(temporary.resolve(probe + "-override"));
      if (probe.equals("override-present")) {
        try (var dll =
            Objects.requireNonNull(
                Class.forName(
                        "com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor",
                        false,
                        getClass().getClassLoader())
                    .getResourceAsStream("/native/windows-x86_64/jlibdeflate.dll"))) {
          Files.copy(dll, override.resolve("jlibdeflate.dll"));
        }
      }
      var command = new ArrayList<String>();
      command.add(ProcessHandle.current().info().command().orElseThrow());
      if (!probe.equals("no-native-access")) command.add("--enable-native-access=ALL-UNNAMED");
      command.add("-Xlog:library=info");
      command.add("-Djava.io.tmpdir=" + isolated);
      if (probe.startsWith("override")) command.add("-Djlibdeflate.library.path=" + override);
      command.add("-cp");
      command.add(probeClassPath());
      command.add(JlibdeflateLaunchProbe.class.getName());
      command.add("text-seed52");
      command.add(Integer.toString(data.length));
      Process process = new ProcessBuilder(command).start();
      // Drain stderr concurrently so a verbose child cannot block on a full pipe.
      var stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
      String stdout = read(process.getInputStream());
      assertTrue(process.waitFor(120, TimeUnit.SECONDS), probe + " timed out");
      String errors = stderr.get();
      String all = stdout + errors;
      long loads =
          all.lines()
              .filter(line -> line.contains("Loaded library") && line.contains("jlibdeflate"))
              .count();
      boolean fromOverride =
          all.lines()
              .anyMatch(
                  line -> line.contains("Loaded library") && line.contains(override.toString()));
      long left;
      try (var files = Files.list(isolated)) {
        left =
            files.filter(path -> path.getFileName().toString().startsWith("jlibdeflate-")).count();
      }
      boolean warning = errors.contains("restricted method");
      String sha =
          stdout
              .lines()
              .filter(line -> line.startsWith("sha256="))
              .map(line -> line.substring(7))
              .findFirst()
              .orElse("");
      if (!probe.equals("override-missing")) {
        assertEquals(0, process.exitValue(), probe + ": " + errors);
        assertEquals(expected, sha, probe + " cross-process bytes");
      } else {
        assertNotEquals(0, process.exitValue(), "a missing caller-supplied DLL must fail loading");
      }
      String detail =
          probe.equals("override-missing")
              ? errors
                  .lines()
                  .filter(line -> line.contains("UnsatisfiedLinkError"))
                  .findFirst()
                  .orElse("")
                  .replace(temporary.toString(), "<temp>")
              : "";
      rows.append(
          String.join(
                  "\t",
                  probe,
                  Integer.toString(process.exitValue()),
                  Long.toString(loads),
                  Boolean.toString(fromOverride),
                  Long.toString(left),
                  Boolean.toString(warning),
                  Boolean.toString(expected.equals(sha)),
                  detail)
              + "\n");
    }
    write("native-loading.tsv", rows);
  }

  /**
   * Records packaging and notice facts about the pinned JAR and confirms that the candidate stays
   * out of production classpaths, the staged runtime and the unchanged codec-profile manifest.
   */
  @Test
  void g_packagingAndNotices() throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path jar =
        Path.of(
            Class.forName(
                    "com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor",
                    false,
                    getClass().getClassLoader())
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    var rows = new StringBuilder("fact\tvalue\n");
    rows.append("jar_sha256\t").append(hash(Files.readAllBytes(jar))).append('\n');
    try (JarFile file = new JarFile(jar.toFile())) {
      var manifest = file.getManifest().getMainAttributes();
      rows.append("automatic_module_name\t")
          .append(Objects.requireNonNullElse(manifest.getValue("Automatic-Module-Name"), "absent"))
          .append('\n');
      rows.append("module_descriptor\t")
          .append(file.getEntry("module-info.class") == null ? "absent" : "present")
          .append('\n');
      var natives = new ArrayList<String>();
      var notices = new ArrayList<String>();
      file.stream()
          .filter(entry -> !entry.isDirectory())
          .forEach(
              entry -> {
                String name = entry.getName();
                if (name.startsWith("native/")) natives.add(name);
                if (name.toLowerCase(Locale.ROOT).matches(".*(license|copying|notice).*"))
                  notices.add(name);
              });
      rows.append("native_payloads\t").append(String.join(",", natives)).append('\n');
      rows.append("license_or_notice_entries\t")
          .append(notices.isEmpty() ? "none" : String.join(",", notices))
          .append('\n');
    }
    String library = Files.readString(root.resolve("jbsa/gradle.lockfile"));
    String line =
        library
            .lines()
            .filter(entry -> entry.startsWith("com.fulcrumgenomics:jlibdeflate:"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        "com.fulcrumgenomics:jlibdeflate:0.1.0=testCompileClasspath,testRuntimeClasspath", line);
    rows.append("jbsa_lock\t").append(line).append('\n');
    for (String production :
        List.of(
            "jbsa-cli/gradle.lockfile",
            "jbsa-dist/gradle.lockfile",
            "build/windows-runtime/launch-policy.json")) {
      boolean present = Files.readString(root.resolve(production)).contains("jlibdeflate");
      assertFalse(present, production);
      rows.append("present_in:").append(production).append('\t').append(present).append('\n');
    }
    byte[] profile;
    try (var resource = JdkZlib.class.getResourceAsStream("/META-INF/jbsa-codec-profile.json")) {
      profile = Objects.requireNonNull(resource).readAllBytes();
    }
    String digest;
    try (var resource = JdkZlib.class.getResourceAsStream("/META-INF/jbsa-codec-profile.sha256")) {
      digest =
          new String(Objects.requireNonNull(resource).readAllBytes(), StandardCharsets.US_ASCII)
              .trim();
    }
    assertEquals(digest, hash(profile), "the baseline profile bytes must be unchanged");
    assertTrue(
        new String(profile, StandardCharsets.UTF_8).contains("\"jlibdeflate\":\"not-promoted\""));
    rows.append("baseline_profile\t")
        .append(JdkZlib.PROFILE)
        .append(' ')
        .append(digest)
        .append('\n');
    write("packaging.tsv", rows);
    write(
        "environment.txt",
        new StringBuilder()
            .append("timestamp=")
            .append(java.time.Instant.now())
            .append('\n')
            .append("java=")
            .append(System.getProperty("java.runtime.version"))
            .append('\n')
            .append("vm=")
            .append(System.getProperty("java.vm.name"))
            .append('\n')
            .append("vendor=")
            .append(System.getProperty("java.vendor"))
            .append('\n')
            .append("os=")
            .append(System.getProperty("os.name"))
            .append(' ')
            .append(System.getProperty("os.version"))
            .append('\n')
            .append("arch=")
            .append(System.getProperty("os.arch"))
            .append('\n')
            .append("processors=")
            .append(Runtime.getRuntime().availableProcessors())
            .append('\n')
            .append("max_heap_bytes=")
            .append(Runtime.getRuntime().maxMemory())
            .append('\n')
            .append("baseline_profile=")
            .append(JdkZlib.PROFILE)
            .append('\n')
            .append("baseline_profile_sha256=")
            .append(digest)
            .append('\n')
            .append("candidate=")
            .append(JlibdeflateZlib.CANDIDATE_PROFILE)
            .append(" jlibdeflate 0.1.0 / libdeflate 1.25 level ")
            .append(JlibdeflateZlib.LEVEL)
            .append('\n'));
  }

  /** Refuses one byte less heap credit before reading the source or touching the sink. */
  private static void assertInsufficientCredit(byte[] data, byte[] stored) throws Exception {
    long encodeHeap = JlibdeflateZlib.encodeHeapBytes(data.length);
    try (var budget =
        new ResourceBudget(ResourceLimits.standard(), CONTEXT, encodeHeap - 1, 64L << 20, 0)) {
      ArchiveException error =
          assertThrows(
              ArchiveException.class,
              () ->
                  JlibdeflateZlib.encode(
                      (offset, bytes) -> fail("unadmitted read"),
                      data.length,
                      (offset, bytes) -> fail("unadmitted write"),
                      () -> {},
                      budget,
                      CONTEXT));
      assertEquals(FailureKind.POLICY, error.primaryFailure().kind());
    }
    long decodeHeap = JlibdeflateZlib.decodeHeapBytes(stored.length, data.length);
    try (var budget =
        new ResourceBudget(ResourceLimits.standard(), CONTEXT, decodeHeap - 1, 64L << 20, 0)) {
      ArchiveException error =
          assertThrows(
              ArchiveException.class,
              () ->
                  JlibdeflateZlib.decode(
                      (offset, bytes) -> fail("unadmitted read"),
                      stored.length,
                      data.length,
                      (offset, bytes) -> fail("unadmitted write"),
                      () -> {},
                      budget,
                      CONTEXT));
      assertEquals(FailureKind.POLICY, error.primaryFailure().kind());
    }
  }

  /** Cancels at the pre-call and post-call checkpoints and then reserves the full ceiling again. */
  private static void assertCancellationReturnsCredits() throws Exception {
    byte[] data = JlibdeflateLaunchProbe.corpus("text-seed52", 1 << 20);
    byte[] stored = candidateEncode(data);
    for (int cancelAt : new int[] {1, 2}) {
      for (boolean encoding : new boolean[] {true, false}) {
        IOException cancelled = new IOException("qualification cancellation");
        int[] checks = {0};
        JdkZlib.Checkpoint checkpoint =
            () -> {
              if (++checks[0] == cancelAt) throw cancelled;
            };
        try (ResourceBudget budget = budget()) {
          assertSame(
              cancelled,
              assertThrows(
                  IOException.class,
                  () -> {
                    if (encoding)
                      JlibdeflateZlib.encode(
                          source(data), data.length, (o, b) -> {}, checkpoint, budget, CONTEXT);
                    else
                      JlibdeflateZlib.decode(
                          source(stored),
                          stored.length,
                          data.length,
                          (o, b) -> {},
                          checkpoint,
                          budget,
                          CONTEXT);
                  }));
          try (var returned = budget.reserve(256L << 20, 64L << 20, 0, 0)) {
            assertNotNull(returned);
          }
        }
      }
    }
  }

  /** Builds the child class path from the test classes and the pinned provider JAR only. */
  private String probeClassPath() throws Exception {
    Path classes =
        Path.of(
            JlibdeflateLaunchProbe.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    Path provider =
        Path.of(
            Class.forName(
                    "com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor",
                    false,
                    getClass().getClassLoader())
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    return classes + java.io.File.pathSeparator + provider;
  }

  /** Reports a decode outcome as PASS or Failure Kind/identifier for parity comparison. */
  private static String outcome(Callable<?> call) {
    try {
      call.call();
      return "PASS";
    } catch (ArchiveException error) {
      return error.primaryFailure().kind() + "/" + error.diagnostics().getFirst().identifier();
    } catch (Exception error) {
      return "UNEXPECTED/" + error.getClass().getName();
    }
  }

  /**
   * Sets FDICT and repairs FCHECK so only the preset-dictionary request makes the header invalid.
   */
  private static byte[] presetDictionary(byte[] stored) {
    byte[] copy = stored.clone();
    int flags = (copy[1] & 0xC0) | 0x20;
    int check = (31 - (((copy[0] & 0xFF) * 256 + flags) % 31)) % 31;
    copy[1] = (byte) (flags | check);
    return copy;
  }

  private static byte[] jdkEncode(byte[] data) throws IOException {
    var encoded = new ByteArrayOutputStream();
    JdkZlib.encode(
        Channels.newChannel(new ByteArrayInputStream(data)),
        data.length,
        sink(encoded),
        () -> {},
        CONTEXT);
    return encoded.toByteArray();
  }

  private static byte[] jdkDecode(byte[] stored, int size) throws IOException {
    var decoded = new ByteArrayOutputStream(Math.max(0, size));
    try (ResourceBudget budget = budget()) {
      jdkDecodeInto(stored, size, sink(decoded), () -> {}, budget);
    }
    return decoded.toByteArray();
  }

  /** Drives the production streaming decoder through the same loop the candidate dispatch uses. */
  private static void jdkDecodeInto(
      byte[] stored,
      long size,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget)
      throws IOException {
    JlibdeflateZlib.jdkDecode(
        source(stored), stored.length, size, sink, checkpoint, budget, CONTEXT);
  }

  private static byte[] candidateEncode(byte[] data) throws IOException {
    var encoded = new ByteArrayOutputStream();
    try (ResourceBudget budget = budget()) {
      JlibdeflateZlib.encode(source(data), data.length, sink(encoded), () -> {}, budget, CONTEXT);
    }
    return encoded.toByteArray();
  }

  private static byte[] candidateDecode(byte[] stored, int size) throws IOException {
    var decoded = new ByteArrayOutputStream(Math.max(0, size));
    try (ResourceBudget budget = budget()) {
      JlibdeflateZlib.decode(
          source(stored), stored.length, size, sink(decoded), () -> {}, budget, CONTEXT);
    }
    return decoded.toByteArray();
  }

  private static ResourceBudget budget() {
    return new ResourceBudget(ResourceLimits.standard(), CONTEXT, 256L << 20, 64L << 20, 0);
  }

  private static JdkZlib.ByteSource source(byte[] data) {
    return (offset, bytes) -> bytes.put(data, Math.toIntExact(offset), bytes.remaining());
  }

  private static JdkZlib.ByteSink sink(ByteArrayOutputStream output) {
    return (offset, bytes) -> {
      assertEquals(output.size(), offset);
      byte[] chunk = new byte[bytes.remaining()];
      bytes.get(chunk);
      output.write(chunk);
    };
  }

  private static String read(java.io.InputStream stream) {
    try {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
  }

  private static double median(double[] values) {
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted.length % 2 == 1
        ? sorted[sorted.length / 2]
        : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
  }

  private static double throughput(long bytes, long nanos) {
    return nanos == 0 ? 0 : bytes / 1048576.0 / (nanos / 1e9);
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void write(String name, CharSequence content) throws IOException {
    Path directory =
        Files.createDirectories(
            Path.of(System.getProperty("jbsa.reactor.root"))
                .resolve("docs/development/evidence/issue52-jlibdeflate"));
    Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8);
  }

  /** Records the largest elapsed time between observations, including callbacks and pauses. */
  private static final class Checkpoints implements JdkZlib.Checkpoint {
    private long previous;
    private long maxGap;

    /** Records elapsed time between consecutive observations. */
    @Override
    public void check() {
      long now = System.nanoTime();
      if (previous != 0) maxGap = Math.max(maxGap, now - previous);
      previous = now;
    }
  }
}
