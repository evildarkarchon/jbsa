package io.github.evildarkarchon.jbsa.internal.bsa;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted;
import io.github.evildarkarchon.jbsa.internal.pack.Codec;
import io.github.evildarkarchon.jbsa.internal.pack.FamilyAdapter;
import io.github.evildarkarchon.jbsa.internal.tes3.Tes3Names;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Versioned BSA (0x67, 0x68, 0x69) wire knowledge for the Pack Pipeline: folder-hash order, framed
 * stored records, per-part Content Sharing of complete stored records, and folder-grouped tables.
 * Payloads are stabilized before splitting because the split cost charges exact stored sizes. Every
 * method is pure; the pipeline owns all I/O and lifecycle.
 */
public final class BsaAdapter implements FamilyAdapter<BsaAdapter.WireName> {
  /** The stateless singleton. */
  public static final BsaAdapter INSTANCE = new BsaAdapter();

  private BsaAdapter() {}

  /**
   * One entry's canonical folder and file name and their family hashes.
   *
   * @param folder the folder bytes, without a terminator
   * @param name the file name bytes, without a terminator
   * @param folderHash the folder's hash under the family's byte recurrence
   * @param nameHash the file name's hash under the family's byte recurrence
   */
  public record WireName(byte[] folder, byte[] name, long folderHash, long nameHash) {}

  /**
   * Applies the versioned BSA request rules in their established order: an available name profile,
   * the family's wire version, the global codec, the archive flags, every entry codec override,
   * then the LZ4 runtime when 0x69 compression is requested.
   */
  @Override
  public Admitted<WireName> admit(PackRequest request, IoContext context) throws ArchiveException {
    Tes3Names.encoding(request.compatibilityProfile(), context);
    int version =
        switch (request.family()) {
          case TES4_BSA -> 0x67;
          case FO3_FNV_SKYRIM_LE_BSA -> 0x68;
          case SSE_BSA -> 0x69;
          default -> throw new IllegalArgumentException("Not a versioned-BSA family");
        };
    if (!request
        .encoding()
        .equals(
            new ArchiveEncoding(
                Optional.of(new WireVersion(version)), Optional.empty(), OptionalLong.empty())))
      throw context.failure(FailureKind.UNSUPPORTED, "archive.unsupported-encoding", null);
    PackOptions options = request.options();
    PackOptions.Compression familyCodec =
        version == 0x69 ? PackOptions.Compression.LZ4_FRAME : PackOptions.Compression.ZLIB;
    boolean defaultCompressed = options.compression() == familyCodec;
    if (options.compression() != PackOptions.Compression.FAMILY_DEFAULT
        && options.compression() != PackOptions.Compression.STORED
        && !defaultCompressed)
      throw context.failure(FailureKind.UNSUPPORTED, "bsa.unsupported-codec", null);
    if (options.archiveFlags() instanceof FlagSelection.Explicit flags
        && ((flags.value() & 3) != 3
            || (flags.value() & ~(version == 0x67 ? 0x6bfL : version == 0x69 ? 0x7ffL : 0x7bfL))
                != 0)) throw context.failure(FailureKind.POLICY, "bsa.invalid-archive-flags", null);
    boolean embedded =
        version != 0x67
            && options.archiveFlags() instanceof FlagSelection.Explicit flags
            && (flags.value() & 0x100) != 0;
    for (var choice : options.entryCompression().values())
      if (choice != PackOptions.Compression.STORED && choice != familyCodec)
        throw context.failure(FailureKind.UNSUPPORTED, "bsa.unsupported-entry-codec", null);
    return new Plan(version, embedded, familyCodec, options);
  }

  /**
   * The request-derived versioned BSA plan.
   *
   * @param version the wire version, 0x67, 0x68, or 0x69
   * @param embedded whether every stored record starts with its length-prefixed full name
   * @param familyCodec the one compressed codec the version supports
   * @param options the request's pack options, which select codecs and flag groups
   */
  record Plan(
      int version, boolean embedded, PackOptions.Compression familyCodec, PackOptions options)
      implements Admitted<WireName> {
    /**
     * Orders by folder hash, folder bytes, file hash, then file bytes, all unsigned. Equal folders
     * are therefore adjacent, which the folder-grouped tables rely on.
     */
    private static final Comparator<Planned<WireName>> OUTPUT_ORDER =
        (a, b) -> {
          WireName left = a.key(), right = b.key();
          int order = Long.compareUnsigned(left.folderHash(), right.folderHash());
          if (order == 0) order = Arrays.compareUnsigned(left.folder(), right.folder());
          if (order == 0) order = Long.compareUnsigned(left.nameHash(), right.nameHash());
          return order == 0 ? Arrays.compareUnsigned(left.name(), right.name()) : order;
        };

    /** Versioned BSA names are planned as ASCII. */
    @Override
    public Charset nameCharset() {
      return StandardCharsets.US_ASCII;
    }

    @Override
    public String diagnosticPrefix() {
      return "bsa";
    }

    @Override
    public long defaultSplitTarget() {
      return 2_147_483_647L;
    }

    @Override
    public Emission emission() {
      return Emission.STABILIZED;
    }

    /**
     * The advisory estimate is the stored record, a 200-byte allowance, and the folder and file
     * name bytes plus one separator. A shared record is still charged once per referencing entry.
     */
    @Override
    public SplitCost<WireName> splitCost() {
      return new SplitCost<>(
          200, name -> name.folder().length + 1L + name.name().length, PayloadCost.STORED);
    }

    /**
     * Sharing compares complete stored records per part: the embedded name and decoded-size prefix
     * are part of the record, and the compressed discriminator separates stored from compressed.
     */
    @Override
    public Sharing sharing() {
      return new Sharing(Sharing.Scope.PER_PART, Sharing.Basis.STORED);
    }

    /**
     * 0x69's LZ4 frame needs its native provider only when the request can select it, globally or
     * by an entry override; 0x67 and 0x68 compress with JDK zlib.
     */
    @Override
    public Set<Codec> requiredCodecs() {
      return version == 0x69
              && (options.compression() == familyCodec
                  || options.entryCompression().containsValue(familyCodec))
          ? Set.of(Codec.BSA_LZ4_FRAME)
          : Set.of();
    }

    /**
     * Applies the per-entry rules in Logical Plan Order: a folder and a file name, a folder of at
     * most 254 bytes, a file name with a stem, and an embedded name of at most 255 bytes, then the
     * u32 source size and, for a stored record, its exact wire size. Returns the entries in folder
     * hash order with their codec and frame.
     */
    @Override
    public List<Planned<WireName>> plan(List<PackSources.Entry> sources, IoContext context)
        throws ArchiveException {
      List<Planned<WireName>> planned = new ArrayList<>(sources.size());
      for (PackSources.Entry source : sources) {
        String identity = source.identity();
        boolean compressed =
            options
                    .entryCompression()
                    .getOrDefault(new NormalizedNameIdentity(identity), options.compression())
                == familyCodec;
        int separator = identity.lastIndexOf('\\');
        if (separator <= 0 || separator == identity.length() - 1)
          throw context.failure(FailureKind.POLICY, "bsa.invalid-encode-name", null);
        byte[] folder = identity.substring(0, separator).getBytes(StandardCharsets.US_ASCII);
        byte[] name = identity.substring(separator + 1).getBytes(StandardCharsets.US_ASCII);
        if (folder.length > 254
            || !BsaNames.hasStem(name)
            || (embedded && folder.length + 1L + name.length > 255))
          throw context.failure(FailureKind.POLICY, "bsa.invalid-encode-name", null);
        checkU32(source.size(), context);
        byte[] frame = frame(identity, compressed, source.size());
        if (!compressed) checkSize(frame.length + source.size(), context);
        planned.add(
            new Planned<>(
                source,
                new WireName(
                    folder,
                    name,
                    BsaNames.hash(folder, false, version),
                    BsaNames.hash(name, true, version)),
                compressed ? (version == 0x69 ? Codec.BSA_LZ4_FRAME : Codec.ZLIB) : Codec.STORED,
                frame));
      }
      planned.sort(OUTPUT_ORDER);
      return planned;
    }

    /**
     * Returns the bytes a stored record carries before its payload: the length-prefixed full name
     * when names are embedded, then the u32 decoded size when the payload is compressed. The name
     * is part of the record, so sharing can never alias records with different prefixes.
     */
    byte[] frame(String identity, boolean compressed, long decodedSize) {
      byte[] fullName = embedded ? identity.getBytes(StandardCharsets.US_ASCII) : new byte[0];
      ByteBuffer frame = little((embedded ? 1 + fullName.length : 0) + (compressed ? 4 : 0));
      if (embedded) frame.put((byte) fullName.length).put(fullName);
      if (compressed) frame.putInt((int) decodedSize);
      return frame.array();
    }

    /** Bit 30 of a stored size marks compression, so a stabilized record must leave it clear. */
    @Override
    public void checkStored(Planned<WireName> entry, IoContext processing) throws ArchiveException {
      checkSize(entry.storedSize(), processing);
    }

    /**
     * Checks one part's u32 table extents and every record start. The payload start follows the
     * 36-byte header, the folder records, each folder's length-prefixed name and file records, and
     * the file name table. Metadata also counts each record's frame, which is encoded name and size
     * metadata even though it follows the index.
     */
    @Override
    public Layout layout(List<Planned<WireName>> part, IoContext context) throws ArchiveException {
      long groups = 0, folderNames = 0, fileNames = 0, frames = 0;
      byte[] previous = null;
      for (Planned<WireName> entry : part) {
        byte[] folder = entry.key().folder();
        if (previous == null || !Arrays.equals(previous, folder)) {
          groups++;
          folderNames += folder.length + 1L;
          previous = folder;
        }
        fileNames += entry.key().name().length + 1L;
        frames += entry.frame().length;
      }
      long dataStart =
          36 + groups * (folderRecordSize() + 1L) + folderNames + part.size() * 16L + fileNames;
      checkU32(folderNames, context);
      checkU32(fileNames, context);
      checkU32(dataStart, context);
      long position = dataStart;
      for (Planned<WireName> entry : part) {
        checkU32(position, context);
        // Only starts are serialized as u32; the final payload may extend beyond four GiB.
        position = Math.addExact(position, entry.storedSize());
      }
      return new Layout(dataStart, dataStart + frames);
    }

    /**
     * Returns the header, folder records, each folder block (its length-prefixed name and file
     * records), and the file name table. File records carry absolute record offsets, so a shared
     * entry repeats its owner's offset, and bit 30 of a size marks a record whose compression
     * differs from the archive default.
     */
    @Override
    public List<Patch> tables(List<Placed<WireName>> part, Layout layout) {
      List<List<Placed<WireName>>> groups = new ArrayList<>();
      long folderNames = 0, fileNames = 0;
      for (Placed<WireName> entry : part) {
        byte[] folder = entry.planned().key().folder();
        if (groups.isEmpty()
            || !Arrays.equals(groups.getLast().getFirst().planned().key().folder(), folder)) {
          groups.add(new ArrayList<>());
          folderNames += folder.length + 1L;
        }
        groups.getLast().add(entry);
        fileNames += entry.planned().key().name().length + 1L;
      }
      long flags = archiveFlags(part);
      List<Patch> patches = new ArrayList<>();
      patches.add(
          new Patch(
              0,
              words(
                  0x00415342,
                  version,
                  36,
                  flags,
                  groups.size(),
                  part.size(),
                  folderNames,
                  fileNames,
                  fileFlags(part))));
      ByteBuffer folderRecords = little(groups.size() * folderRecordSize());
      ByteBuffer names = little(Math.toIntExact(fileNames));
      long block = 36 + groups.size() * (long) folderRecordSize();
      for (var group : groups) {
        WireName first = group.getFirst().planned().key();
        folderRecord(folderRecords, first.folderHash(), group.size(), block + fileNames);
        ByteBuffer records = little(1 + first.folder().length + 1 + 16 * group.size());
        records.put((byte) (first.folder().length + 1)).put(first.folder()).put((byte) 0);
        for (Placed<WireName> entry : group) {
          boolean compressed = entry.planned().codec() != Codec.STORED;
          long size = entry.storedSize() | ((((flags & 4) != 0) ^ compressed) ? 0x40000000L : 0);
          records
              .putLong(entry.planned().key().nameHash())
              .putInt((int) size)
              .putInt((int) entry.offset());
          names.put(entry.planned().key().name()).put((byte) 0);
        }
        patches.add(new Patch(block, records.array()));
        block += records.capacity();
      }
      patches.add(new Patch(36, folderRecords.array()));
      patches.add(new Patch(layout.payloadStart() - fileNames, names.array()));
      return patches;
    }

    /**
     * Derives the part's automatic archive flags, unless the request selected explicit ones:
     * directory and file names always, the 0x67 retain bits, compressed-by-default when any record
     * in the part is compressed, and the bits its file flags need. Those are the final file flags,
     * so an explicit file flag selection also decides bit 0x80.
     */
    long archiveFlags(List<Placed<WireName>> part) {
      if (options.archiveFlags() instanceof FlagSelection.Explicit explicit)
        return explicit.value();
      boolean retain = false;
      for (Placed<WireName> entry : part) {
        int classification = classify(entry.planned().source().identity());
        retain |= classification == 5 || classification == 9;
      }
      return (version == 0x67 ? 0x603 : 3)
          | (part.stream().anyMatch(entry -> entry.planned().codec() != Codec.STORED) ? 4 : 0)
          | ((fileFlags(part) & 1) != 0 ? 0x80 : 0)
          | (retain ? 0x10 : 0);
    }

    /** Returns the part's file-class flags, or the request's explicit selection. */
    long fileFlags(List<Placed<WireName>> part) {
      if (options.fileFlags() instanceof FlagSelection.Explicit explicit) return explicit.value();
      return automaticFileFlags(part);
    }

    /**
     * Classifies every name by root folder, then by final extension, and keeps only the classes
     * this version defines.
     */
    private long automaticFileFlags(List<Placed<WireName>> part) {
      long files = 0;
      for (Placed<WireName> entry : part) {
        files |= CONTRIBUTIONS[classify(entry.planned().source().identity())];
        if (version == 0x67
            && new String(entry.planned().key().name(), StandardCharsets.US_ASCII).endsWith(".xml"))
          files |= 4;
      }
      if (version != 0x67) files &= ~(4 | 0x20 | 0x80);
      if (version == 0x69) files &= ~0x100;
      return files;
    }

    /** 0x69 folder records carry mandatory zero padding after the count and the offset. */
    private int folderRecordSize() {
      return version == 0x69 ? 24 : 16;
    }

    /** Appends the family-specific folder record for one group. */
    private void folderRecord(ByteBuffer records, long hash, long count, long offset) {
      records.putLong(hash).putInt((int) count);
      if (version == 0x69) records.putInt(0).putInt((int) offset).putInt(0);
      else records.putInt((int) offset);
    }
  }

  /** Rejects impossible unsigned fields before narrowing. */
  private static void checkU32(long value, IoContext context) throws ArchiveException {
    if (value < 0 || value > 0xffff_ffffL)
      throw context.failure(FailureKind.POLICY, "bsa.wire-limit", null);
  }

  /** Bit 30 belongs to compression, even when the independent high bit is set. */
  private static void checkSize(long value, IoContext context) throws ArchiveException {
    checkU32(value, context);
    if ((value & 0x40000000L) != 0)
      throw context.failure(FailureKind.POLICY, "bsa.wire-limit", null);
  }

  /** Allocates a little-endian table buffer. */
  private static ByteBuffer little(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  /** Serializes checked unsigned header fields. */
  private static byte[] words(long... values) {
    ByteBuffer bytes = little(values.length * 4);
    for (long value : values) bytes.putInt((int) value);
    return bytes.array();
  }

  private static final String[] ROOTS = {
    "meshes",
    "textures",
    "materials",
    "geometries",
    "sound\\voice",
    "sound",
    "music",
    "scripts\\source",
    "source\\scripts",
    "scripts",
    "strings",
    "trees",
    "video",
    "lodsettings",
    "distantlod",
    "interface",
    "programs",
    "menus",
    "fonts",
    "facegen",
    "lsdata",
    "shaders",
    "shadersfx",
    "grass",
    "vis",
    "seq",
    "dialogueviews",
    "bookart",
    "icons",
    "splash"
  };
  private static final String[] EXTENSIONS = {
    ".nif .kf .kfm .egm .egt .tri .psa .hkt .hkx .ssf .btr .bto .btt .dtl",
    ".dds .tga .png",
    ".bgsm .bgem",
    ".mesh",
    ".lip .wav .xwm .mp3 .ogg .fuz",
    ".wav .xwm .ogg",
    ".xwm .mp3",
    ".psc",
    ".psc",
    ".pex .psc",
    ".strings .ilstrings .dlstrings",
    ".spt",
    ".bik .bk2",
    ".lodsettings .dlodsettings .lod",
    ".cmp .lod",
    ".swf .png .txt",
    ".swf",
    ".xml .htm .txt .scc .bat",
    ".fnt .tex",
    ".ctl",
    ".dat",
    ".sdp",
    ".fxp",
    ".gid",
    ".uvd",
    ".seq",
    ".xml",
    ".dds .tga",
    ".dds .tga",
    ".dds .tga"
  };
  private static final int[] CONTRIBUTIONS = {
    1, 2, 256, 256, 16, 8, 256, 256, 256, 256, 256, 64, 256, 257, 257, 256, 256, 32, 128, 256, 256,
    256, 256, 256, 256, 256, 256, 256, 256, 256, 256
  };

  /** Applies root precedence before the ordered final-extension fallback classifier. */
  static int classify(String name) {
    for (int i = 0; i < ROOTS.length; i++)
      if (name.equals(ROOTS[i]) || name.startsWith(ROOTS[i] + "\\")) return i;
    int dot = name.lastIndexOf('.');
    if (dot > name.lastIndexOf('\\')) {
      String extension = name.substring(dot);
      for (int i = 0; i < EXTENSIONS.length; i++)
        for (String candidate : EXTENSIONS[i].split(" ")) if (candidate.equals(extension)) return i;
    }
    return ROOTS.length;
  }
}
