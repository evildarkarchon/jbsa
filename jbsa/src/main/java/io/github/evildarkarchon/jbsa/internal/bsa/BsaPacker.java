package io.github.evildarkarchon.jbsa.internal.bsa;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Canonical sequential versioned-BSA writer with bounded stabilization before split planning. */
public final class BsaPacker {
  private BsaPacker() {}

  /** Plans complete names and exact encoded sizes before publishing any archive part. */
  public static OperationReport pack(PackRequest request, OperationControl control)
      throws ArchiveException {
    var operation =
        new OperationSession(
            Operation.PACK, request.resourceLimits(), request.diagnosticPolicy(), control);
    var context = IoContext.of(request.destination(), Operation.PACK);
    boolean publicationOwnsSession = false;
    SpillBuffer scratch = null;
    OperationReport result = null;
    var failures = new FailureRetention(request.resourceLimits(), Operation.PACK);
    ResourceBudget budget = ResourceBudget.forMutation(request.resourceLimits(), context);
    try {
      operation.begin();
      io.github.evildarkarchon.jbsa.internal.tes3.Tes3Names.encoding(
          request.compatibilityProfile(), context);
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
      PackOptions.Compression familyCodec =
          version == 0x69 ? PackOptions.Compression.LZ4_FRAME : PackOptions.Compression.ZLIB;
      boolean defaultCompressed = request.options().compression() == familyCodec;
      if (request.options().compression() != PackOptions.Compression.FAMILY_DEFAULT
          && request.options().compression() != PackOptions.Compression.STORED
          && !defaultCompressed)
        throw context.failure(FailureKind.UNSUPPORTED, "bsa.unsupported-codec", null);
      if (request.options().archiveFlags() instanceof FlagSelection.Explicit flags
          && ((flags.value() & 3) != 3
              || (flags.value() & ~(version == 0x67 ? 0x6bfL : version == 0x69 ? 0x7ffL : 0x7bfL))
                  != 0))
        throw context.failure(FailureKind.POLICY, "bsa.invalid-archive-flags", null);
      boolean embedded =
          version != 0x67
              && request.options().archiveFlags() instanceof FlagSelection.Explicit flags
              && (flags.value() & 0x100) != 0;
      for (var choice : request.options().entryCompression().values())
        if (choice != PackOptions.Compression.STORED && choice != familyCodec)
          throw context.failure(FailureKind.UNSUPPORTED, "bsa.unsupported-entry-codec", null);
      if (version == 0x69
          && (defaultCompressed || request.options().entryCompression().containsValue(familyCodec)))
        BsaLz4Frame.preflight("encode", context);
      Set<NormalizedNameIdentity> unmatched =
          new HashSet<>(request.options().entryCompression().keySet());
      List<Item> items = new ArrayList<>();
      long decoded = 0;
      for (PackSources.Entry source : PackSources.plan(request, operation, budget)) {
        unmatched.remove(new NormalizedNameIdentity(source.identity()));
        boolean compressed =
            request
                    .options()
                    .entryCompression()
                    .getOrDefault(
                        new NormalizedNameIdentity(source.identity()),
                        request.options().compression())
                == familyCodec;
        int separator = source.identity().lastIndexOf('\\');
        if (separator <= 0 || separator == source.identity().length() - 1)
          throw context.failure(FailureKind.POLICY, "bsa.invalid-encode-name", null);
        byte[] folder =
            source.identity().substring(0, separator).getBytes(StandardCharsets.US_ASCII);
        byte[] name =
            source.identity().substring(separator + 1).getBytes(StandardCharsets.US_ASCII);
        if (folder.length > 254
            || !BsaNames.hasStem(name)
            || (embedded && folder.length + 1L + name.length > 255))
          throw context.failure(FailureKind.POLICY, "bsa.invalid-encode-name", null);
        checkU32(source.size(), context);
        if (!compressed)
          checkSize(source.size() + (embedded ? folder.length + name.length + 2L : 0), context);
        if (source.size() > request.resourceLimits().maxDecodedBytes() - decoded)
          throw context.limit(
              "maxDecodedBytes",
              request.resourceLimits().maxDecodedBytes(),
              java.math.BigInteger.valueOf(decoded)
                  .add(java.math.BigInteger.valueOf(source.size()))
                  .toString());
        decoded += source.size();
        items.add(
            new Item(
                source,
                folder,
                name,
                BsaNames.hash(folder, false, version),
                BsaNames.hash(name, true, version),
                compressed));
      }
      if (!unmatched.isEmpty())
        throw context.failure(FailureKind.POLICY, "pack.unmatched-entry-compression", null);
      if (items.isEmpty()) throw context.failure(FailureKind.POLICY, "bsa.empty-entry-set", null);
      items.sort(
          (a, b) -> {
            int order = Long.compareUnsigned(a.folderHash, b.folderHash);
            if (order == 0) order = Arrays.compareUnsigned(a.folder, b.folder);
            if (order == 0) order = Long.compareUnsigned(a.nameHash, b.nameHash);
            return order == 0 ? Arrays.compareUnsigned(a.name, b.name) : order;
          });
      boolean anyCompressed = items.stream().anyMatch(item -> item.compressed);
      if (!anyCompressed) {
        for (Item item : items)
          item.size =
              item.source.size() + (embedded ? item.folder.length + item.name.length + 2L : 0);
        for (var part : split(items, request.options())) layout(part, version, context);
      }
      // One spool bounds heap and handle use independently of entry count, and fixes split sizes.
      scratch = SpillBuffer.open(Path.of(System.getProperty("java.io.tmpdir")), budget, context);
      SpillBuffer stable = scratch;
      try (var codecCredits =
          budget.reserve(
              anyCompressed && version != 0x69 ? JdkZlib.ENCODE_HEAP_BYTES : 65536,
              anyCompressed && version != 0x69 ? JdkZlib.ENCODE_NATIVE_BYTES : 0,
              0,
              0)) {
        for (int ordinal = 0; ordinal < items.size(); ordinal++) {
          Item item = items.get(ordinal);
          var processing =
              new IoContext(
                  context.path(),
                  Operation.PACK,
                  OperationPhase.PROCESSING,
                  OptionalLong.of(ordinal));
          item.offset = stable.size();
          long framing = item.offset;
          if (embedded) {
            // Include the name in the stabilized record so sharing cannot alias different prefixes.
            byte[] fullName = item.source.identity().getBytes(StandardCharsets.US_ASCII);
            stable.write(framing++, ByteBuffer.wrap(new byte[] {(byte) fullName.length}));
            stable.write(framing, ByteBuffer.wrap(fullName));
            framing += fullName.length;
          }
          if (item.compressed) stable.write(framing, words(item.source.size()));
          long start = framing + (item.compressed ? 4 : 0);
          PackSources.consume(
              item.source,
              input -> {
                if (!item.compressed) {
                  transfer(input, item.source.size(), stable, start, operation, processing);
                } else if (version == 0x69) {
                  long[] position = {0};
                  BsaLz4Frame.encode(
                      (offset, bytes) -> {
                        if (offset != position[0])
                          throw new IllegalStateException("Nonsequential source");
                        readExact(input, bytes, processing);
                        position[0] += bytes.position();
                      },
                      item.source.size(),
                      (offset, bytes) -> stable.write(start + offset, bytes),
                      () -> operation.checkpoint(OperationPhase.PROCESSING, processing.ordinal()),
                      budget,
                      processing);
                  requireEnd(input, processing);
                } else {
                  JdkZlib.encode(
                      input,
                      item.source.size(),
                      (offset, bytes) -> stable.write(start + offset, bytes),
                      () -> operation.checkpoint(OperationPhase.PROCESSING, processing.ordinal()),
                      processing);
                }
              },
              processing);
          item.size = stable.size() - item.offset;
          checkSize(item.size, processing);
        }
      }
      stable.seal();
      List<List<Item>> parts = split(items, request.options());
      for (var part : parts) budget.metadata(layout(part, version, context).dataStart);
      // Payload framing is encoded record/name metadata even though it follows the index.
      for (Item item : items)
        budget.metadata(
            (embedded ? item.folder.length + item.name.length + 2L : 0)
                + (item.compressed ? 4 : 0));
      var writers = new ArrayList<PublicationTransaction.Writer>();
      var completed = new ArrayList<OperationReport.ArchivePart>();
      for (var part : parts) {
        int number = writers.size() + 1;
        writers.add(
            output -> {
              write(part, stable, output, request.options(), version, context);
              // Stabilization is no longer needed after the last staged part; cleanup must precede
              // commit.
              if (number == parts.size()) stable.close();
              completed.add(
                  new OperationReport.ArchivePart(
                      PublicationTransaction.splitPath(
                          request.destination().toAbsolutePath().normalize(), number),
                      output.size(),
                      part.size()));
            });
      }
      // Sharing compares two windows; reserve their peak before any staged writer allocates them.
      try (var replayCredits = budget.reserve(132096, 0, 0, 0)) {
        publicationOwnsSession = true;
        var report =
            PublicationTransaction.archives(
                request.destination(),
                writers,
                request.targetPolicy(),
                request.resourceLimits(),
                operation,
                budget);
        result =
            new OperationReport(
                report.operation(),
                report.artifacts(),
                report.diagnostics(),
                report.assessment(),
                completed);
      }
    } catch (IOException failure) {
      failures.accept(
          failure instanceof ArchiveException archive
              ? archive
              : context.failure(FailureKind.SOURCE, "operation.source-io", failure));
    } finally {
      if (scratch != null)
        try {
          scratch.close();
        } catch (ArchiveException failure) {
          failures.accept(failure);
        }
      budget.close();
    }
    if (failures.failed()) {
      ArchiveException failure = failures.finish(result == null ? List.of() : result.artifacts());
      if (publicationOwnsSession) throw failure;
      operation.accept(failure);
      operation.cleanup();
      operation.cleaned(0);
      return operation.finish(List.of());
    }
    return result;
  }

  /** Uses the format's packed-byte advisory estimate without splitting a logical entry. */
  private static List<List<Item>> split(List<Item> items, PackOptions options) {
    long target =
        switch (options.splitting()) {
          case PackOptions.Splitting.FamilyDefault ignored -> 2147483647L;
          case PackOptions.Splitting.UpToBytes value -> value.targetBytes();
          case PackOptions.Splitting.LegacyPerEntry ignored -> 1L;
        };
    List<List<Item>> parts = new ArrayList<>();
    List<Item> current = new ArrayList<>();
    long estimate = 0;
    for (Item item : items) {
      long cost = item.size + 200 + item.folder.length + 1 + item.name.length;
      if (target != 0 && !current.isEmpty() && cost > target - estimate) {
        parts.add(List.copyOf(current));
        current.clear();
        estimate = 0;
      }
      current.add(item);
      estimate = Math.addExact(estimate, cost);
    }
    if (!current.isEmpty()) parts.add(List.copyOf(current));
    return parts;
  }

  /** Calculates serialized groups, table extents, and maximum payload position before staging. */
  private static Layout layout(List<Item> items, int version, IoContext context)
      throws ArchiveException {
    List<List<Item>> groups = new ArrayList<>();
    long folderNames = 0, fileNames = 0;
    for (Item item : items) {
      if (groups.isEmpty() || !Arrays.equals(groups.getLast().getFirst().folder, item.folder)) {
        groups.add(new ArrayList<>());
        folderNames += item.folder.length + 1L;
      }
      groups.getLast().add(item);
      fileNames += item.name.length + 1L;
    }
    int folderRecordSize = version == 0x69 ? 24 : 16;
    long dataStart =
        36 + groups.size() * (folderRecordSize + 1L) + folderNames + items.size() * 16L + fileNames;
    checkU32(folderNames, context);
    checkU32(fileNames, context);
    checkU32(dataStart, context);
    long position = dataStart;
    for (Item item : items) {
      checkU32(position, context);
      // Only starts are serialized as u32; the final payload may extend beyond four GiB.
      position = Math.addExact(position, item.size);
    }
    return new Layout(groups, folderNames, fileNames, dataStart);
  }

  /** Emits all tables in canonical group order and shares only exactly equal framed records. */
  private static void write(
      List<Item> items,
      SpillBuffer scratch,
      PublicationTransaction.StagedFile output,
      PackOptions options,
      int version,
      IoContext context)
      throws IOException {
    Layout layout = layout(items, version, context);
    long files = 0;
    boolean retain = false;
    for (Item item : items) {
      int classification = classify(item.source.identity());
      files |= CONTRIBUTIONS[classification];
      retain |= classification == 5 || classification == 9;
      if (version == 0x67 && new String(item.name, StandardCharsets.US_ASCII).endsWith(".xml"))
        files |= 4;
    }
    if (version != 0x67) files &= ~(4 | 0x20 | 0x80);
    if (version == 0x69) files &= ~0x100;
    if (options.fileFlags() instanceof FlagSelection.Explicit explicit) files = explicit.value();
    long flags =
        (version == 0x67 ? 0x603 : 3)
            | (items.stream().anyMatch(item -> item.compressed) ? 4 : 0)
            | ((files & 1) != 0 ? 0x80 : 0)
            | (retain ? 0x10 : 0);
    if (options.archiveFlags() instanceof FlagSelection.Explicit explicit) flags = explicit.value();
    output.reserve(layout.dataStart);
    output.write(
        0,
        words(
            0x00415342,
            version,
            36,
            flags,
            layout.groups.size(),
            items.size(),
            layout.folderNames,
            layout.fileNames,
            files));
    int folderRecordSize = version == 0x69 ? 24 : 16;
    long block = 36 + layout.groups.size() * (long) folderRecordSize,
        namePosition = layout.dataStart - layout.fileNames,
        payloadPosition = layout.dataStart;
    Map<String, List<Item>> emitted = new HashMap<>();
    int folderOrdinal = 0;
    for (var group : layout.groups) {
      Item first = group.getFirst();
      output.write(
          36 + folderOrdinal++ * (long) folderRecordSize,
          folderRecord(first.folderHash, group.size(), block + layout.fileNames, version));
      output.write(block++, ByteBuffer.wrap(new byte[] {(byte) (first.folder.length + 1)}));
      output.write(block, ByteBuffer.wrap(Arrays.copyOf(first.folder, first.folder.length + 1)));
      block += first.folder.length + 1L;
      for (Item item : group) {
        output.checkpoint();
        long destination = payloadPosition;
        Item shared = null;
        String key = options.sharing() ? digest(scratch, item.offset, item.size, output) : "";
        if (options.sharing())
          for (Item candidate : emitted.getOrDefault(key, List.of()))
            if (candidate.size == item.size
                && candidate.compressed == item.compressed
                && candidate.source.size() == item.source.size()
                && equal(scratch, candidate.offset, item.offset, item.size, output)) {
              shared = candidate;
              break;
            }
        if (shared == null) {
          copy(scratch, item.offset, item.size, output, destination);
          item.destination = destination;
          if (options.sharing())
            emitted.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
          payloadPosition += item.size;
        } else destination = shared.destination;
        long size = item.size | ((((flags & 4) != 0) ^ item.compressed) ? 0x40000000L : 0);
        output.write(block, record(item.nameHash, size, destination));
        block += 16;
        output.write(namePosition, ByteBuffer.wrap(Arrays.copyOf(item.name, item.name.length + 1)));
        namePosition += item.name.length + 1L;
        output.completedEntry(item.source.size());
      }
    }
  }

  /** Reads a declared source exactly, detecting excess bytes and stalled generated channels. */
  private static void transfer(
      ReadableByteChannel input,
      long size,
      SpillBuffer scratch,
      long start,
      OperationSession operation,
      IoContext context)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(65536);
    long count = 0;
    int idle = 0;
    while (true) {
      operation.checkpoint(OperationPhase.PROCESSING, context.ordinal());
      buffer
          .clear()
          .limit((int) Math.min(buffer.capacity(), size - count + (count == size ? 1 : 0)));
      int read = input.read(buffer);
      if (read < 0) break;
      if (read == 0) {
        if (++idle > 16) throw context.failure(FailureKind.SOURCE, "io.no-progress", null);
        continue;
      }
      idle = 0;
      if (read > size - count)
        throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      scratch.write(start + count, buffer.flip());
      count += read;
    }
    if (count != size) throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
  }

  /** Fills one codec source window while bounding stalled-channel retries. */
  private static void readExact(ReadableByteChannel input, ByteBuffer bytes, IoContext context)
      throws IOException {
    int idle = 0;
    while (bytes.hasRemaining()) {
      int count = input.read(bytes);
      if (count < 0) throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      if (count == 0) {
        if (++idle > 16) throw context.failure(FailureKind.SOURCE, "io.no-progress", null);
      } else {
        idle = 0;
      }
    }
  }

  /** Probes once beyond the declared generated length so LZ4 cannot hide excess source bytes. */
  private static void requireEnd(ReadableByteChannel input, IoContext context) throws IOException {
    ByteBuffer probe = ByteBuffer.allocate(1);
    int idle = 0;
    while (true) {
      int count = input.read(probe);
      if (count < 0) return;
      if (count > 0) throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      if (++idle > 16) throw context.failure(FailureKind.SOURCE, "io.no-progress", null);
    }
  }

  /** Compares stable record bytes in a fixed window before reusing any payload offset. */
  private static boolean equal(
      SpillBuffer scratch,
      long left,
      long right,
      long size,
      PublicationTransaction.StagedFile output)
      throws IOException {
    ByteBuffer a = ByteBuffer.allocate(65536), b = ByteBuffer.allocate(65536);
    for (long offset = 0; offset < size; ) {
      output.checkpoint();
      int count = (int) Math.min(a.capacity(), size - offset);
      a.clear().limit(count);
      b.clear().limit(count);
      scratch.read(left + offset, a);
      scratch.read(right + offset, b);
      if (!a.flip().equals(b.flip())) return false;
      offset += count;
    }
    return true;
  }

  /** Shortlists exact-size sharing candidates without treating a digest as byte equality. */
  private static String digest(
      SpillBuffer scratch, long position, long size, PublicationTransaction.StagedFile output)
      throws IOException {
    java.security.MessageDigest digest;
    try {
      digest = java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
    ByteBuffer bytes = ByteBuffer.allocate(65536);
    for (long offset = 0; offset < size; ) {
      output.checkpoint();
      int count = (int) Math.min(bytes.capacity(), size - offset);
      bytes.clear().limit(count);
      scratch.read(position + offset, bytes);
      digest.update(bytes.flip());
      offset += count;
    }
    return size + ":" + HexFormat.of().formatHex(digest.digest());
  }

  /** Replays stable payloads while observing cancellation at every bounded write. */
  private static void copy(
      SpillBuffer scratch,
      long source,
      long size,
      PublicationTransaction.StagedFile output,
      long target)
      throws IOException {
    ByteBuffer bytes = ByteBuffer.allocate(65536);
    for (long offset = 0; offset < size; ) {
      output.checkpoint();
      int count = (int) Math.min(bytes.capacity(), size - offset);
      bytes.clear().limit(count);
      scratch.read(source + offset, bytes);
      output.write(target + offset, bytes.flip());
      offset += count;
    }
  }

  /** Rejects impossible unsigned fields before narrowing. */
  private static void checkU32(long value, IoContext context) throws ArchiveException {
    if (value < 0 || value > 0xffffffffL)
      throw context.failure(FailureKind.POLICY, "bsa.wire-limit", null);
  }

  /** Bit 30 belongs to compression, even when the independent high bit is set. */
  private static void checkSize(long value, IoContext context) throws ArchiveException {
    checkU32(value, context);
    if ((value & 0x40000000L) != 0)
      throw context.failure(FailureKind.POLICY, "bsa.wire-limit", null);
  }

  /** Serializes checked unsigned header fields. */
  private static ByteBuffer words(long... values) {
    ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (long value : values) bytes.putInt((int) value);
    return bytes.flip();
  }

  /** Serializes one folder or file record with its unsigned hash. */
  private static ByteBuffer record(long hash, long value, long offset) {
    return ByteBuffer.allocate(16)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(hash)
        .putInt((int) value)
        .putInt((int) offset)
        .flip();
  }

  /** Serializes the family-specific folder record, including mandatory zero 0x69 padding. */
  private static ByteBuffer folderRecord(long hash, long count, long offset, int version) {
    if (version != 0x69) return record(hash, count, offset);
    return ByteBuffer.allocate(24)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(hash)
        .putInt((int) count)
        .putInt(0)
        .putInt((int) offset)
        .putInt(0)
        .flip();
  }

  /** Retains canonical metadata and stabilized source coordinates without payload arrays. */
  private static final class Item {
    final PackSources.Entry source;
    final byte[] folder, name;
    final long folderHash, nameHash;
    final boolean compressed;
    long offset, size, destination;

    Item(
        PackSources.Entry source,
        byte[] folder,
        byte[] name,
        long folderHash,
        long nameHash,
        boolean compressed) {
      this.source = source;
      this.folder = folder;
      this.name = name;
      this.folderHash = folderHash;
      this.nameHash = nameHash;
      this.compressed = compressed;
    }
  }

  private record Layout(
      List<List<Item>> groups, long folderNames, long fileNames, long dataStart) {}

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
  private static int classify(String name) {
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
