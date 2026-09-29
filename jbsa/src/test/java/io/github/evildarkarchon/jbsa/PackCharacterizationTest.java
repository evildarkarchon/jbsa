package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Characterizes observable pack admission, progress, and target-preflight behavior through the
 * public API only, ahead of the Pack Pipeline refactor (#68). Assertions that an approved change
 * will deliberately flip carry a comment naming that change ID (D5, D6, D9, Q10, Q14).
 *
 * <p>Admission order is pinned with "ladders": each rung repairs only the rule that fired on the
 * previous rung, so every step proves one rule's precedence over all of the rules still broken.
 */
@EnabledOnOs(OS.WINDOWS)
class PackCharacterizationTest {
  private static final ArchiveEncoding BSA_67 = selector(0x67, null, false);
  private static final ArchiveEncoding FO4_GNRL = selector(1, Ba2Subtype.GNRL, false);
  private static final ArchiveEncoding FO4_DX10 = selector(1, Ba2Subtype.DX10, false);
  private static final ArchiveEncoding SF_GNRL_RAW = selector(3, Ba2Subtype.GNRL, true);

  /** A structurally unsafe name that every family's shared source planner rejects. */
  private static final String UNSAFE_NAME = "meshes\\bad:name.nif";

  @TempDir Path temporary;

  /** Counts every payload factory invocation made by the current test's sources. */
  private final AtomicInteger opens = new AtomicInteger();

  /** TES3 admission order, from the first rule checked to the post-plan wire limit. */
  @Test
  void tes3AdmissionOrder() throws Exception {
    Path target = temporary.resolve("tes3.bsa");
    var allRulesBroken =
        options(
            PackOptions.Compression.FAMILY_DEFAULT,
            flags(0),
            Map.of(new NormalizedNameIdentity("a"), PackOptions.Compression.STORED));
    var noEntryCodec = options(PackOptions.Compression.FAMILY_DEFAULT, flags(0), Map.of());
    var admitted =
        options(PackOptions.Compression.FAMILY_DEFAULT, FlagSelection.AUTOMATIC, Map.of());

    assertRejected(
        request(target, ArchiveFamily.TES3_BSA, BSA_67, allRulesBroken, source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "tes3.entry-compression-inapplicable");
    assertRejected(
        request(target, ArchiveFamily.TES3_BSA, BSA_67, noEntryCodec, source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "archive.unsupported-encoding");
    assertRejected(
        request(
            target,
            ArchiveFamily.TES3_BSA,
            ArchiveEncoding.tes3(),
            noEntryCodec,
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "tes3.flags-inapplicable");
    // D5: the shared source planner emits pack.invalid-encode-name for every family.
    assertRejected(
        request(
            target,
            ArchiveFamily.TES3_BSA,
            ArchiveEncoding.tes3(),
            admitted,
            source(UNSAFE_NAME, 1)),
        FailureKind.POLICY,
        "pack.invalid-encode-name");
    assertRejected(
        request(target, ArchiveFamily.TES3_BSA, ArchiveEncoding.tes3(), admitted),
        FailureKind.POLICY,
        "tes3.empty-entry-set");
    // The u32 wire check runs during splitting, before the decoded-size limit is charged.
    assertRejected(
        withLimits(
            request(
                target,
                ArchiveFamily.TES3_BSA,
                ArchiveEncoding.tes3(),
                admitted,
                source("a", 0x1_0000_0000L)),
            decodedLimit(1)),
        FailureKind.POLICY,
        "tes3.wire-limit");
  }

  /** Versioned-BSA admission order, through source planning to the empty entry set. */
  @Test
  void bsaAdmissionOrder() throws Exception {
    Path target = temporary.resolve("tes4.bsa");
    var unmatched = Map.of(new NormalizedNameIdentity("x\\y.txt"), PackOptions.Compression.STORED);
    var badEntryCodec =
        Map.of(new NormalizedNameIdentity("x\\y.txt"), PackOptions.Compression.LZ4_RAW);

    assertRejected(
        request(
            target,
            ArchiveFamily.TES4_BSA,
            selector(0x68, null, false),
            options(PackOptions.Compression.LZ4_FRAME, flags(0), badEntryCodec),
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "archive.unsupported-encoding");
    assertRejected(
        request(
            target,
            ArchiveFamily.TES4_BSA,
            BSA_67,
            options(PackOptions.Compression.LZ4_FRAME, flags(0), badEntryCodec),
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "bsa.unsupported-codec");
    assertRejected(
        request(
            target,
            ArchiveFamily.TES4_BSA,
            BSA_67,
            options(PackOptions.Compression.STORED, flags(0), badEntryCodec),
            source(UNSAFE_NAME, 1)),
        FailureKind.POLICY,
        "bsa.invalid-archive-flags");
    assertRejected(
        request(
            target,
            ArchiveFamily.TES4_BSA,
            BSA_67,
            options(PackOptions.Compression.STORED, FlagSelection.AUTOMATIC, badEntryCodec),
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "bsa.unsupported-entry-codec");
    var stored = options(PackOptions.Compression.STORED, FlagSelection.AUTOMATIC, unmatched);
    // D5: the shared source planner emits pack.invalid-encode-name for every family.
    assertRejected(
        request(target, ArchiveFamily.TES4_BSA, BSA_67, stored, source(UNSAFE_NAME, 1)),
        FailureKind.POLICY,
        "pack.invalid-encode-name");
    // A BSA name needs a folder; the per-entry name rule precedes the unmatched-override check.
    assertRejected(
        request(target, ArchiveFamily.TES4_BSA, BSA_67, stored, source("a.txt", 1)),
        FailureKind.POLICY,
        "bsa.invalid-encode-name");
    // The second name rule: a folder longer than 254 bytes cannot fit its length-prefixed record.
    assertRejected(
        request(
            target, ArchiveFamily.TES4_BSA, BSA_67, stored, source("f".repeat(255) + "\\a.txt", 1)),
        FailureKind.POLICY,
        "bsa.invalid-encode-name");
    assertRejected(
        request(target, ArchiveFamily.TES4_BSA, BSA_67, stored),
        FailureKind.POLICY,
        "pack.unmatched-entry-compression");
    assertRejected(
        request(
            target,
            ArchiveFamily.TES4_BSA,
            BSA_67,
            options(PackOptions.Compression.STORED, FlagSelection.AUTOMATIC, Map.of())),
        FailureKind.POLICY,
        "bsa.empty-entry-set");
  }

  /** Fallout 4 General BA2 admission order, through source planning to the empty entry set. */
  @Test
  void ba2AdmissionOrder() throws Exception {
    Path target = temporary.resolve("general.ba2");
    var unmatched = Map.of(new NormalizedNameIdentity("x\\y.txt"), PackOptions.Compression.ZLIB);
    var badEntryCodec =
        Map.of(new NormalizedNameIdentity("x\\y.txt"), PackOptions.Compression.LZ4_FRAME);

    assertRejected(
        request(
            target,
            ArchiveFamily.FO4_GENERAL_BA2,
            selector(2, Ba2Subtype.GNRL, false),
            options(PackOptions.Compression.LZ4_FRAME, flags(0), badEntryCodec),
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "archive.unsupported-encoding");
    assertRejected(
        request(
            target,
            ArchiveFamily.FO4_GENERAL_BA2,
            FO4_GNRL,
            options(PackOptions.Compression.LZ4_FRAME, flags(0), badEntryCodec),
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "ba2.flags-inapplicable");
    assertRejected(
        request(
            target,
            ArchiveFamily.FO4_GENERAL_BA2,
            FO4_GNRL,
            options(PackOptions.Compression.LZ4_FRAME, FlagSelection.AUTOMATIC, badEntryCodec),
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "ba2.unsupported-codec");
    assertRejected(
        request(
            target,
            ArchiveFamily.FO4_GENERAL_BA2,
            FO4_GNRL,
            options(PackOptions.Compression.STORED, FlagSelection.AUTOMATIC, badEntryCodec),
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "ba2.unsupported-entry-codec");
    var stored = options(PackOptions.Compression.STORED, FlagSelection.AUTOMATIC, unmatched);
    // D5: the shared source planner emits pack.invalid-encode-name for every family.
    assertRejected(
        request(target, ArchiveFamily.FO4_GENERAL_BA2, FO4_GNRL, stored, source(UNSAFE_NAME, 1)),
        FailureKind.POLICY,
        "pack.invalid-encode-name");
    // A BA2 name needs a directory; the per-entry name rule precedes the unmatched-override check.
    assertRejected(
        request(target, ArchiveFamily.FO4_GENERAL_BA2, FO4_GNRL, stored, source("a.txt", 1)),
        FailureKind.POLICY,
        "ba2.invalid-encode-name");
    // The planner admits windows-1252 bytes, but the BA2 wire name must still be ASCII.
    assertRejected(
        request(target, ArchiveFamily.FO4_GENERAL_BA2, FO4_GNRL, stored, source("x/é.txt", 1)),
        FailureKind.POLICY,
        "ba2.invalid-encode-name");
    assertRejected(
        request(target, ArchiveFamily.FO4_GENERAL_BA2, FO4_GNRL, stored),
        FailureKind.POLICY,
        "pack.unmatched-entry-compression");
    assertRejected(
        request(
            target,
            ArchiveFamily.FO4_GENERAL_BA2,
            FO4_GNRL,
            options(PackOptions.Compression.STORED, FlagSelection.AUTOMATIC, Map.of())),
        FailureKind.POLICY,
        "ba2.empty-entry-set");
  }

  /** Starfield's mixed raw-LZ4/zlib rule precedes even the encoding and flag checks. */
  @Test
  void ba2MixedCodecsPrecedeEncodingAndFlags() throws Exception {
    assertRejected(
        request(
            temporary.resolve("mixed.ba2"),
            ArchiveFamily.STARFIELD_GENERAL_BA2,
            selector(1, Ba2Subtype.DX10, false),
            options(
                PackOptions.Compression.LZ4_RAW,
                flags(0),
                Map.of(new NormalizedNameIdentity("x\\y.txt"), PackOptions.Compression.ZLIB)),
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "ba2.mixed-compressed-codecs");
    // The mix is also detected between a global zlib choice and a raw-LZ4 override.
    assertRejected(
        request(
            temporary.resolve("mixed-override.ba2"),
            ArchiveFamily.STARFIELD_GENERAL_BA2,
            SF_GNRL_RAW,
            options(
                PackOptions.Compression.ZLIB,
                FlagSelection.AUTOMATIC,
                Map.of(new NormalizedNameIdentity("x\\y.txt"), PackOptions.Compression.LZ4_RAW)),
            source("x/y.txt", 1)),
        FailureKind.UNSUPPORTED,
        "ba2.mixed-compressed-codecs");
  }

  /** DX10 stored and non-DDS rules, including their order against neighbouring BA2 rules. */
  @Test
  void ddsAdmissionOrder() throws Exception {
    Path target = temporary.resolve("textures.ba2");
    // A bad entry codec is reported before the DX10 stored rule sees the stored global choice.
    assertRejected(
        request(
            target,
            ArchiveFamily.FO4_DDS_BA2,
            FO4_DX10,
            options(
                PackOptions.Compression.STORED,
                FlagSelection.AUTOMATIC,
                Map.of(new NormalizedNameIdentity("x\\y.dds"), PackOptions.Compression.LZ4_FRAME)),
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "ba2.unsupported-entry-codec");
    assertRejected(
        request(
            target,
            ArchiveFamily.FO4_DDS_BA2,
            FO4_DX10,
            options(PackOptions.Compression.STORED, FlagSelection.AUTOMATIC, Map.of()),
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "dx10.stored-encode");
    // A stored per-entry override is rejected the same way, before any source is planned.
    assertRejected(
        request(
            target,
            ArchiveFamily.FO4_DDS_BA2,
            FO4_DX10,
            options(
                PackOptions.Compression.FAMILY_DEFAULT,
                FlagSelection.AUTOMATIC,
                Map.of(new NormalizedNameIdentity("x\\y.dds"), PackOptions.Compression.STORED)),
            source(UNSAFE_NAME, 1)),
        FailureKind.UNSUPPORTED,
        "dx10.stored-encode");
    var admitted =
        options(PackOptions.Compression.FAMILY_DEFAULT, FlagSelection.AUTOMATIC, Map.of());
    // The non-DDS extension rule precedes the directory rule for the same entry.
    assertRejected(
        request(target, ArchiveFamily.FO4_DDS_BA2, FO4_DX10, admitted, source("a.txt", 1)),
        FailureKind.UNSUPPORTED,
        "dds.non-dds-entry");
    assertRejected(
        request(target, ArchiveFamily.FO4_DDS_BA2, FO4_DX10, admitted, source("a.dds", 1)),
        FailureKind.POLICY,
        "ba2.invalid-encode-name");
    assertRejected(
        request(target, ArchiveFamily.FO4_DDS_BA2, FO4_DX10, admitted),
        FailureKind.POLICY,
        "ba2.empty-entry-set");
  }

  /** Every family rejects an encoding selector tuple it cannot write. */
  @ParameterizedTest
  @EnumSource(ArchiveFamily.class)
  void everyFamilyRejectsAnUnsupportedEncoding(ArchiveFamily family) throws Exception {
    assertRejected(
        request(
            temporary.resolve("encoding.bin"),
            family,
            selector(0x99, null, false),
            options(PackOptions.Compression.FAMILY_DEFAULT, FlagSelection.AUTOMATIC, Map.of()),
            source("x/y.dds", 1)),
        FailureKind.UNSUPPORTED,
        "archive.unsupported-encoding");
  }

  /** The SSE family codec is LZ4 frames, so a global zlib choice is an unsupported codec there. */
  @Test
  void sseRejectsZlib() throws Exception {
    assertRejected(
        request(
            temporary.resolve("sse.bsa"),
            ArchiveFamily.SSE_BSA,
            selector(0x69, null, false),
            options(PackOptions.Compression.ZLIB, FlagSelection.AUTOMATIC, Map.of()),
            source("x/y.txt", 1)),
        FailureKind.UNSUPPORTED,
        "bsa.unsupported-codec");
  }

  /**
   * ACP932 maps some non-ASCII scalars to ASCII bytes, the only public way to give two distinct
   * normalized identities one BA2 wire name. Hosts on any other ANSI code page skip this pin.
   */
  @Test
  void ba2RejectsDuplicateWireNamesUnderActiveAnsi() throws Throwable {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        PackCharacterizationTest.class.getModule().isNativeAccessEnabled());
    try (var arena = java.lang.foreign.Arena.ofConfined()) {
      var lookup = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", arena);
      var function =
          java.lang.foreign.Linker.nativeLinker()
              .downcallHandle(
                  lookup.find("GetACP").orElseThrow(),
                  java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT));
      org.junit.jupiter.api.Assumptions.assumeTrue(
          (int) function.invokeExact() == 932,
          "Requires the real Windows ACP932 environment; the test never changes the machine ACP");
    }
    var standard =
        request(
            temporary.resolve("alias.ba2"),
            ArchiveFamily.FO4_GENERAL_BA2,
            FO4_GNRL,
            options(PackOptions.Compression.STORED, FlagSelection.AUTOMATIC, Map.of()),
            source("Data/~.bin", 0),
            source("Data/‾.bin", 0));
    assertRejected(
        new PackRequest(
            standard.destination(),
            standard.family(),
            standard.encoding(),
            Optional.of(CompatibilityProfile.BSARCH_1_0_V1),
            standard.sources(),
            standard.targetPolicy(),
            standard.diagnosticPolicy(),
            standard.resourceLimits(),
            standard.workerSelection(),
            standard.options(),
            standard.ddsTarget()),
        FailureKind.POLICY,
        "ba2.duplicate-encode-name");
  }

  /** TES3 has no compressed wire form, so it rejects a global compressed choice. */
  @ParameterizedTest
  @EnumSource(
      value = PackOptions.Compression.class,
      names = {"ZLIB", "LZ4_RAW", "LZ4_FRAME"})
  void tes3RejectsGlobalCompression(PackOptions.Compression compression) throws Exception {
    // D6: TES3 rejects a global ZLIB or LZ4 choice with UNSUPPORTED tes3.unsupported-codec.
    assertRejected(
        request(
            temporary.resolve("hinted.bsa"),
            ArchiveFamily.TES3_BSA,
            ArchiveEncoding.tes3(),
            options(compression, FlagSelection.AUTOMATIC, Map.of()),
            source("a", 3)),
        FailureKind.UNSUPPORTED,
        "tes3.unsupported-codec");
  }

  /**
   * TES3 charges the decoded-size limit in Logical Plan Order, not its hash-sorted order.
   * Single-character names hash to c (0x6000000C) before b (0x80000018) before a (0x80000030), the
   * reverse of plan order here.
   */
  @Test
  void tes3DecodedLimitIsChargedInLogicalPlanOrder() throws Exception {
    var failure =
        assertRejected(
            withLimits(
                request(
                    temporary.resolve("limit.bsa"),
                    ArchiveFamily.TES3_BSA,
                    ArchiveEncoding.tes3(),
                    options(PackOptions.Compression.STORED, FlagSelection.AUTOMATIC, Map.of()),
                    source("a", 5),
                    source("b", 1),
                    source("c", 1)),
                decodedLimit(5)),
            FailureKind.POLICY,
            "operation.resource-limit");
    var diagnostic = failure.diagnostics().getFirst();
    assertEquals("maxDecodedBytes", diagnostic.values().get("field"));
    assertEquals("5", diagnostic.values().get("ceiling"));
    // Q14: charged in Logical Plan Order (a, b, c), the limit trips at b and observes 6, not 7.
    assertEquals("6", diagnostic.values().get("observed"));
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertEquals(OptionalLong.empty(), failure.primaryFailure().ordinal());
  }

  /** Records a stabilized (zlib) BSA pack's progress, interleaved with its source reads. */
  @Test
  void bsaStabilizationRunsInProcessingProgressPhase() throws Exception {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    var request =
        sequential(
            request(
                temporary.resolve("progress.bsa"),
                ArchiveFamily.TES4_BSA,
                BSA_67,
                options(PackOptions.Compression.ZLIB, FlagSelection.AUTOMATIC, Map.of()),
                logged("x/a.txt", 3, events),
                logged("x/b.txt", 2, events)));
    BethesdaArchives.standard().pack(request, recording(events));
    // Q10 (#71): BSA stabilization runs in PROCESSING, so both opens follow that phase's entry.
    assertEquals(processingStabilizedProgress(List.of("open x/a.txt", "open x/b.txt")), events);
  }

  /** Records a stabilized BA2 pack's progress, interleaved with its source reads. */
  @Test
  void ba2StabilizationRunsInPreflightProgressPhase() throws Exception {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    var request =
        sequential(
            request(
                temporary.resolve("progress.ba2"),
                ArchiveFamily.FO4_GENERAL_BA2,
                FO4_GNRL,
                options(PackOptions.Compression.STORED, FlagSelection.AUTOMATIC, Map.of()),
                logged("x/a.txt", 3, events),
                logged("x/b.txt", 2, events)));
    BethesdaArchives.standard().pack(request, recording(events));
    // Q10 (#72): BA2 stabilization will run in PROCESSING, moving both opens after its entry.
    assertEquals(stabilizedProgress(List.of("open x/a.txt", "open x/b.txt")), events);
  }

  /** A stored BSA pack with sharing enabled detects a target conflict before any source read. */
  @Test
  void bsaStoredSharingPreflightsTargets() throws Exception {
    Path target = Files.write(temporary.resolve("taken.bsa"), new byte[] {7});
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(
                            target,
                            ArchiveFamily.TES4_BSA,
                            BSA_67,
                            new PackOptions(
                                List.of(),
                                PackOptions.Compression.STORED,
                                true,
                                new PackOptions.Splitting.FamilyDefault(),
                                FlagSelection.AUTOMATIC,
                                FlagSelection.AUTOMATIC),
                            source("x/a.txt", 1)),
                        OperationControl.standard()));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(
        Optional.of("extraction.target-exists"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    // D9: preflight runs with sharing enabled, failing before any source factory opens.
    assertEquals(0, opens.get());
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(target));
  }

  /** With sharing disabled the same stored BSA pack is preflighted before any source read. */
  @Test
  void bsaStoredWithoutSharingPreflightsTargets() throws Exception {
    Path target = Files.write(temporary.resolve("taken.bsa"), new byte[] {7});
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(
                            target,
                            ArchiveFamily.TES4_BSA,
                            BSA_67,
                            new PackOptions(
                                List.of(),
                                PackOptions.Compression.STORED,
                                false,
                                new PackOptions.Splitting.FamilyDefault(),
                                FlagSelection.AUTOMATIC,
                                FlagSelection.AUTOMATIC),
                            source("x/a.txt", 1)),
                        OperationControl.standard()));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(
        Optional.of("extraction.target-exists"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertEquals(0, opens.get());
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(target));
  }

  /**
   * Asserts a pack is rejected with the given Primary Failure before any source factory opens and
   * before any output exists, and returns the failure for further inspection.
   */
  private ArchiveException assertRejected(
      PackRequest request, FailureKind kind, String identifier) {
    int before = opens.get();
    var failure =
        assertThrows(
            ArchiveException.class,
            () -> BethesdaArchives.standard().pack(request, OperationControl.standard()),
            identifier);
    assertEquals(
        Optional.of(identifier), failure.primaryFailure().diagnosticIdentifier(), "identifier");
    assertEquals(kind, failure.kind(), identifier);
    assertEquals(before, opens.get(), identifier + " opened a source");
    assertFalse(Files.exists(request.destination()), identifier + " left an output");
    return failure;
  }

  /** Builds a request with standard policy; DDS families receive the mandatory PC target. */
  private static PackRequest request(
      Path target,
      ArchiveFamily family,
      ArchiveEncoding encoding,
      PackOptions options,
      PackSource... sources) {
    boolean dds = family == ArchiveFamily.FO4_DDS_BA2 || family == ArchiveFamily.STARFIELD_DDS_BA2;
    var standard =
        PackRequest.standard(
            target,
            family,
            encoding,
            List.of(sources),
            dds ? Optional.of(DdsTarget.PC) : Optional.empty());
    return new PackRequest(
        standard.destination(),
        standard.family(),
        standard.encoding(),
        standard.compatibilityProfile(),
        standard.sources(),
        standard.targetPolicy(),
        standard.diagnosticPolicy(),
        standard.resourceLimits(),
        standard.workerSelection(),
        options,
        standard.ddsTarget());
  }

  /** Replaces only the resource limits of a request. */
  private static PackRequest withLimits(PackRequest request, ResourceLimits limits) {
    return new PackRequest(
        request.destination(),
        request.family(),
        request.encoding(),
        request.compatibilityProfile(),
        request.sources(),
        request.targetPolicy(),
        request.diagnosticPolicy(),
        limits,
        request.workerSelection(),
        request.options(),
        request.ddsTarget());
  }

  /** Pins one worker so source reads and progress interleave deterministically. */
  private static PackRequest sequential(PackRequest request) {
    return new PackRequest(
        request.destination(),
        request.family(),
        request.encoding(),
        request.compatibilityProfile(),
        request.sources(),
        request.targetPolicy(),
        request.diagnosticPolicy(),
        request.resourceLimits(),
        new WorkerSelection.UpTo(1),
        request.options(),
        request.ddsTarget());
  }

  /** Standard limits with only the decoded-byte ceiling lowered. */
  private static ResourceLimits decodedLimit(long bytes) {
    var standard = ResourceLimits.standard();
    return new ResourceLimits(
        standard.maxEntries(),
        standard.maxMetadataBytes(),
        bytes,
        standard.maxScratchBytes(),
        standard.maxOutputs(),
        standard.maxDiagnostics(),
        standard.maxSecondaryFailures());
  }

  /** Family-default splitting with sharing enabled and automatic file flags. */
  private static PackOptions options(
      PackOptions.Compression compression,
      FlagSelection archiveFlags,
      Map<NormalizedNameIdentity, PackOptions.Compression> entryCompression) {
    return new PackOptions(
        List.of(),
        compression,
        true,
        new PackOptions.Splitting.FamilyDefault(),
        archiveFlags,
        FlagSelection.AUTOMATIC,
        entryCompression);
  }

  /**
   * The Progress Snapshots and source opens of one sequential two-entry stabilized pack (sources of
   * 3 and 2 bytes) that still stabilizes during PREFLIGHT. Stabilization itself emits no snapshot,
   * so its phase shows only through where the "open" events land: between PREFLIGHT's last advance
   * and its completion.
   */
  private static List<String> stabilizedProgress(List<String> stabilization) {
    var events =
        new ArrayList<>(
            List.of("PREFLIGHT ENTRIES 0", "PREFLIGHT ENTRIES 1", "PREFLIGHT ENTRIES 2"));
    events.addAll(stabilization);
    events.addAll(
        List.of(
            "PREFLIGHT ENTRIES 2/2",
            "PROCESSING ENTRIES 0",
            "PROCESSING BYTES 0",
            "PROCESSING BYTES 3",
            "PROCESSING ENTRIES 1",
            "PROCESSING BYTES 5",
            "PROCESSING ENTRIES 2",
            "PROCESSING ENTRIES 2/2",
            "PROCESSING BYTES 5/5",
            "PUBLISHING ARTIFACTS 0",
            "PUBLISHING ARTIFACTS 1",
            "PUBLISHING ARTIFACTS 1/1",
            "CLEANUP ARTIFACTS 0",
            "CLEANUP ARTIFACTS 1/1"));
    return events;
  }

  /**
   * The Progress Snapshots and source opens of one sequential two-entry pack whose stabilization
   * runs in PROCESSING (sources of 3 and 2 bytes). Entering the phase reports both of its metrics
   * at zero before stabilization reads any source; publication then continues in that phase.
   */
  private static List<String> processingStabilizedProgress(List<String> stabilization) {
    var events =
        new ArrayList<>(
            List.of(
                "PREFLIGHT ENTRIES 0",
                "PREFLIGHT ENTRIES 1",
                "PREFLIGHT ENTRIES 2",
                "PREFLIGHT ENTRIES 2/2",
                "PROCESSING ENTRIES 0",
                "PROCESSING BYTES 0"));
    events.addAll(stabilization);
    events.addAll(
        List.of(
            "PROCESSING BYTES 3",
            "PROCESSING ENTRIES 1",
            "PROCESSING BYTES 5",
            "PROCESSING ENTRIES 2",
            "PROCESSING ENTRIES 2/2",
            "PROCESSING BYTES 5/5",
            "PUBLISHING ARTIFACTS 0",
            "PUBLISHING ARTIFACTS 1",
            "PUBLISHING ARTIFACTS 1/1",
            "CLEANUP ARTIFACTS 0",
            "CLEANUP ARTIFACTS 1/1"));
    return events;
  }

  /** An explicit flag group, which some families reject outright and others validate. */
  private static FlagSelection flags(long value) {
    return new FlagSelection.Explicit(value);
  }

  /** Builds a wire selector tuple; raw LZ4 Starfield selectors carry compression method 3. */
  private static ArchiveEncoding selector(long version, Ba2Subtype subtype, boolean rawLz4) {
    return new ArchiveEncoding(
        Optional.of(new WireVersion(version)),
        Optional.ofNullable(subtype),
        rawLz4 ? OptionalLong.of(3) : OptionalLong.empty());
  }

  /** A generated source of the declared length whose factory invocations are counted. */
  private PackSource source(String name, long length) {
    return new PackSource.GeneratedEntry(
        name,
        length,
        () -> {
          opens.incrementAndGet();
          return Channels.newChannel(new ByteArrayInputStream(new byte[(int) length]));
        });
  }

  /** A counted source that also logs each factory invocation into a shared event list. */
  private PackSource logged(String name, int length, List<String> events) {
    return new PackSource.GeneratedEntry(
        name,
        length,
        () -> {
          opens.incrementAndGet();
          events.add("open " + name);
          return Channels.newChannel(new ByteArrayInputStream(new byte[length]));
        });
  }

  /** An operation control that logs every Progress Snapshot into a shared event list. */
  private static OperationControl recording(List<String> events) {
    return new OperationControl(
        snapshot ->
            events.add(
                snapshot.phase()
                    + " "
                    + snapshot.metric()
                    + " "
                    + snapshot.completed()
                    + (snapshot.total().isPresent() ? "/" + snapshot.total().getAsLong() : "")),
        () -> false);
  }
}
