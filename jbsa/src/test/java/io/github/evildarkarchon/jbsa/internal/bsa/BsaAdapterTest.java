package io.github.evildarkarchon.jbsa.internal.bsa;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted;
import io.github.evildarkarchon.jbsa.internal.pack.Codec;
import io.github.evildarkarchon.jbsa.internal.pack.PackPipeline;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the versioned BSA adapter. Every test except the sharing discriminator's is a pure input to
 * output check; that one drives the pipeline, because the discriminator only matters once real
 * stabilized records are compared.
 */
class BsaAdapterTest {
  private static final Path TARGET = Path.of("unused.bsa").toAbsolutePath();
  private static final IoContext CONTEXT = IoContext.of(TARGET, Operation.PACK);

  @TempDir Path temporary;

  /** Output order groups equal folders and sorts by unsigned folder hash, then file hash. */
  @Test
  void plansEntriesInFolderHashThenFileHashOrder() throws Exception {
    var admitted = admitted(0x68, options(PackOptions.Compression.STORED, Map.of()));
    var planned =
        admitted.plan(
            List.of(
                entry("textures\\b.dds", 1),
                entry("meshes\\z.nif", 1),
                entry("textures\\a.dds", 1),
                entry("meshes\\a.nif", 1),
                entry("sound\\q.wav", 1)),
            CONTEXT);
    var closed = new HashSet<String>();
    for (int index = 1; index < planned.size(); index++) {
      var previous = planned.get(index - 1).key();
      var current = planned.get(index).key();
      int folder = Long.compareUnsigned(previous.folderHash(), current.folderHash());
      assertTrue(folder <= 0, "folder hash order");
      if (Arrays.equals(previous.folder(), current.folder()))
        assertTrue(
            Long.compareUnsigned(previous.nameHash(), current.nameHash()) <= 0, "name order");
      else {
        closed.add(new String(previous.folder(), StandardCharsets.US_ASCII));
        // A folder whose group already closed never reappears: equal folders are adjacent.
        assertFalse(closed.contains(new String(current.folder(), StandardCharsets.US_ASCII)));
      }
    }
    assertEquals(3, closed.size() + 1, "three folder groups");
    for (var entry : planned) {
      var key = entry.key();
      assertEquals(BsaNames.hash(key.folder(), false, 0x68), key.folderHash());
      assertEquals(BsaNames.hash(key.name(), true, 0x68), key.nameHash());
    }
  }

  /**
   * A stored record carries only the embedded name; a compressed one adds its u32 decoded size.
   * Without embedded names a stored record is unframed and a compressed one carries only the size.
   */
  @Test
  void framesEmbeddedNamesThenCompressedDecodedSizes() throws Exception {
    var embedded =
        admitted(
            0x68,
            options(
                PackOptions.Compression.ZLIB,
                new FlagSelection.Explicit(0x103),
                Map.of(new NormalizedNameIdentity("x\\s.txt"), PackOptions.Compression.STORED)));
    var planned =
        byName(embedded.plan(List.of(entry("x\\s.txt", 5), entry("x\\c.txt", 7)), CONTEXT));
    var stored = planned.get("x\\s.txt");
    assertEquals(Codec.STORED, stored.codec());
    assertArrayEquals(bytes(7, "x\\s.txt"), stored.frame());
    assertEquals(8 + 5, stored.storedSize());
    var compressed = planned.get("x\\c.txt");
    assertEquals(Codec.ZLIB, compressed.codec());
    assertArrayEquals(concat(bytes(7, "x\\c.txt"), u32(7)), compressed.frame());
    assertEquals(Admitted.Planned.UNKNOWN_SIZE, compressed.storedSize());

    var plain = admitted(0x67, options(PackOptions.Compression.ZLIB, Map.of()));
    var zlib = plain.plan(List.of(entry("x\\c.txt", 7)), CONTEXT).getFirst();
    assertArrayEquals(u32(7), zlib.frame());
    var unframed =
        admitted(0x67, options(PackOptions.Compression.STORED, Map.of()))
            .plan(List.of(entry("x\\c.txt", 7)), CONTEXT)
            .getFirst();
    assertEquals(0, unframed.frame().length);
    assertEquals(7, unframed.storedSize());
  }

  /** Only 0x69 selects the BSA LZ4 frame codec, and only when its family codec is requested. */
  @Test
  void sseCompressesWithTheBsaLz4Frame() throws Exception {
    var admitted = admitted(0x69, options(PackOptions.Compression.LZ4_FRAME, Map.of()));
    var entry = admitted.plan(List.of(entry("x\\c.txt", 7)), CONTEXT).getFirst();
    assertEquals(Codec.BSA_LZ4_FRAME, entry.codec());
    assertArrayEquals(u32(7), entry.frame());
  }

  /**
   * Admission only declares the native codec the pipeline must admit, so it succeeds on any host:
   * 0x69 requires the LZ4 frame when selected globally or by an override, and never otherwise.
   */
  @Test
  void declaresTheLz4FrameWithoutAdmittingIt() throws Exception {
    assertEquals(
        Set.of(Codec.BSA_LZ4_FRAME),
        admitted(0x69, options(PackOptions.Compression.LZ4_FRAME, Map.of())).requiredCodecs());
    assertEquals(
        Set.of(Codec.BSA_LZ4_FRAME),
        admitted(
                0x69,
                options(
                    PackOptions.Compression.STORED,
                    Map.of(
                        new NormalizedNameIdentity("x\\c.txt"), PackOptions.Compression.LZ4_FRAME)))
            .requiredCodecs());
    assertEquals(
        Set.of(),
        admitted(0x69, options(PackOptions.Compression.STORED, Map.of())).requiredCodecs());
    assertEquals(
        Set.of(), admitted(0x68, options(PackOptions.Compression.ZLIB, Map.of())).requiredCodecs());
  }

  /** The split cost charges stored records; sharing compares complete stored records per part. */
  @Test
  void declaresStabilizedStoredRecordRules() throws Exception {
    var admitted = admitted(0x68, options(PackOptions.Compression.STORED, Map.of()));
    assertEquals(Admitted.Emission.STABILIZED, admitted.emission());
    assertEquals(Admitted.PayloadCost.STORED, admitted.splitCost().payload());
    assertEquals(200, admitted.splitCost().fixed());
    var key = admitted.plan(List.of(entry("abc\\de.txt", 1)), CONTEXT).getFirst().key();
    assertEquals(3 + 1 + 6, admitted.splitCost().nameCost().applyAsLong(key));
    assertEquals(
        new Admitted.Sharing(Admitted.Sharing.Scope.PER_PART, Admitted.Sharing.Basis.STORED),
        admitted.sharing());
    assertEquals(2_147_483_647L, admitted.defaultSplitTarget());
  }

  /**
   * The payload start follows the header, one folder record plus length byte per group, the folder
   * names, 16-byte file records, and the file names; metadata adds every record's frame.
   */
  @Test
  void layoutCountsGroupsNamesRecordsAndFrames() throws Exception {
    var embedded =
        admitted(
            0x68,
            options(PackOptions.Compression.STORED, new FlagSelection.Explicit(0x103), Map.of()));
    var part =
        embedded.plan(
            List.of(entry("meshes\\a.nif", 2), entry("meshes\\b.nif", 3), entry("x\\c.txt", 4)),
            CONTEXT);
    var layout = embedded.layout(part, CONTEXT);
    long folderNames = 7 + 2, fileNames = 6 + 6 + 6;
    long dataStart = 36 + 2 * (16 + 1) + folderNames + 3 * 16 + fileNames;
    assertEquals(dataStart, layout.payloadStart());
    long frames = (1 + 12) + (1 + 12) + (1 + 7);
    assertEquals(dataStart + frames, layout.metadataBytes());

    // 0x69 folder records carry eight more bytes of padding.
    var sse = admitted(0x69, options(PackOptions.Compression.STORED, Map.of()));
    var ssePart = sse.plan(List.of(entry("x\\c.txt", 4)), CONTEXT);
    assertEquals(36 + (24 + 1) + 2 + 16 + 6, sse.layout(ssePart, CONTEXT).payloadStart());
  }

  /** Record starts are u32; only the last record may extend past four GiB. */
  @Test
  void layoutRejectsRecordStartsBeyondU32() throws Exception {
    var admitted = admitted(0x67, options(PackOptions.Compression.ZLIB, Map.of()));
    var part = admitted.plan(List.of(entry("x\\a.txt", 1), entry("x\\b.txt", 1)), CONTEXT);
    long dataStart = 36 + 17 + 2 + 2 * 16 + 12;
    long toEdge = 0x1_0000_0000L - dataStart;
    var fits = List.of(part.get(0).stabilized(toEdge - 1), part.get(1).stabilized(1L << 40));
    assertEquals(dataStart, admitted.layout(fits, CONTEXT).payloadStart());
    var overflow = List.of(part.get(0).stabilized(toEdge), part.get(1).stabilized(1));
    var failure = assertThrows(ArchiveException.class, () -> admitted.layout(overflow, CONTEXT));
    assertEquals(Optional.of("bsa.wire-limit"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(FailureKind.POLICY, failure.kind());
  }

  /** A stabilized record whose size reaches bit 30 cannot be encoded, at its PROCESSING ordinal. */
  @Test
  void checkStoredRejectsTheCompressionBit() throws Exception {
    var admitted = admitted(0x67, options(PackOptions.Compression.ZLIB, Map.of()));
    var entry = admitted.plan(List.of(entry("x\\a.txt", 1)), CONTEXT).getFirst();
    var processing =
        new IoContext(TARGET, Operation.PACK, OperationPhase.PROCESSING, OptionalLong.of(3));
    admitted.checkStored(entry.stabilized(0x3fff_ffffL), processing);
    var failure =
        assertThrows(
            ArchiveException.class,
            () -> admitted.checkStored(entry.stabilized(0x4000_0000L), processing));
    assertEquals(Optional.of("bsa.wire-limit"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertEquals(OptionalLong.of(3), failure.primaryFailure().ordinal());
  }

  /**
   * The patches tile the whole metadata region: header, folder record (whose offset is its block
   * position plus the file-name total), the folder block with its file records, and the names. A
   * shared entry repeats its owner's absolute offset.
   */
  @Test
  void tablesTileHeaderFoldersRecordsAndNames() throws Exception {
    var admitted = admitted(0x67, options(PackOptions.Compression.STORED, Map.of()));
    var part = admitted.plan(List.of(entry("x\\a.txt", 3), entry("x\\b.txt", 3)), CONTEXT);
    var layout = admitted.layout(part, CONTEXT);
    long dataStart = 36 + 17 + 2 + 2 * 16 + 12;
    assertEquals(dataStart, layout.payloadStart());
    var placed =
        List.of(
            new Admitted.Placed<>(part.get(0), dataStart, 3),
            new Admitted.Placed<>(part.get(1), dataStart, 3));
    byte[] metadata = apply(admitted.tables(placed, layout), (int) dataStart);

    var expected = ByteBuffer.allocate((int) dataStart).order(ByteOrder.LITTLE_ENDIAN);
    // ".txt" is class 15 (256); 0x67 automatic flags are 0x603 with no compressed record.
    for (long word : new long[] {0x00415342, 0x67, 36, 0x603, 1, 2, 2, 12, 256})
      expected.putInt((int) word);
    expected.putLong(part.get(0).key().folderHash()).putInt(2).putInt(52 + 12);
    expected.put((byte) 2).put((byte) 'x').put((byte) 0);
    for (var entry : part)
      expected.putLong(entry.key().nameHash()).putInt(3).putInt((int) dataStart);
    expected.put(concat(ascii(part.get(0).key().name()), ascii(part.get(1).key().name())));
    assertArrayEquals(expected.array(), metadata);
  }

  /**
   * Bit 30 marks a record whose compression differs from the archive default; any compressed record
   * sets that default, and an explicit file flag selection also decides the archive's 0x80 bit.
   */
  @Test
  void flagsFollowThePartAndExplicitFileFlags() throws Exception {
    var mixed =
        admitted(
            0x67,
            options(
                PackOptions.Compression.ZLIB,
                Map.of(new NormalizedNameIdentity("x\\s.txt"), PackOptions.Compression.STORED)));
    var part = mixed.plan(List.of(entry("x\\s.txt", 3), entry("x\\c.txt", 3)), CONTEXT);
    var placed = new ArrayList<Admitted.Placed<BsaAdapter.WireName>>();
    long position = 1000;
    for (var entry : part) placed.add(new Admitted.Placed<>(entry.stabilized(9), position++, 9));
    byte[] metadata = apply(mixed.tables(placed, mixed.layout(stabilized(part, 9), CONTEXT)), 99);
    var wire = ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x607, wire.getInt(12));
    for (int record = 0; record < 2; record++) {
      int size = wire.getInt(55 + record * 16 + 8);
      boolean stored = placed.get(record).planned().codec() == Codec.STORED;
      assertEquals(stored ? 0x4000_0009 : 9, size);
    }

    var explicit =
        admitted(
            0x68,
            new PackOptions(
                List.of(),
                PackOptions.Compression.STORED,
                true,
                new PackOptions.Splitting.FamilyDefault(),
                FlagSelection.AUTOMATIC,
                new FlagSelection.Explicit(1)));
    var one = explicit.plan(List.of(entry("x\\c.txt", 3)), CONTEXT);
    var header =
        ByteBuffer.wrap(
                apply(
                    explicit.tables(
                        List.of(new Admitted.Placed<>(one.getFirst(), 77, 3)),
                        explicit.layout(one, CONTEXT)),
                    77))
            .order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x83, header.getInt(12));
    assertEquals(1, header.getInt(32));
  }

  /**
   * A stored record and a compressed record whose framed bytes are identical must not share one
   * copy: the discriminator separates them, while two identical stored records still share.
   */
  @Test
  void storedAndCompressedRecordsWithIdenticalBytesDoNotAlias() throws Exception {
    byte[] decoded = new byte[1000];
    Arrays.fill(decoded, (byte) 'z');
    Path probe = temporary.resolve("probe.bsa");
    PackPipeline.pack(
        request(probe, PackOptions.Compression.ZLIB, Map.of(), source("x/a.txt", decoded)),
        OperationControl.standard(),
        BsaAdapter.INSTANCE);
    // One folder and one file: the compressed record (u32 size then zlib) follows the metadata.
    byte[] probeBytes = Files.readAllBytes(probe);
    byte[] record = Arrays.copyOfRange(probeBytes, 36 + 17 + 2 + 16 + 6, probeBytes.length);

    Path mixed = temporary.resolve("mixed.bsa");
    PackPipeline.pack(
        request(
            mixed,
            PackOptions.Compression.ZLIB,
            Map.of(new NormalizedNameIdentity("x\\b.txt"), PackOptions.Compression.STORED),
            source("x/a.txt", decoded),
            source("x/b.txt", record)),
        OperationControl.standard(),
        BsaAdapter.INSTANCE);
    long[] offsets = recordOffsets(mixed);
    assertNotEquals(offsets[0], offsets[1]);
    assertEquals(36 + 17 + 2 + 2 * 16 + 12 + 2L * record.length, Files.size(mixed));

    Path shared = temporary.resolve("shared.bsa");
    PackPipeline.pack(
        request(
            shared,
            PackOptions.Compression.STORED,
            Map.of(),
            source("x/a.txt", record),
            source("x/b.txt", record)),
        OperationControl.standard(),
        BsaAdapter.INSTANCE);
    long[] sharedOffsets = recordOffsets(shared);
    assertEquals(sharedOffsets[0], sharedOffsets[1]);
  }

  /** Reads the two file-record offsets of a one-folder "x" 0x67 archive. */
  private static long[] recordOffsets(Path archive) throws Exception {
    var wire = ByteBuffer.wrap(Files.readAllBytes(archive)).order(ByteOrder.LITTLE_ENDIAN);
    return new long[] {
      Integer.toUnsignedLong(wire.getInt(55 + 12)), Integer.toUnsignedLong(wire.getInt(71 + 12))
    };
  }

  /** Applies positional patches to a zeroed buffer, failing if any lands outside it. */
  private static byte[] apply(List<Admitted.Patch> patches, int size) {
    byte[] bytes = new byte[size];
    for (var patch : patches)
      System.arraycopy(patch.bytes(), 0, bytes, (int) patch.position(), patch.bytes().length);
    return bytes;
  }

  /** Returns every entry with the same measured stored size. */
  private static List<Admitted.Planned<BsaAdapter.WireName>> stabilized(
      List<Admitted.Planned<BsaAdapter.WireName>> part, long size) {
    return part.stream().map(entry -> entry.stabilized(size)).toList();
  }

  /** Indexes planned entries by identity. */
  private static Map<String, Admitted.Planned<BsaAdapter.WireName>> byName(
      List<Admitted.Planned<BsaAdapter.WireName>> planned) {
    var byName = new HashMap<String, Admitted.Planned<BsaAdapter.WireName>>();
    for (var entry : planned) byName.put(entry.source().identity(), entry);
    return byName;
  }

  /** Admits a versioned BSA request of the given wire version through the adapter singleton. */
  private static Admitted<BsaAdapter.WireName> admitted(int version, PackOptions options)
      throws ArchiveException {
    return BsaAdapter.INSTANCE.admit(request(TARGET, version, options), CONTEXT);
  }

  /** A standard request for one wire version, replacing only its options and sources. */
  private static PackRequest request(
      Path target, int version, PackOptions options, PackSource... sources) {
    ArchiveFamily family =
        switch (version) {
          case 0x67 -> ArchiveFamily.TES4_BSA;
          case 0x68 -> ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA;
          default -> ArchiveFamily.SSE_BSA;
        };
    var standard =
        PackRequest.standard(
            target,
            family,
            new ArchiveEncoding(
                Optional.of(new WireVersion(version)), Optional.empty(), OptionalLong.empty()),
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
        new WorkerSelection.UpTo(1),
        options,
        standard.ddsTarget());
  }

  /** A sharing 0x67 request with the given codecs. */
  private static PackRequest request(
      Path target,
      PackOptions.Compression compression,
      Map<NormalizedNameIdentity, PackOptions.Compression> entryCompression,
      PackSource... sources) {
    return request(target, 0x67, options(compression, entryCompression), sources);
  }

  /** Sharing options with automatic flags and the given codecs. */
  private static PackOptions options(
      PackOptions.Compression compression,
      Map<NormalizedNameIdentity, PackOptions.Compression> entryCompression) {
    return options(compression, FlagSelection.AUTOMATIC, entryCompression);
  }

  /** Sharing options with the given archive flags and codecs. */
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

  /** A planned source whose payload pure adapter methods never consume. */
  private static PackSources.Entry entry(String identity, long size) {
    return new PackSources.Entry(
        identity,
        identity,
        identity.getBytes(StandardCharsets.US_ASCII),
        0,
        size,
        reader -> {
          throw new AssertionError("Adapters must not read payloads");
        });
  }

  /** A generated source with fixed bytes. */
  private static PackSource source(String name, byte[] bytes) {
    return new PackSource.GeneratedEntry(
        name, bytes.length, () -> Channels.newChannel(new ByteArrayInputStream(bytes)));
  }

  /** A length byte followed by ASCII text. */
  private static byte[] bytes(int length, String text) {
    return concat(new byte[] {(byte) length}, text.getBytes(StandardCharsets.US_ASCII));
  }

  /** A NUL-terminated copy of a name. */
  private static byte[] ascii(byte[] name) {
    return Arrays.copyOf(name, name.length + 1);
  }

  /** A little-endian u32. */
  private static byte[] u32(long value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array();
  }

  /** Concatenates two arrays. */
  private static byte[] concat(byte[] left, byte[] right) {
    byte[] joined = Arrays.copyOf(left, left.length + right.length);
    System.arraycopy(right, 0, joined, left.length, right.length);
    return joined;
  }
}
