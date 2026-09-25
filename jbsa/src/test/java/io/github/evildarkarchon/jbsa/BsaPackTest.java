package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Public canonical Oblivion packing behavior and independent wire expectations. */
@EnabledOnOs(OS.WINDOWS)
class BsaPackTest {
  @TempDir Path temporary;

  /** Caller channel bugs retain operation attribution regardless of the selected wire codec. */
  @Test
  void doesNotAttributeCallerChannelBugsToTheCodecProvider() throws Exception {
    for (var codec : List.of(PackOptions.Compression.STORED, PackOptions.Compression.ZLIB)) {
      var source =
          new PackSource.GeneratedEntry(
              "meshes/a.nif",
              1,
              () ->
                  new java.nio.channels.ReadableByteChannel() {
                    /**
                     * Simulates a caller implementation fault independently of the requested
                     * compression.
                     */
                    @Override
                    public int read(ByteBuffer bytes) {
                      throw new IllegalStateException("caller channel");
                    }

                    /** Keeps the fake channel available until the deliberate read fault. */
                    @Override
                    public boolean isOpen() {
                      return true;
                    }

                    /** Accepts cleanup without resources or additional failures. */
                    @Override
                    public void close() {
                      /* No backing resources in this deliberately failing source. */
                    }
                  });
      var options =
          new PackOptions(
              List.of(),
              codec,
              false,
              new PackOptions.Splitting.UpToBytes(0),
              FlagSelection.AUTOMATIC,
              FlagSelection.AUTOMATIC);
      ArchiveException failure =
          assertThrows(
              ArchiveException.class,
              () ->
                  BethesdaArchives.standard()
                      .pack(
                          request(temporary.resolve(codec + ".bsa"), options, source),
                          OperationControl.standard()));
      assertEquals(FailureKind.INTERNAL, failure.kind());
      assertEquals(
          "operation.internal-failure",
          failure.primaryFailure().diagnosticIdentifier().orElseThrow());
    }
  }

  /** Stored overrides replace a global zlib choice and bind to the surviving normalized overlay. */
  @Test
  void appliesStoredOverrideToSurvivingOverlay() throws Exception {
    Path target = temporary.resolve("overlay-compression.bsa");
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            true,
            new PackOptions.Splitting.FamilyDefault(),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC,
            Map.of(new NormalizedNameIdentity("meshes\\a.nif"), PackOptions.Compression.STORED));
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    var shadowed =
        new PackSource.GeneratedEntry(
            "meshes/a.nif",
            1,
            () -> {
              opens.incrementAndGet();
              return Channels.newChannel(new ByteArrayInputStream(new byte[] {1}));
            });
    BethesdaArchives.standard()
        .pack(
            request(target, options, shadowed, generated("MESHES/A.NIF", new byte[] {9, 8})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0, opens.get());
    assertEquals(0x683, wire.getInt(12));
    assertEquals(2, wire.getInt(68));
    assertArrayEquals(new byte[] {9, 8}, Arrays.copyOfRange(wire.array(), 82, 84));
  }

  /** Unknown identities and family-invalid per-entry choices fail before any payload effects. */
  @Test
  void rejectsInvalidCompressionOverridesBeforePayloads() throws Exception {
    var overrides =
        List.of(
            Map.of(new NormalizedNameIdentity("meshes\\missing.nif"), PackOptions.Compression.ZLIB),
            Map.of(new NormalizedNameIdentity("meshes\\a.nif"), PackOptions.Compression.LZ4_RAW),
            Map.of(
                new NormalizedNameIdentity("meshes\\a.nif"),
                PackOptions.Compression.FAMILY_DEFAULT));
    for (var override : overrides) {
      var opens = new java.util.concurrent.atomic.AtomicInteger();
      var source =
          new PackSource.GeneratedEntry(
              "meshes/a.nif",
              0,
              () -> {
                opens.incrementAndGet();
                return Channels.newChannel(new ByteArrayInputStream(new byte[0]));
              });
      var options =
          new PackOptions(
              List.of(),
              PackOptions.Compression.STORED,
              true,
              new PackOptions.Splitting.FamilyDefault(),
              FlagSelection.AUTOMATIC,
              FlagSelection.AUTOMATIC,
              override);
      assertThrows(
          ArchiveException.class,
          () ->
              BethesdaArchives.standard()
                  .pack(
                      request(temporary.resolve("invalid-override.bsa"), options, source),
                      OperationControl.standard()));
      assertEquals(0, opens.get());
    }
  }

  /**
   * TES3's stored-only format rejects entry overrides explicitly, even when the value is stored.
   */
  @Test
  void rejectsTes3CompressionOverrides() throws Exception {
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.STORED,
            true,
            new PackOptions.Splitting.FamilyDefault(),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC,
            Map.of(new NormalizedNameIdentity("meshes\\a.nif"), PackOptions.Compression.STORED));
    var normal =
        request(
            temporary.resolve("tes3-override.bsa"),
            options,
            generated("meshes/a.nif", new byte[0]));
    var tes3 =
        new PackRequest(
            normal.destination(),
            ArchiveFamily.TES3_BSA,
            ArchiveEncoding.tes3(),
            normal.compatibilityProfile(),
            normal.sources(),
            normal.targetPolicy(),
            normal.diagnosticPolicy(),
            normal.resourceLimits(),
            normal.workerSelection(),
            options,
            normal.ddsTarget());
    assertThrows(
        ArchiveException.class,
        () -> BethesdaArchives.standard().pack(tes3, OperationControl.standard()));
  }

  /** Exact post-overlay identities can select stored and zlib records within one archive. */
  @Test
  void packsMixedCompressionOverridesAndRoundTrips() throws Exception {
    Path target = temporary.resolve("mixed.bsa");
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.STORED,
            true,
            new PackOptions.Splitting.FamilyDefault(),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC,
            Map.of(new NormalizedNameIdentity("meshes\\b.nif"), PackOptions.Compression.ZLIB));
    BethesdaArchives.standard()
        .pack(
            request(
                target,
                options,
                generated("meshes/a.nif", new byte[] {1, 2}),
                generated("MESHES/B.NIF", new byte[] {3, 4})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x687, wire.getInt(12));
    assertEquals(0x40000002, wire.getInt(68));
    assertEquals(14, wire.getInt(84));
    try (var archive = BethesdaArchives.standard().open(target, OpenOptions.standard())) {
      for (int i = 0; i < 2; i++)
        try (var content = archive.entry(i).openContent()) {
          ByteBuffer bytes = ByteBuffer.allocate(3);
          assertEquals(2, content.read(bytes));
          assertEquals(-1, content.read(bytes));
          assertArrayEquals(
              i == 0 ? new byte[] {1, 2} : new byte[] {3, 4}, Arrays.copyOf(bytes.array(), 2));
        }
    }
  }

  /** Equal record lengths alone do not allow sharing unrelated payload bytes. */
  @Test
  void preservesDifferentPayloadsOfEqualLength() throws Exception {
    Path target = temporary.resolve("different.bsa");
    BethesdaArchives.standard()
        .pack(
            request(
                target,
                PackOptions.standard(),
                generated("meshes/a.nif", new byte[] {1, 2}),
                generated("meshes/b.nif", new byte[] {3, 4})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertNotEquals(wire.getInt(72), wire.getInt(88));
    assertArrayEquals(
        new byte[] {1, 2}, Arrays.copyOfRange(wire.array(), wire.getInt(72), wire.getInt(72) + 2));
    assertArrayEquals(
        new byte[] {3, 4}, Arrays.copyOfRange(wire.array(), wire.getInt(88), wire.getInt(88) + 2));
  }

  /** Known stored wire overflow is rejected before opening any source whose plan cannot fit. */
  @Test
  void rejectsKnownStoredOffsetOverflowBeforeOpeningSources() throws Exception {
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.STORED,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    var first =
        new PackSource.GeneratedEntry(
            "meshes/a.nif",
            0x80000000L,
            () -> {
              opens.incrementAndGet();
              return Channels.newChannel(new ByteArrayInputStream(new byte[0]));
            });
    var second =
        new PackSource.GeneratedEntry(
            "meshes/b.nif",
            0x80000000L,
            () -> {
              opens.incrementAndGet();
              return Channels.newChannel(new ByteArrayInputStream(new byte[0]));
            });
    assertThrows(
        ArchiveException.class,
        () ->
            BethesdaArchives.standard()
                .pack(
                    request(
                        temporary.resolve("overflow.bsa"),
                        options,
                        first,
                        second,
                        generated("meshes/c.nif", new byte[] {1})),
                    OperationControl.standard()));
    assertEquals(0, opens.get());
  }

  /** The last payload may end beyond four GiB when every encoded start offset still fits u32. */
  @Test
  void acceptsLargeFinalExtentUntilSourceLengthValidation() throws Exception {
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.STORED,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    var first =
        new PackSource.GeneratedEntry(
            "meshes/a.nif",
            0x80000000L,
            () -> {
              opens.incrementAndGet();
              return Channels.newChannel(new ByteArrayInputStream(new byte[0]));
            });
    var second =
        new PackSource.GeneratedEntry(
            "meshes/b.nif",
            0x80000000L,
            () -> {
              opens.incrementAndGet();
              return Channels.newChannel(new ByteArrayInputStream(new byte[0]));
            });
    var failure =
        assertThrows(
            ArchiveException.class,
            () -> {
              PackRequest planned = request(temporary.resolve("large.bsa"), options, first, second);
              // This case checks first-source length handling before a later source is opened.
              PackRequest sequential =
                  new PackRequest(
                      planned.destination(),
                      planned.family(),
                      planned.encoding(),
                      planned.compatibilityProfile(),
                      planned.sources(),
                      planned.targetPolicy(),
                      planned.diagnosticPolicy(),
                      planned.resourceLimits(),
                      new WorkerSelection.UpTo(1),
                      planned.options(),
                      planned.ddsTarget());
              BethesdaArchives.standard().pack(sequential, OperationControl.standard());
            });
    assertEquals(1, opens.get());
    assertEquals(FailureKind.SOURCE, failure.primaryFailure().kind());
    assertEquals(
        Optional.of("source.length-mismatch"), failure.primaryFailure().diagnosticIdentifier());
  }

  /** Stabilized bytes and staged archives count together against the retained scratch ceiling. */
  @Test
  void countsStabilizationAndPublicationInOneScratchBudget() throws Exception {
    var normal =
        request(
            temporary.resolve("limited.bsa"),
            PackOptions.standard(),
            generated("meshes/a.nif", new byte[100]));
    var limits = new ResourceLimits(100, 10000, 10000, 200, 10, 100, 10);
    var limited =
        new PackRequest(
            normal.destination(),
            normal.family(),
            normal.encoding(),
            normal.compatibilityProfile(),
            normal.sources(),
            normal.targetPolicy(),
            normal.diagnosticPolicy(),
            limits,
            normal.workerSelection(),
            normal.options(),
            normal.ddsTarget());
    assertThrows(
        ArchiveException.class,
        () -> BethesdaArchives.standard().pack(limited, OperationControl.standard()));
    assertFalse(Files.exists(normal.destination()));
  }

  /** Compression always stays zlib-framed, including expansion, and explicit defaults toggle. */
  @Test
  void compressesSmallPayloadWithoutStoredFallback() throws Exception {
    Path target = temporary.resolve("compressed.bsa");
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            true,
            new PackOptions.Splitting.FamilyDefault(),
            new FlagSelection.Explicit(3),
            FlagSelection.AUTOMATIC);
    BethesdaArchives.standard()
        .pack(
            request(target, options, generated("meshes/a.nif", new byte[] {42})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(3, wire.getInt(12));
    assertEquals(0x4000000d, wire.getInt(68));
    assertEquals(1, wire.getInt(82));
    assertEquals(0x78, wire.get(86) & 255);
    var inflater = new java.util.zip.Inflater();
    try {
      inflater.setInput(wire.array(), 86, 9);
      byte[] decoded = new byte[2];
      assertEquals(1, inflater.inflate(decoded));
      assertEquals(42, decoded[0]);
      assertTrue(inflater.finished());
      assertEquals(0, inflater.getRemaining());
    } finally {
      inflater.end();
    }
  }

  /** Root priority, extension fallback, and Oblivion's extra XML bit are independent rules. */
  @Test
  void classifiesAutomaticFlagsByOrderedRoots() throws Exception {
    Path target = temporary.resolve("flags.bsa");
    BethesdaArchives.standard()
        .pack(
            request(
                target,
                PackOptions.standard(),
                generated("menus/a.xml", new byte[] {1}),
                generated("shaders/a.sdp", new byte[] {2}),
                generated("sound/voice/a.wav", new byte[] {3}),
                generated("else/a.wav", new byte[] {4})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x603, wire.getInt(12));
    assertEquals(0x134, wire.getInt(32));
  }

  /** Advisory splits use actual compressed records and preserve whole entries. */
  @Test
  void splitsOnPackedBytesAndSharesExactRecords() throws Exception {
    Path target = temporary.resolve("parts.bsa");
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            true,
            new PackOptions.Splitting.UpToBytes(250),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    byte[] data = new byte[1024];
    var report =
        BethesdaArchives.standard()
            .pack(
                request(
                    target,
                    options,
                    generated("meshes/a.nif", data),
                    generated("meshes/b.nif", data)),
                OperationControl.standard());
    assertEquals(2, report.archiveParts().size());
    assertTrue(Files.size(target) < 250);
    Path shared = temporary.resolve("shared.bsa");
    BethesdaArchives.standard()
        .pack(
            request(
                shared,
                PackOptions.standard(),
                generated("meshes/a.nif", data),
                generated("meshes/b.nif", data)),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(shared)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(wire.getInt(72), wire.getInt(88));
  }

  /** All canonical name rejections happen before generated source factories run. */
  @Test
  void rejectsUnencodableNamesBeforePayloadEffects() throws Exception {
    for (String name :
        List.of("a.nif", "meshes/.nif", "a".repeat(255) + "/a.nif", "meshes/é.nif")) {
      var opens = new java.util.concurrent.atomic.AtomicInteger();
      var source =
          new PackSource.GeneratedEntry(
              name,
              1,
              () -> {
                opens.incrementAndGet();
                return null;
              });
      assertThrows(
          ArchiveException.class,
          () ->
              BethesdaArchives.standard()
                  .pack(
                      request(temporary.resolve("invalid.bsa"), PackOptions.standard(), source),
                      OperationControl.standard()));
      assertEquals(0, opens.get());
    }
  }

  /** A folder, basename table, and absolute payload pointer obey the documented layout. */
  @Test
  void packsCanonicalStoredArchive() throws Exception {
    Path target = temporary.resolve("one.bsa");
    BethesdaArchives.standard()
        .pack(
            request(
                target, PackOptions.standard(), generated("Meshes/A.nif", new byte[] {1, 2, 3})),
            OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x00415342, wire.getInt(0));
    assertEquals(0x67, wire.getInt(4));
    assertEquals(36, wire.getInt(8));
    assertEquals(0x683, wire.getInt(12));
    assertEquals(1, wire.getInt(16));
    assertEquals(1, wire.getInt(20));
    assertEquals(7, wire.getInt(24));
    assertEquals(6, wire.getInt(28));
    assertEquals(1, wire.getInt(32));
    assertEquals(58, wire.getInt(48));
    assertEquals(3, wire.getInt(68));
    assertEquals(82, wire.getInt(72));
    assertArrayEquals(new byte[] {1, 2, 3}, Arrays.copyOfRange(wire.array(), 82, 85));
  }

  /** Builds a canonical 0x67 request through the public request model. */
  private static PackRequest request(Path target, PackOptions options, PackSource... sources) {
    var standard =
        PackRequest.standard(
            target,
            ArchiveFamily.TES4_BSA,
            new ArchiveEncoding(
                Optional.of(new WireVersion(0x67)), Optional.empty(), OptionalLong.empty()),
            List.of(sources),
            Optional.empty());
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

  /** Supplies a fresh bounded payload for each source consumption. */
  private static PackSource generated(String name, byte[] bytes) {
    return new PackSource.GeneratedEntry(
        name, bytes.length, () -> Channels.newChannel(new ByteArrayInputStream(bytes)));
  }
}
