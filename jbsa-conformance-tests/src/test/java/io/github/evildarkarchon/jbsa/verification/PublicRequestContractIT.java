package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises immutable caller requests independently of archive processing. */
@Tag("contract")
final class PublicRequestContractIT {
  /** Verifies the published profile remains tied to the normative canonical payload. */
  @Test
  void compatibilityProfileRetainsItsPublishedDigestAndCompleteOrderedBundle() throws Exception {
    CompatibilityProfile profile = CompatibilityProfile.BSARCH_1_0_V1;
    assertEquals("bsarch-1.0/v1", profile.identifier());
    assertEquals(1L, profile.revision());
    Path root = Path.of(System.getProperty("jbsa.reactor.root", "."));
    String specification =
        Files.readString(root.resolve("docs/spec/compatibility-profiles.md")).replace("\r\n", "\n");
    String marker = "<!-- profile-payload-start -->\n";
    String payload =
        specification.substring(
            specification.indexOf(marker) + marker.length(),
            specification.indexOf("<!-- profile-payload-end -->"));
    String digest =
        HexFormat.of()
            .withUpperCase()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
    assertEquals(digest, profile.contentDigest());
    assertEquals(13, profile.deviations().size());
    assertEquals("BSARCH-1.0-V1-CLI-REPEATED-VALUE", profile.deviations().getFirst());
    assertEquals("BSARCH-1.0-V1-SF3-ZLIB-FALLBACK", profile.deviations().getLast());
    assertThrows(UnsupportedOperationException.class, () -> profile.deviations().clear());
  }

  /**
   * Verifies request defaults, retained overlay order, and family-specific encode-target contracts.
   */
  @Test
  void requestsOwnSourcesAndRequireDdsTargetsOnlyForDdsFamilies() {
    ArrayList<PackSource> sources =
        new ArrayList<>(List.of(new PackSource.DetectedPath(Path.of("data"))));
    PackRequest request =
        PackRequest.standard(
            Path.of("out.bsa"),
            ArchiveFamily.TES3_BSA,
            ArchiveEncoding.tes3(),
            sources,
            Optional.empty());
    sources.clear();
    assertEquals(1, request.sources().size());
    assertThrows(UnsupportedOperationException.class, () -> request.sources().clear());
    assertEquals(TargetPolicy.FAIL, request.targetPolicy());
    assertEquals(ResourceLimits.standard(), request.resourceLimits());
    ArchiveEncoding dds =
        new ArchiveEncoding(
            Optional.of(new WireVersion(1)), Optional.of(Ba2Subtype.DX10), OptionalLong.empty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PackRequest.standard(
                Path.of("out.ba2"), ArchiveFamily.FO4_DDS_BA2, dds, List.of(), Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PackRequest.standard(
                Path.of("out.bsa"),
                ArchiveFamily.TES3_BSA,
                ArchiveEncoding.tes3(),
                List.of(),
                Optional.of(DdsTarget.PC)));
    PackRequest ddsRequest =
        PackRequest.standard(
            Path.of("out.ba2"),
            ArchiveFamily.FO4_DDS_BA2,
            dds,
            List.of(),
            Optional.of(DdsTarget.XBOX));
    assertEquals(Optional.of(DdsTarget.XBOX), ddsRequest.ddsTarget());
    for (ArchiveFamily family : ArchiveFamily.values()) {
      boolean requiresTarget =
          family == ArchiveFamily.FO4_DDS_BA2 || family == ArchiveFamily.STARFIELD_DDS_BA2;
      Optional<DdsTarget> accepted = requiresTarget ? Optional.of(DdsTarget.PC) : Optional.empty();
      Optional<DdsTarget> rejected = requiresTarget ? Optional.empty() : Optional.of(DdsTarget.PC);
      assertDoesNotThrow(
          () -> PackRequest.standard(Path.of("out"), family, dds, List.of(), accepted));
      assertThrows(
          IllegalArgumentException.class,
          () -> PackRequest.standard(Path.of("out"), family, dds, List.of(), rejected));
    }
    ExtractRequest extraction = ExtractRequest.standard(Path.of("in.bsa"), Path.of("out"));
    assertEquals(TargetPolicy.FAIL, extraction.targetPolicy());
    assertEquals(ResourceLimits.standard(), extraction.resourceLimits());
    assertEquals(Optional.empty(), extraction.compatibilityProfile());
    assertEquals(EntrySelection.ALL, extraction.entries());
  }

  /** Verifies copied filters, long split targets, and independent literal BSA flag overrides. */
  @Test
  void packChoicesCopyFiltersAndKeepAutomaticFlagsDistinctFromExplicitZero() {
    ArrayList<String> masks = new ArrayList<>(List.of("*.dds"));
    PackOptions choices =
        new PackOptions(
            masks,
            PackOptions.Compression.FAMILY_DEFAULT,
            true,
            new PackOptions.Splitting.UpToBytes(3_000_000_000L),
            new FlagSelection.Automatic(),
            new FlagSelection.Explicit(0));
    masks.clear();
    assertEquals(List.of("*.dds"), choices.inclusionMasks());
    assertThrows(UnsupportedOperationException.class, () -> choices.inclusionMasks().clear());
    assertInstanceOf(FlagSelection.Automatic.class, choices.archiveFlags());
    assertEquals(new FlagSelection.Explicit(0), choices.fileFlags());
    assertThrows(IllegalArgumentException.class, () -> new FlagSelection.Explicit(0x1_0000_0000L));
    assertThrows(IllegalArgumentException.class, () -> new FlagSelection.Explicit(-1));
    assertThrows(IllegalArgumentException.class, () -> new PackOptions.Splitting.UpToBytes(-1));
    PackOptions legacy =
        new PackOptions(
            List.of(),
            PackOptions.Compression.FAMILY_DEFAULT,
            true,
            new PackOptions.Splitting.LegacyPerEntry(),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PackRequest(
                Path.of("out"),
                ArchiveFamily.TES3_BSA,
                ArchiveEncoding.tes3(),
                Optional.empty(),
                List.of(),
                TargetPolicy.FAIL,
                DiagnosticPolicy.standard(),
                ResourceLimits.standard(),
                WorkerSelection.AUTOMATIC,
                legacy,
                Optional.empty()));
    assertDoesNotThrow(
        () ->
            new PackRequest(
                Path.of("out"),
                ArchiveFamily.TES3_BSA,
                ArchiveEncoding.tes3(),
                Optional.of(CompatibilityProfile.BSARCH_1_0_V1),
                List.of(),
                TargetPolicy.FAIL,
                DiagnosticPolicy.standard(),
                ResourceLimits.standard(),
                WorkerSelection.AUTOMATIC,
                legacy,
                Optional.empty()));
  }

  /**
   * Verifies lazy generated capabilities and immutable selections without allocating declared
   * payloads.
   */
  @Test
  void sourceValuesRetainExactNamesAndGeneratedInputsReopenAsChannels() throws Exception {
    PackSource.NamedFile named =
        new PackSource.NamedFile("Textures/Exact.DDS", Path.of("asset.dds"));
    assertEquals("Textures/Exact.DDS", named.name());
    AtomicLong opens = new AtomicLong();
    PackSource.GeneratedEntry generated =
        new PackSource.GeneratedEntry(
            "large.bin",
            3_000_000_000L,
            () -> {
              opens.incrementAndGet();
              return Channels.newChannel(new ByteArrayInputStream(new byte[] {42}));
            });
    assertEquals(3_000_000_000L, generated.length());
    assertEquals(0L, opens.get());
    try (var first = generated.contentFactory().open();
        var second = generated.contentFactory().open()) {
      assertNotSame(first, second);
      ByteBuffer buffer = ByteBuffer.allocate(1);
      assertEquals(1, first.read(buffer));
      assertEquals(42, buffer.get(0));
      assertEquals(1, second.read(buffer.clear()));
    }
    assertEquals(2L, opens.get());
    assertThrows(
        IllegalArgumentException.class,
        () -> new PackSource.GeneratedEntry("bad", -1, generated.contentFactory()));
    ArrayList<Long> ordinals = new ArrayList<>(List.of(3_000_000_000L));
    EntrySelection.Ordinals selected = new EntrySelection.Ordinals(ordinals);
    ordinals.clear();
    assertEquals(List.of(3_000_000_000L), selected.ordinals());
    assertThrows(UnsupportedOperationException.class, () -> selected.ordinals().clear());
    assertThrows(IllegalArgumentException.class, () -> new EntrySelection.Ordinals(List.of(-1L)));
  }

  /** Verifies environment-independent open defaults and positive long-valued worker ceilings. */
  @Test
  void openingDefaultsAreExplicitAndWorkerCeilingsAdmitLargeValues() {
    OpenOptions options = OpenOptions.standard();
    assertEquals(Optional.empty(), options.compatibilityProfile());
    assertEquals(ResourceLimits.standard(), options.resourceLimits());
    assertEquals(Optional.empty(), options.ddsTarget());
    assertEquals(3_000_000_000L, new WorkerSelection.UpTo(3_000_000_000L).workers());
    assertThrows(IllegalArgumentException.class, () -> new WorkerSelection.UpTo(0));
    assertThrows(IllegalArgumentException.class, () -> new WorkerSelection.UpTo(-1));
  }

  /** Verifies literal specification defaults and constructor enforcement of retention ceilings. */
  @Test
  void resourceLimitsUseTheNormativeCeilingsAndRejectInvalidRetention() {
    ResourceLimits limits = ResourceLimits.standard();
    assertEquals(1_000_000L, limits.maxEntries());
    assertEquals(1_073_741_824L, limits.maxMetadataBytes());
    assertEquals(1_099_511_627_776L, limits.maxDecodedBytes());
    assertEquals(274_877_906_944L, limits.maxScratchBytes());
    assertEquals(1_000_000L, limits.maxOutputs());
    assertEquals(4_096L, limits.maxDiagnostics());
    assertEquals(256L, limits.maxSecondaryFailures());
    assertThrows(IllegalArgumentException.class, () -> new ResourceLimits(0, 0, 0, 0, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ResourceLimits(-1, 0, 0, 0, 0, 1, 0));
    assertDoesNotThrow(() -> new ResourceLimits(0, 0, 0, 0, 0, 1, 0));
  }
}
