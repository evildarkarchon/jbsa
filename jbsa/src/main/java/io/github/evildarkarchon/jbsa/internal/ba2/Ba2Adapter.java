package io.github.evildarkarchon.jbsa.internal.ba2;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.dds.DdsEnvelope;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.JdkZlib;
import io.github.evildarkarchon.jbsa.internal.io.Lz4Raw;
import io.github.evildarkarchon.jbsa.internal.io.Lz4Runtime;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted;
import io.github.evildarkarchon.jbsa.internal.pack.Codec;
import io.github.evildarkarchon.jbsa.internal.pack.FamilyAdapter;
import io.github.evildarkarchon.jbsa.internal.tes3.Tes3Names;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * BA2 wire knowledge for the Pack Pipeline, for General and DX10 archives, Fallout 4 v1 and
 * Starfield v2/v3. Entries keep Logical Plan Order. Content Sharing owners are decided once for the
 * whole archive set on raw bytes (DX10 per normalized mip chunk), each owner is emitted once per
 * part, and every part is laid out header-last: records, payloads, then the trailing name table.
 * Every staged part is read back before publication. Every method is pure; the pipeline owns all
 * I/O and lifecycle.
 */
public final class Ba2Adapter implements FamilyAdapter<Ba2Adapter.Key> {
  /** The stateless singleton. */
  public static final Ba2Adapter INSTANCE = new Ba2Adapter();

  /** The largest DDS envelope header {@link DdsEnvelope#analyze} inspects (XBOX DX10). */
  private static final int DDS_HEAD_BYTES = 164;

  private Ba2Adapter() {}

  /**
   * One entry's BA2 wire name and identity.
   *
   * @param name the ASCII wire name with forward slashes, as stored in the trailing name table
   * @param identity the canonical hashed identity the record carries
   * @param displayLength the source display name's length in chars, which the split cost charges
   * @param texture the DDS envelope analysis {@link Admitted#chunk} attached, or null for General
   *     entries and DX10 entries not yet stabilized
   */
  public record Key(
      byte[] name,
      EntryMetadata.Ba2Identity identity,
      int displayLength,
      DdsEnvelope.Analysis texture) {
    /** Returns this key with its stabilized texture metadata. */
    Key withTexture(DdsEnvelope.Analysis analysis) {
      return new Key(name, identity, displayLength, analysis);
    }

    /** Returns the variable DX10 record extent or the fixed General record extent. */
    long recordSize() {
      return texture == null ? 36 : 24 + 24L * texture.chunks().size();
    }
  }

  /**
   * Applies the BA2 request rules in their established order: an available name profile, mixed
   * compressed codecs, the family's selector tuple, flags, the global codec, every entry codec
   * override, a stored DX10 request, then the raw LZ4 runtime when raw LZ4 is in use.
   */
  @Override
  public Admitted<Key> admit(PackRequest request, IoContext context) throws ArchiveException {
    ArchiveFamily family = request.family();
    boolean dds = family == ArchiveFamily.FO4_DDS_BA2 || family == ArchiveFamily.STARFIELD_DDS_BA2;
    boolean starfield =
        family == ArchiveFamily.STARFIELD_GENERAL_BA2 || family == ArchiveFamily.STARFIELD_DDS_BA2;
    if (!dds && !starfield && family != ArchiveFamily.FO4_GENERAL_BA2)
      throw new IllegalArgumentException("Not a BA2 family");
    Charset charset = Tes3Names.encoding(request.compatibilityProfile(), context);
    PackOptions options = request.options();
    PackOptions.Compression global = options.compression();
    boolean rawLz4 =
        starfield
            && (global == PackOptions.Compression.LZ4_RAW
                || (family == ArchiveFamily.STARFIELD_DDS_BA2
                    && global == PackOptions.Compression.FAMILY_DEFAULT)
                || options.entryCompression().containsValue(PackOptions.Compression.LZ4_RAW));
    boolean zlib =
        global == PackOptions.Compression.ZLIB
            || options.entryCompression().containsValue(PackOptions.Compression.ZLIB);
    if (rawLz4 && zlib)
      throw context.failure(FailureKind.UNSUPPORTED, "ba2.mixed-compressed-codecs", null);
    int version = starfield ? (rawLz4 ? 3 : 2) : 1;
    ArchiveEncoding encoding =
        new ArchiveEncoding(
            Optional.of(new WireVersion(version)),
            Optional.of(dds ? Ba2Subtype.DX10 : Ba2Subtype.GNRL),
            rawLz4 ? OptionalLong.of(3) : OptionalLong.empty());
    if (!request.encoding().equals(encoding))
      throw context.failure(FailureKind.UNSUPPORTED, "archive.unsupported-encoding", null);
    if (options.archiveFlags() instanceof FlagSelection.Explicit
        || options.fileFlags() instanceof FlagSelection.Explicit)
      throw context.failure(FailureKind.UNSUPPORTED, "ba2.flags-inapplicable", null);
    if (global != PackOptions.Compression.FAMILY_DEFAULT
        && global != PackOptions.Compression.STORED
        && global != PackOptions.Compression.ZLIB
        && (!starfield || global != PackOptions.Compression.LZ4_RAW))
      throw context.failure(FailureKind.UNSUPPORTED, "ba2.unsupported-codec", null);
    for (var choice : options.entryCompression().values())
      if (choice != PackOptions.Compression.STORED
          && choice != PackOptions.Compression.ZLIB
          && (!starfield || choice != PackOptions.Compression.LZ4_RAW))
        throw context.failure(FailureKind.UNSUPPORTED, "ba2.unsupported-entry-codec", null);
    if (dds
        && (global == PackOptions.Compression.STORED
            || options.entryCompression().containsValue(PackOptions.Compression.STORED)))
      throw context.failure(FailureKind.UNSUPPORTED, "dx10.stored-encode", null);
    return new Plan(dds, rawLz4, version, charset, options, request.ddsTarget().orElse(null));
  }

  /**
   * The request-derived BA2 plan.
   *
   * @param dds whether this is a DX10 archive of chunked textures
   * @param rawLz4 whether compressed payloads use Starfield raw LZ4 instead of zlib
   * @param version the wire version, 1, 2, or 3
   * @param charset the name profile names are planned and encoded under
   * @param options the request's pack options, which select codecs
   * @param ddsTarget the DDS platform target; null for General archives
   */
  record Plan(
      boolean dds,
      boolean rawLz4,
      int version,
      Charset charset,
      PackOptions options,
      DdsTarget ddsTarget)
      implements Admitted<Key> {
    /** BA2 names are planned under windows-1252 or the active ANSI profile. */
    @Override
    public Charset nameCharset() {
      return charset;
    }

    @Override
    public String diagnosticPrefix() {
      return "ba2";
    }

    /** BA2 never splits unless the request asks for it. */
    @Override
    public long defaultSplitTarget() {
      return 0;
    }

    @Override
    public Emission emission() {
      return Emission.STABILIZED;
    }

    /**
     * The advisory estimate is a 200-byte allowance, the display name's length, and each shared
     * owner's stored size only the first time a part references it.
     */
    @Override
    public SplitCost<Key> splitCost() {
      return new SplitCost<>(200, Key::displayLength, PayloadCost.UNIQUE_STORED);
    }

    /** Owners are decided once for the whole set on raw (for DX10, normalized) bytes. */
    @Override
    public Sharing sharing() {
      return new Sharing(Sharing.Scope.ARCHIVE_SET, Sharing.Basis.RAW);
    }

    /** Raw LZ4 is the one BA2 codec with a native provider to admit; zlib is JDK-only. */
    @Override
    public Set<Codec> requiredCodecs() {
      return rawLz4 ? Set.of(Codec.LZ4_RAW) : Set.of();
    }

    /** DDS adds bounded partition and header state beyond the shared source-entry allowance. */
    @Override
    public long entryReserveBytes() {
      return dds ? 2048 : 0;
    }

    @Override
    public int chunkHeadBytes() {
      return dds ? DDS_HEAD_BYTES : 0;
    }

    /**
     * Applies the per-entry rules in Logical Plan Order: a DDS extension in a DX10 archive, a
     * directory and file name, an encodable ASCII wire name of at most 65535 bytes, the same rules
     * on the actual wire spelling, a unique wire identity, and a u32 General source size. Returns
     * the entries in Logical Plan Order with their codec.
     */
    @Override
    public List<Planned<Key>> plan(List<PackSources.Entry> sources, IoContext context)
        throws ArchiveException {
      List<Planned<Key>> planned = new ArrayList<>(sources.size());
      Set<NormalizedNameIdentity> wireIdentities = new HashSet<>();
      for (PackSources.Entry source : sources) {
        PackOptions.Compression selected =
            options
                .entryCompression()
                .getOrDefault(new NormalizedNameIdentity(source.identity()), options.compression());
        boolean compressed =
            dds
                || selected == PackOptions.Compression.ZLIB
                || selected == PackOptions.Compression.LZ4_RAW;
        String display = source.displayName().replace('\\', '/');
        if (dds && !display.toLowerCase(Locale.ROOT).endsWith(".dds"))
          throw context.failure(FailureKind.UNSUPPORTED, "dds.non-dds-entry", null);
        int separator = display.lastIndexOf('/');
        if (separator <= 0 || separator == display.length() - 1)
          throw context.failure(FailureKind.POLICY, "ba2.invalid-encode-name", null);
        byte[] name;
        try {
          ByteBuffer encoded = charset.newEncoder().encode(CharBuffer.wrap(display));
          name = new byte[encoded.remaining()];
          encoded.get(name);
        } catch (CharacterCodingException failure) {
          throw context.failure(FailureKind.POLICY, "ba2.invalid-encode-name", failure);
        }
        if (name.length > 65535 || !Ba2Names.ascii(name))
          throw context.failure(FailureKind.POLICY, "ba2.invalid-encode-name", null);
        // An ANSI alias can become ASCII punctuation (for example yen becomes a separator).
        // Revalidate the actual wire spelling before payload access, including alias collisions.
        for (int index = 0; index < name.length; index++)
          if (name[index] == '\\') name[index] = '/';
        String wireDisplay = new String(name, StandardCharsets.US_ASCII);
        var wireIdentity = NormalizedNameIdentity.from(wireDisplay, charset);
        int wireSeparator = wireDisplay.lastIndexOf('/');
        if (wireIdentity.isEmpty()
            || wireSeparator <= 0
            || wireSeparator == wireDisplay.length() - 1)
          throw context.failure(FailureKind.POLICY, "ba2.invalid-encode-name", null);
        if (!wireIdentities.add(wireIdentity.orElseThrow()))
          throw context.failure(FailureKind.POLICY, "ba2.duplicate-encode-name", null);
        // DX10 chunks are checked individually once the envelope is known.
        if (!dds) checkU32(source.size(), context);
        planned.add(
            new Planned<>(
                source,
                new Key(name, Ba2Names.identity(name), source.displayName().length(), null),
                compressed ? (rawLz4 ? Codec.LZ4_RAW : Codec.ZLIB) : Codec.STORED,
                new byte[0]));
      }
      return planned;
    }

    /**
     * Validates the DDS envelope and returns its mip partitions, a BGR24 rewrite when the legacy
     * format needs one, the canonical decoded size (payload plus synthesized header), and the
     * source header as metadata. Every slice's raw size must fit its u32 record field.
     */
    @Override
    public Chunks<Key> chunk(Planned<Key> entry, ByteBuffer head, IoContext processing)
        throws ArchiveException {
      DdsEnvelope.Analysis texture =
          DdsEnvelope.analyze(head, entry.source().size(), ddsTarget, processing);
      List<Slice> slices = new ArrayList<>(texture.chunks().size());
      for (DdsEnvelope.Chunk chunk : texture.chunks()) {
        checkU32(chunk.size(), processing);
        slices.add(new Slice(chunk.offset(), chunk.size()));
      }
      long decoded =
          texture.payloadSize()
              + DdsEnvelope.canonicalHeader(
                      texture.width(),
                      texture.height(),
                      texture.mipCount(),
                      texture.dxgiFormat(),
                      texture.cubemap(),
                      texture.tileMode(),
                      ddsTarget,
                      processing)
                  .length;
      return new Chunks<>(
          entry.key().withTexture(texture),
          texture.headerSize(),
          texture.normalizeBgr24() ? Rewrite.BGR24_TO_BGRA32 : Rewrite.NONE,
          slices,
          decoded,
          texture.headerSize());
    }

    /** Every stored owner an entry references must fit its u32 packed-size field. */
    @Override
    public void checkStored(Planned<Key> entry, IoContext processing) throws ArchiveException {
      for (Segment segment : entry.segments()) checkU32(segment.storedSize(), processing);
    }

    /**
     * Payloads start after the header and every record; metadata also counts the trailing name
     * table, one u16 length and the name bytes per entry. BA2 offsets are u64, so no part field can
     * overflow here.
     */
    @Override
    public Layout layout(List<Planned<Key>> part, IoContext context) {
      long records = 0, names = 0;
      for (Planned<Key> entry : part) {
        records += entry.key().recordSize();
        names += entry.key().name().length + 2L;
      }
      long payloadStart = Ba2Layout.headerSize(version) + records;
      return new Layout(payloadStart, payloadStart + names);
    }

    /**
     * Returns every record, then the name table at the payload end, then the header, which points
     * at that name table. Records carry each owner's absolute position; a General record reports a
     * packed size only when its owner is compressed, and a DX10 record carries one chunk record per
     * slice with its mip range.
     */
    @Override
    public List<Patch> tables(List<Placed<Key>> part, Layout layout) {
      int headerSize = Ba2Layout.headerSize(version);
      List<Patch> patches = new ArrayList<>(part.size() + 2);
      long recordPosition = headerSize;
      long payloadEnd = layout.payloadStart();
      long names = 0;
      for (Placed<Key> placed : part) {
        Planned<Key> entry = placed.planned();
        Key key = entry.key();
        List<Segment> segments = entry.segments();
        for (int index = 0; index < segments.size(); index++)
          payloadEnd =
              Math.max(
                  payloadEnd,
                  placed.segmentOffsets().get(index) + segments.get(index).storedSize());
        ByteBuffer record = little((int) key.recordSize());
        record
            .putInt((int) key.identity().baseNameHash())
            .put(key.identity().extension().bytes())
            .putInt((int) key.identity().directoryHash())
            .put((byte) 0);
        if (key.texture() == null) {
          Segment owner = segments.getFirst();
          record
              .put((byte) 1)
              .putShort((short) 16)
              .putLong(placed.segmentOffsets().getFirst())
              .putInt(owner.codec() != Codec.STORED ? (int) owner.storedSize() : 0)
              .putInt((int) entry.source().size())
              .putInt(0xbaadf00d);
        } else {
          DdsEnvelope.Analysis texture = key.texture();
          record
              .put((byte) segments.size())
              .putShort((short) 24)
              .putShort((short) texture.height())
              .putShort((short) texture.width())
              .put((byte) texture.mipCount())
              .put((byte) texture.dxgiFormat())
              .put((byte) (texture.cubemap() ? 1 : 0))
              .put((byte) texture.tileMode());
          for (int index = 0; index < segments.size(); index++) {
            DdsEnvelope.Chunk chunk = texture.chunks().get(index);
            record
                .putLong(placed.segmentOffsets().get(index))
                .putInt((int) segments.get(index).storedSize())
                .putInt((int) segments.get(index).rawSize())
                .putShort((short) chunk.startMip())
                .putShort((short) chunk.endMip())
                .putInt(0xbaadf00d);
          }
        }
        patches.add(new Patch(recordPosition, record.array()));
        recordPosition += key.recordSize();
        names += key.name().length + 2L;
      }
      ByteBuffer table = little(Math.toIntExact(names));
      for (Placed<Key> placed : part) {
        byte[] name = placed.planned().key().name();
        table.putShort((short) name.length).put(name);
      }
      patches.add(new Patch(payloadEnd, table.array()));
      ByteBuffer header = little(headerSize);
      header
          .putInt(0x58445442)
          .putInt(version)
          .putInt(dds ? 0x30315844 : 0x4c524e47)
          .putInt(part.size())
          .putLong(payloadEnd);
      if (version >= 2) header.putLong(1);
      if (version == 3) header.putInt(3);
      patches.add(new Patch(0, header.array()));
      return patches;
    }

    /**
     * Every staged part is reopened and fully decoded before publication. The reader holds per
     * entry state, eight bytes per metadata byte, one decode window, and one codec: raw LZ4 when
     * the part holds a raw-LZ4 entry (which needs its largest source whole), otherwise zlib.
     */
    @Override
    public Readback readback(List<Planned<Key>> part, Layout layout) {
      boolean validatesRawLz4 =
          rawLz4 && part.stream().anyMatch(entry -> entry.codec() != Codec.STORED);
      // Raw LZ4's block buffers are native under the LWJGL provider and heap under the portable
      // one; pack preflight has already pinned which of the two the reader will use.
      Lz4Runtime.Provider provider = validatesRawLz4 ? Lz4Runtime.selected() : null;
      long codecHeap =
          validatesRawLz4
              ? 4096
                  + part.stream().mapToLong(entry -> entry.source().size()).max().orElse(0)
                  + Lz4Raw.maxDecodeHeapBytes(provider)
              : JdkZlib.DECODE_HEAP_BYTES;
      long codecNative =
          validatesRawLz4 ? Lz4Raw.maxDecodeNativeBytes(provider) : JdkZlib.DECODE_NATIVE_BYTES;
      return new Readback(
          "ba2.noncanonical-staged-output",
          512L * part.size() + 8L * layout.metadataBytes() + 65536 + codecHeap,
          codecNative,
          1);
    }
  }

  /** Rejects impossible unsigned fields before narrowing. */
  private static void checkU32(long value, IoContext context) throws ArchiveException {
    if (value < 0 || value > 0xffffffffL)
      throw context.failure(FailureKind.POLICY, "ba2.wire-limit", null);
  }

  /** Allocates a little-endian table buffer. */
  private static ByteBuffer little(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }
}
