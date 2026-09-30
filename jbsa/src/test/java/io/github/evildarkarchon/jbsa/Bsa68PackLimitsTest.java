package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public metadata ceilings include names and decoded-size fields outside the index. */
final class Bsa68PackLimitsTest {
  @TempDir Path directory;

  /** A 12-byte embedded name needs 13 framing bytes, plus four for compressed decoded size. */
  @Test
  void chargesOutputFramingAtTheExactMetadataCeiling() throws Exception {
    for (boolean compressed : new boolean[] {false, true}) {
      long metadata = 82 + 13 + (compressed ? 4 : 0);
      var source =
          new PackSource.GeneratedEntry(
              "meshes/a.nif",
              1,
              () -> Channels.newChannel(new ByteArrayInputStream(new byte[] {7})));
      Path rejected = directory.resolve("rejected-" + compressed + ".bsa");
      assertEquals(
          FailureKind.POLICY,
          assertThrows(
                  ArchiveException.class,
                  () ->
                      BethesdaArchives.standard()
                          .pack(
                              request(rejected, source, metadata - 1, true, compressed),
                              OperationControl.standard()))
              .kind());
      assertFalse(Files.exists(rejected));
      Path accepted = directory.resolve("accepted-" + compressed + ".bsa");
      BethesdaArchives.standard()
          .pack(request(accepted, source, metadata, true, compressed), OperationControl.standard());
      assertTrue(Files.isRegularFile(accepted));
    }
  }

  /** Repacking combines the source embedded metadata with the destination's ordinary index. */
  @Test
  void includesParsedEmbeddedNamesInTheRepackLedger() throws Exception {
    Path source = directory.resolve("source.bsa");
    BethesdaArchives.standard()
        .pack(
            request(
                source,
                new PackSource.GeneratedEntry(
                    "meshes/a.nif",
                    1,
                    () -> Channels.newChannel(new ByteArrayInputStream(new byte[] {7}))),
                95,
                true,
                false),
            OperationControl.standard());
    Path rejected = directory.resolve("rejected.bsa");
    assertEquals(
        FailureKind.POLICY,
        assertThrows(
                ArchiveException.class,
                () ->
                    BethesdaArchives.standard()
                        .pack(
                            request(
                                rejected, new PackSource.DetectedPath(source), 176, false, false),
                            OperationControl.standard()))
            .kind());
    assertFalse(Files.exists(rejected));
    Path accepted = directory.resolve("accepted.bsa");
    BethesdaArchives.standard()
        .pack(
            request(accepted, new PackSource.DetectedPath(source), 177, false, false),
            OperationControl.standard());
    assertTrue(Files.isRegularFile(accepted));
  }

  /** Sets only the metadata ceiling and requested framing on the standard public pack model. */
  private static PackRequest request(
      Path target, PackSource source, long metadata, boolean embedded, boolean compressed) {
    var limits = ResourceLimits.standard();
    return new PackRequest(
        target,
        ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA,
        new ArchiveEncoding(
            Optional.of(new WireVersion(0x68)), Optional.empty(), OptionalLong.empty()),
        Optional.empty(),
        List.of(source),
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        new ResourceLimits(
            limits.maxEntries(),
            metadata,
            limits.maxDecodedBytes(),
            limits.maxScratchBytes(),
            limits.maxOutputs(),
            limits.maxDiagnostics(),
            limits.maxSecondaryFailures()),
        WorkerSelection.AUTOMATIC,
        new PackOptions(
            List.of(),
            compressed ? PackOptions.Compression.ZLIB : PackOptions.Compression.STORED,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            embedded ? new FlagSelection.Explicit(0x103) : FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC),
        Optional.empty());
  }
}
