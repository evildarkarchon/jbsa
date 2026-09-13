package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Public TES3 encode examples independently calculated from the wire specification. */
@EnabledOnOs(OS.WINDOWS)
class Tes3PackTest {
  @TempDir Path temporary;

  /** Checks a complete archive against independently calculated little-endian wire bytes. */
  @Test
  void packsCanonicalStoredBytes() throws Exception {
    Path target = temporary.resolve("one.bsa");
    var request =
        PackRequest.standard(
            target,
            ArchiveFamily.TES3_BSA,
            ArchiveEncoding.tes3(),
            List.of(generated("A", new byte[] {1, 2, 3})),
            Optional.empty());
    var report = BethesdaArchives.standard().pack(request, OperationControl.standard());
    // For "a", the empty low half is zero and ROR(0x61, 1) is 0x80000030.
    assertArrayEquals(
        HexFormat.of()
            .parseHex("000100000e0000000100000003000000000000000000000061000000000030000080010203"),
        Files.readAllBytes(target));
    assertEquals(ArtifactState.PUBLISHED, report.artifacts().getFirst().state());
    assertEquals(
        List.of(new OperationReport.ArchivePart(target.toAbsolutePath(), 37, 1)),
        report.archiveParts());
  }

  /**
   * Exercises directory-relative names, later replacement, and existing-archive source expansion.
   */
  @Test
  void expandsDirectoryAndArchiveSourcesAndAppliesLaterOverlay() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("input/meshes"));
    Files.write(root.resolve("A.nif"), new byte[] {1});
    Files.write(root.resolve("b.nif"), new byte[] {2});
    Path first = temporary.resolve("first.bsa");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                first,
                ArchiveFamily.TES3_BSA,
                ArchiveEncoding.tes3(),
                List.of(
                    new PackSource.DetectedPath(root.getParent()),
                    generated("MESHES/a.NIF", new byte[] {9, 8})),
                Optional.empty()),
            OperationControl.standard());
    Path second = temporary.resolve("second.bsa");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                second,
                ArchiveFamily.TES3_BSA,
                ArchiveEncoding.tes3(),
                List.of(new PackSource.DetectedPath(first)),
                Optional.empty()),
            OperationControl.standard());
    try (var archive = BethesdaArchives.standard().open(second, OpenOptions.standard())) {
      assertEquals(2, archive.entryCount());
      for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
        var entry = archive.entry(ordinal);
        try (var content = entry.openContent()) {
          byte[] actual = Channels.newInputStream(content).readAllBytes();
          assertArrayEquals(
              entry.metadata().displayName().equals("meshes\\a.nif")
                  ? new byte[] {9, 8}
                  : new byte[] {2},
              actual);
        }
      }
    }
  }

  /** Split membership follows hash order and compression never alters TES3's stored payloads. */
  @Test
  void splitsInCanonicalOrderAndIgnoresCompressionHints() throws Exception {
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            false,
            new PackOptions.Splitting.UpToBytes(1),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    Path target = temporary.resolve("split");
    var request =
        request(
            target,
            List.of(generated("b", new byte[] {2}), generated("a", new byte[] {1})),
            options,
            ResourceLimits.standard());
    var report = BethesdaArchives.standard().pack(request, OperationControl.standard());
    assertEquals(2, report.artifacts().size());
    assertTrue(Files.exists(temporary.resolve("split2")));
    // Both low halves are zero; b's high half 0x80000018 sorts before a's 0x80000030.
    assertEquals(
        "b", BethesdaArchives.standard().inspect(target).entries().getFirst().displayName());
    assertEquals(
        "a",
        BethesdaArchives.standard()
            .inspect(temporary.resolve("split2"))
            .entries()
            .getFirst()
            .displayName());
  }

  /**
   * Rejects unrepresentable fields and metadata admission before source factories or output
   * effects.
   */
  @Test
  void rejectsWireAndSemanticLimitsBeforeInvokingSources() throws Exception {
    var never =
        new PackSource.GeneratedEntry(
            "a",
            0x1_0000_0000L,
            () -> {
              throw new AssertionError("Preflight must reject before source open");
            });
    assertEquals(
        FailureKind.POLICY,
        assertThrows(
                ArchiveException.class,
                () ->
                    BethesdaArchives.standard()
                        .pack(
                            request(
                                temporary.resolve("wide.bsa"),
                                List.of(never),
                                PackOptions.standard(),
                                ResourceLimits.standard()),
                            OperationControl.standard()))
            .kind());
    var limits = new ResourceLimits(3, 1, 3, 100, 1, 20, 3);
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(
                            temporary.resolve("metadata.bsa"),
                            List.of(generated("a", new byte[] {1})),
                            PackOptions.standard(),
                            limits),
                        OperationControl.standard()));
    assertEquals("maxMetadataBytes", failure.diagnostics().getFirst().values().get("field"));
    assertFalse(Files.exists(temporary.resolve("metadata.bsa")));
  }

  /** Sharing confirms bytes and emits only unique payload spans, without orphan trailing bytes. */
  @Test
  void sharingUsesExactBytesAndLeavesNoTrailingPayload() throws Exception {
    Path target = temporary.resolve("shared.bsa");
    BethesdaArchives.standard()
        .pack(
            request(
                target,
                List.of(
                    generated("a", new byte[] {1, 2, 3}),
                    generated("b", new byte[] {1, 2, 3}),
                    generated("c", new byte[] {1, 2, 4})),
                PackOptions.standard(),
                ResourceLimits.standard()),
            OperationControl.standard());
    var inspection = BethesdaArchives.standard().inspect(target);
    var entries =
        inspection.entries().stream()
            .collect(java.util.stream.Collectors.toMap(EntryMetadata::displayName, e -> e));
    assertEquals(
        ((EntryMetadata.Tes3) entries.get("a").facts()).dataOffset(),
        ((EntryMetadata.Tes3) entries.get("b").facts()).dataOffset());
    assertNotEquals(
        ((EntryMetadata.Tes3) entries.get("a").facts()).dataOffset(),
        ((EntryMetadata.Tes3) entries.get("c").facts()).dataOffset());
    assertEquals(84, Files.size(target)); // 12 header + 60 tables + 6 names + 6 unique payload.
    assertFalse(
        inspection.assessment().diagnostics().stream()
            .anyMatch(d -> d.identifier().equals("tes3.trailing-data")));
  }

  /** Existing output files are omitted from a recursive source but forbidden as explicit inputs. */
  @Test
  void excludesOutputSiblingsFromDiscoveryAndRejectsExplicitOutputSources() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("tree"));
    Files.write(root.resolve("a"), new byte[] {7});
    Path target = root.resolve("archive.bsa");
    Files.write(target, new byte[] {99});
    Files.write(root.resolve("archive2.bsa"), new byte[] {99});
    var standard =
        request(
            target,
            List.of(new PackSource.DetectedPath(root)),
            PackOptions.standard(),
            ResourceLimits.standard());
    var replace =
        new PackRequest(
            target,
            standard.family(),
            standard.encoding(),
            Optional.empty(),
            standard.sources(),
            TargetPolicy.REPLACE,
            standard.diagnosticPolicy(),
            standard.resourceLimits(),
            standard.workerSelection(),
            standard.options(),
            Optional.empty());
    BethesdaArchives.standard().pack(replace, OperationControl.standard());
    assertEquals(1, BethesdaArchives.standard().inspect(target).entries().size());
    assertEquals(
        FailureKind.POLICY,
        assertThrows(
                ArchiveException.class,
                () ->
                    BethesdaArchives.standard()
                        .pack(
                            request(
                                target,
                                List.of(new PackSource.DetectedPath(target)),
                                PackOptions.standard(),
                                ResourceLimits.standard()),
                            OperationControl.standard()))
            .kind());
  }

  /** A short generated source closes its transferred channel and leaves no staging or output. */
  @Test
  void malformedGeneratedLengthClosesItsChannelAndPublishesNothing() throws Exception {
    var closed = new java.util.concurrent.atomic.AtomicBoolean();
    var source =
        new PackSource.GeneratedEntry(
            "a",
            2,
            () ->
                new java.nio.channels.ReadableByteChannel() {
                  private boolean emitted;

                  public int read(java.nio.ByteBuffer buffer) {
                    if (emitted) return -1;
                    emitted = true;
                    buffer.put((byte) 1);
                    return 1;
                  }

                  public boolean isOpen() {
                    return !closed.get();
                  }

                  public void close() {
                    closed.set(true);
                  }
                });
    Path target = temporary.resolve("failed.bsa");
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(
                            target,
                            List.of(source),
                            PackOptions.standard(),
                            ResourceLimits.standard()),
                        OperationControl.standard()));
    assertEquals(FailureKind.SOURCE, failure.kind());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertTrue(closed.get());
    assertFalse(Files.exists(target));
    try (var paths = Files.list(temporary)) {
      assertEquals(0, paths.count());
    }
  }

  /** A full name-hash collision preserves distinct identities and sorts by canonical bytes. */
  @Test
  void hashCollisionsKeepDistinctNamesAndUseCanonicalByteTieOrder() throws Exception {
    Path target = temporary.resolve("collision.bsa");
    // Swapping first-half bytes 0 and 4 preserves their XOR contribution; the second half is equal.
    BethesdaArchives.standard()
        .pack(
            request(
                target,
                List.of(
                    generated("b000a0zzzzzz", new byte[] {2}),
                    generated("a000b0zzzzzz", new byte[] {1})),
                PackOptions.standard(),
                ResourceLimits.standard()),
            OperationControl.standard());
    var entries = BethesdaArchives.standard().inspect(target).entries();
    assertEquals("a000b0zzzzzz", entries.getFirst().displayName());
    assertEquals("b000a0zzzzzz", entries.getLast().displayName());
    assertEquals(
        ((EntryMetadata.Tes3) entries.getFirst().facts()).nameHash(),
        ((EntryMetadata.Tes3) entries.getLast().facts()).nameHash());
  }

  /** Exercises disk-backed stabilization and the combined peak of scratch and staged output. */
  @Test
  void sharesSpilledPayloadAndHonorsPeakScratchLimit() throws Exception {
    byte[] payload = new byte[131_073];
    java.util.Arrays.fill(payload, (byte) 0x42);
    var sources = List.<PackSource>of(generated("a", payload), generated("b", payload));
    Path target = temporary.resolve("spilled.bsa");
    BethesdaArchives.standard()
        .pack(
            request(target, sources, PackOptions.standard(), ResourceLimits.standard()),
            OperationControl.standard());
    assertEquals(56 + payload.length, Files.size(target));
    var limits = new ResourceLimits(5, 1000, 300000, payload.length, 5, 10, 3);
    Path failed = temporary.resolve("scratch-limit.bsa");
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(failed, sources, PackOptions.standard(), limits),
                        OperationControl.standard()));
    assertEquals("maxScratchBytes", failure.diagnostics().getFirst().values().get("field"));
    assertFalse(Files.exists(failed));
    try (var files = Files.list(temporary)) {
      assertEquals(List.of(target), files.toList());
    }
  }

  /** The default decoder preserves Windows-1252 text without inventing a canonical hash mapping. */
  @Test
  void defaultNameEncodingRetainsUnqualifiedNonAsciiWireNames() throws Exception {
    Path source = temporary.resolve("ansi.bsa");
    // The complete name is byte 0x80, which means EURO SIGN in the normative Windows-1252 default.
    Files.write(
        source,
        HexFormat.of()
            .parseHex("000100000e0000000100000000000000000000000000000080000000000000000000"));
    assertEquals(
        "€", BethesdaArchives.standard().inspect(source).entries().getFirst().displayName());
  }

  /**
   * An isolated embedded consumer cannot silently substitute defaults for unavailable active ANSI.
   */
  @Test
  void activeAnsiProfileFailsClosedWithoutNativeAccess() throws Exception {
    Path archive = temporary.resolve("profile.bsa");
    Files.write(
        archive,
        HexFormat.of()
            .parseHex("000100000e0000000100000000000000000000000000000061000000000030000080"));
    Path probe = temporary.resolve("ProfileProbe.java");
    Files.writeString(
        probe,
        """
        import io.github.evildarkarchon.jbsa.*;
        import java.nio.file.*;
        import java.util.*;
        class ProfileProbe {
          public static void main(String[] args) throws Exception {
            try {
              BethesdaArchives.standard().inspect(Path.of(args[0]), new OpenOptions(
                  Optional.of(CompatibilityProfile.BSARCH_1_0_V1), ResourceLimits.standard(), Optional.empty()));
              System.out.print("unexpected-success");
            } catch (ArchiveException failure) { System.out.print(failure.kind()); }
          }
        }
        """);
    String classes =
        Path.of(BethesdaArchives.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "--class-path",
                classes,
                probe.toString(),
                archive.toString())
            .redirectErrorStream(true)
            .start();
    assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(
        "CAPABILITY",
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
  }

  /** A source-close failure remains secondary to the earlier read failure. */
  @Test
  void sourceCloseFailureIsStructuredBehindTheReadFailure() throws Exception {
    var source =
        new PackSource.GeneratedEntry(
            "a",
            1,
            () ->
                new java.nio.channels.ReadableByteChannel() {
                  public int read(java.nio.ByteBuffer bytes) throws java.io.IOException {
                    throw new java.io.IOException("read");
                  }

                  public boolean isOpen() {
                    return true;
                  }

                  public void close() throws java.io.IOException {
                    throw new java.io.IOException("close");
                  }
                });
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(
                            temporary.resolve("close-failed.bsa"),
                            List.of(source),
                            PackOptions.standard(),
                            ResourceLimits.standard()),
                        OperationControl.standard()));
    assertEquals("read", failure.getCause().getMessage());
    assertEquals(1, failure.secondaryFailures().size());
    assertEquals(OperationPhase.CLEANUP, failure.secondaryFailures().getFirst().phase());
    assertEquals(
        "close", failure.secondaryFailures().getFirst().cause().orElseThrow().getMessage());
  }

  /** Parsed source metadata remains charged after its entries are replaced by an overlay. */
  @Test
  void metadataLimitCountsEveryArchiveSourceEvenWhenOverlaid() throws Exception {
    byte[] bytes =
        HexFormat.of()
            .parseHex("000100000e0000000100000000000000000000000000000061000000000030000080");
    Path first = Files.write(temporary.resolve("first.bsa"), bytes);
    Path second = Files.write(temporary.resolve("second.bsa"), bytes);
    var limits = new ResourceLimits(5, 40, 1000, 1000, 5, 10, 3);
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(
                            temporary.resolve("result.bsa"),
                            List.of(
                                new PackSource.DetectedPath(first),
                                new PackSource.DetectedPath(second)),
                            PackOptions.standard(),
                            limits),
                        OperationControl.standard()));
    assertEquals("maxMetadataBytes", failure.diagnostics().getFirst().values().get("field"));
  }

  /** Uses explicit choices while retaining the safe public request policy defaults. */
  private static PackRequest request(
      Path target, List<PackSource> sources, PackOptions options, ResourceLimits limits) {
    return new PackRequest(
        target,
        ArchiveFamily.TES3_BSA,
        ArchiveEncoding.tes3(),
        Optional.empty(),
        sources,
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        limits,
        new WorkerSelection.UpTo(1),
        options,
        Optional.empty());
  }

  /** Transfers a fresh channel for each invocation of a repeatable synthetic source. */
  private static PackSource.GeneratedEntry generated(String name, byte[] bytes) {
    return new PackSource.GeneratedEntry(
        name, bytes.length, () -> Channels.newChannel(new ByteArrayInputStream(bytes)));
  }
}
