package io.github.evildarkarchon.jbsa.internal.tes3;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted;
import io.github.evildarkarchon.jbsa.internal.pack.Codec;
import io.github.evildarkarchon.jbsa.internal.pack.FamilyAdapter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * TES3 wire knowledge for the Pack Pipeline: hash-sorted, stored-only, streamed parts with per-part
 * raw Content Sharing. Every method is pure; the pipeline owns all I/O and lifecycle.
 */
public final class Tes3Adapter implements FamilyAdapter<Tes3Adapter.WireName> {
  /** The stateless singleton. */
  public static final Tes3Adapter INSTANCE = new Tes3Adapter();

  private static final byte[] NO_FRAME = new byte[0];

  private Tes3Adapter() {}

  /**
   * One entry's canonical wire name and its TES3 hash.
   *
   * @param bytes the encoded name, without its NUL terminator
   * @param hash the 64-bit TES3 name hash, low word first on the wire
   */
  public record WireName(byte[] bytes, long hash) {}

  /**
   * Applies TES3's request rules in order: no entry codec overrides, no compressed codec (D6), an
   * available name profile, the TES3 encoding, and no explicit flag groups. TES3 has no
   * request-derived planning state, so every admitted request shares one {@link Admitted} value.
   */
  @Override
  public Admitted<WireName> admit(PackRequest request, IoContext context) throws ArchiveException {
    if (!request.options().entryCompression().isEmpty())
      throw context.failure(FailureKind.UNSUPPORTED, "tes3.entry-compression-inapplicable", null);
    // D6: TES3 has no compressed wire form, so a compressed global choice is refused, not ignored.
    if (request.options().compression() != PackOptions.Compression.FAMILY_DEFAULT
        && request.options().compression() != PackOptions.Compression.STORED)
      throw context.failure(FailureKind.UNSUPPORTED, "tes3.unsupported-codec", null);
    Tes3Names.encoding(request.compatibilityProfile(), context);
    if (!request.encoding().equals(ArchiveEncoding.tes3()))
      throw context.failure(FailureKind.UNSUPPORTED, "archive.unsupported-encoding", null);
    if (request.options().archiveFlags() instanceof FlagSelection.Explicit
        || request.options().fileFlags() instanceof FlagSelection.Explicit)
      throw context.failure(FailureKind.UNSUPPORTED, "tes3.flags-inapplicable", null);
    return Plan.INSTANCE;
  }

  /** The request-independent TES3 plan. */
  static final class Plan implements Admitted<WireName> {
    static final Plan INSTANCE = new Plan();

    /** Orders by the hash's low word, then its high word, then canonical name bytes, unsigned. */
    private static final Comparator<Planned<WireName>> OUTPUT_ORDER =
        (a, b) -> {
          long left = a.key().hash(), right = b.key().hash();
          int low = Long.compare(left & 0xffff_ffffL, right & 0xffff_ffffL);
          int high = Long.compare(left >>> 32, right >>> 32);
          return low != 0
              ? low
              : high != 0 ? high : Arrays.compareUnsigned(a.key().bytes(), b.key().bytes());
        };

    private Plan() {}

    /** TES3 names are planned as ASCII; the profile check only validates availability. */
    @Override
    public Charset nameCharset() {
      return StandardCharsets.US_ASCII;
    }

    @Override
    public String diagnosticPrefix() {
      return "tes3";
    }

    @Override
    public long defaultSplitTarget() {
      return 2_147_483_647L;
    }

    @Override
    public Emission emission() {
      return Emission.STREAMING;
    }

    /** The advisory estimate is the payload, a 200-byte allowance, and the name bytes. */
    @Override
    public SplitCost<WireName> splitCost() {
      return new SplitCost<>(200, name -> name.bytes().length, PayloadCost.DECODED);
    }

    @Override
    public Sharing sharing() {
      return new Sharing(Sharing.Scope.PER_PART, Sharing.Basis.RAW);
    }

    /**
     * Rejects a size that cannot be encoded before splitting or charging the decoded-size limit,
     * then returns stored entries in hash order.
     */
    @Override
    public List<Planned<WireName>> plan(List<PackSources.Entry> sources, IoContext context)
        throws ArchiveException {
      List<Planned<WireName>> planned = new ArrayList<>(sources.size());
      for (PackSources.Entry source : sources) {
        checkU32(source.size(), context);
        planned.add(
            new Planned<>(
                source, new WireName(source.name(), source.hash()), Codec.STORED, NO_FRAME));
      }
      planned.sort(OUTPUT_ORDER);
      return planned;
    }

    /**
     * Checks every u32 field of one part. The payload start equals the metadata extent: a 12-byte
     * header, then 8-byte size/offset records, 4-byte name offsets, NUL-terminated names, and
     * 8-byte hashes. Offsets are checked against the unshared payload sum.
     */
    @Override
    public Layout layout(List<Planned<WireName>> part, IoContext context) throws ArchiveException {
      long metadata = 12 + part.size() * 20L;
      long relative = 0;
      for (Planned<WireName> entry : part) {
        checkU32(entry.source().size(), context);
        checkU32(relative, context);
        relative = Math.addExact(relative, entry.source().size());
        metadata = Math.addExact(metadata, entry.key().bytes().length + 1L);
      }
      checkU32(metadata - 12 - part.size() * 8L, context);
      // Only overflow matters here: the part's complete unshared extent must fit a signed long.
      Math.addExact(metadata, relative);
      return new Layout(metadata, metadata);
    }

    /**
     * Returns the header, size/offset records, name offsets, names, and hashes. Record offsets are
     * relative to the payload start, so shared entries repeat their owner's offset.
     */
    @Override
    public List<Patch> tables(List<Placed<WireName>> part, Layout layout) {
      int count = part.size();
      long names = 0;
      for (Placed<WireName> entry : part) names += entry.planned().key().bytes().length + 1L;
      long hashOffset = count * 12L + names;
      ByteBuffer records = little(count * 8);
      ByteBuffer nameOffsets = little(count * 4);
      ByteBuffer nameTable = little(Math.toIntExact(names));
      ByteBuffer hashes = little(count * 8);
      for (Placed<WireName> entry : part) {
        byte[] name = entry.planned().key().bytes();
        records
            .putInt((int) entry.storedSize())
            .putInt((int) (entry.offset() - layout.payloadStart()));
        nameOffsets.putInt(nameTable.position());
        nameTable.put(name).put((byte) 0);
        hashes.putLong(entry.planned().key().hash());
      }
      return List.of(
          new Patch(0, words(0x100, hashOffset, count)),
          new Patch(12, records.array()),
          new Patch(12 + count * 8L, nameOffsets.array()),
          new Patch(12 + count * 12L, nameTable.array()),
          new Patch(12 + hashOffset, hashes.array()));
    }
  }

  /** Rejects a wire field before narrowing or opening any source payload. */
  private static void checkU32(long value, IoContext context) throws ArchiveException {
    if (value < 0 || value > 0xffff_ffffL)
      throw context.failure(FailureKind.POLICY, "tes3.wire-limit", null);
  }

  /** Allocates a little-endian table buffer. */
  private static ByteBuffer little(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  /** Serializes checked unsigned TES3 fields in little-endian order. */
  private static byte[] words(long... values) {
    var bytes = little(values.length * 4);
    for (long value : values) bytes.putInt((int) value);
    return bytes.array();
  }
}
