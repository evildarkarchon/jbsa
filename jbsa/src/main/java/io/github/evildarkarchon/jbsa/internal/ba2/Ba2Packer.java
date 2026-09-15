package io.github.evildarkarchon.jbsa.internal.ba2;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.dds.DdsEnvelope;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Canonical sequential BA2 writer with bounded stabilization before split planning. */
public final class Ba2Packer {
  private Ba2Packer() {}

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
    boolean dds = request.family() == ArchiveFamily.FO4_DDS_BA2;
    boolean starfieldGeneral = request.family() == ArchiveFamily.STARFIELD_GENERAL_BA2;
    try {
      operation.begin();
      var encoding =
          io.github.evildarkarchon.jbsa.internal.tes3.Tes3Names.encoding(
              request.compatibilityProfile(), context);
      boolean rawLz4 =
          starfieldGeneral
              && (request.options().compression() == PackOptions.Compression.LZ4_RAW
                  || request
                      .options()
                      .entryCompression()
                      .containsValue(PackOptions.Compression.LZ4_RAW));
      boolean zlib =
          request.options().compression() == PackOptions.Compression.ZLIB
              || request.options().entryCompression().containsValue(PackOptions.Compression.ZLIB);
      if (rawLz4 && zlib)
        throw context.failure(FailureKind.UNSUPPORTED, "ba2.mixed-compressed-codecs", null);
      ArchiveEncoding archiveEncoding =
          new ArchiveEncoding(
              Optional.of(new WireVersion(starfieldGeneral ? (rawLz4 ? 3 : 2) : 1)),
              Optional.of(dds ? Ba2Subtype.DX10 : Ba2Subtype.GNRL),
              rawLz4 ? OptionalLong.of(3) : OptionalLong.empty());
      if (!request.encoding().equals(archiveEncoding))
        throw context.failure(FailureKind.UNSUPPORTED, "archive.unsupported-encoding", null);
      if (request.options().archiveFlags() instanceof FlagSelection.Explicit
          || request.options().fileFlags() instanceof FlagSelection.Explicit)
        throw context.failure(FailureKind.UNSUPPORTED, "ba2.flags-inapplicable", null);
      if (request.options().compression() != PackOptions.Compression.FAMILY_DEFAULT
          && request.options().compression() != PackOptions.Compression.STORED
          && request.options().compression() != PackOptions.Compression.ZLIB
          && (!starfieldGeneral
              || request.options().compression() != PackOptions.Compression.LZ4_RAW))
        throw context.failure(FailureKind.UNSUPPORTED, "ba2.unsupported-codec", null);
      for (var choice : request.options().entryCompression().values())
        if (choice != PackOptions.Compression.STORED
            && choice != PackOptions.Compression.ZLIB
            && (!starfieldGeneral || choice != PackOptions.Compression.LZ4_RAW))
          throw context.failure(FailureKind.UNSUPPORTED, "ba2.unsupported-entry-codec", null);
      if (dds
          && (request.options().compression() == PackOptions.Compression.STORED
              || request
                  .options()
                  .entryCompression()
                  .containsValue(PackOptions.Compression.STORED)))
        throw context.failure(FailureKind.UNSUPPORTED, "dx10.stored-encode", null);
      if (rawLz4) Lz4Runtime.preflight("raw-lz4", "encode", context);
      Set<NormalizedNameIdentity> unmatched =
          new HashSet<>(request.options().entryCompression().keySet());
      List<Item> items = new ArrayList<>();
      Set<NormalizedNameIdentity> wireIdentities = new HashSet<>();
      long decoded = 0;
      for (PackSources.Entry source : PackSources.plan(request, operation, budget, encoding)) {
        unmatched.remove(new NormalizedNameIdentity(source.identity()));
        PackOptions.Compression selected =
            request
                .options()
                .entryCompression()
                .getOrDefault(
                    new NormalizedNameIdentity(source.identity()), request.options().compression());
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
          ByteBuffer encoded = encoding.newEncoder().encode(CharBuffer.wrap(display));
          name = new byte[encoded.remaining()];
          encoded.get(name);
        } catch (java.nio.charset.CharacterCodingException failure) {
          throw context.failure(FailureKind.POLICY, "ba2.invalid-encode-name", failure);
        }
        if (name.length > 65535 || !Ba2Names.ascii(name))
          throw context.failure(FailureKind.POLICY, "ba2.invalid-encode-name", null);
        // An ANSI alias can become ASCII punctuation (for example yen becomes a separator).
        // Revalidate the actual wire spelling before payload access, including alias collisions.
        for (int index = 0; index < name.length; index++)
          if (name[index] == '\\') name[index] = '/';
        String wireDisplay = new String(name, StandardCharsets.US_ASCII);
        var wireIdentity = NormalizedNameIdentity.from(wireDisplay, encoding);
        int wireSeparator = wireDisplay.lastIndexOf('/');
        if (wireIdentity.isEmpty()
            || wireSeparator <= 0
            || wireSeparator == wireDisplay.length() - 1)
          throw context.failure(FailureKind.POLICY, "ba2.invalid-encode-name", null);
        if (!wireIdentities.add(wireIdentity.orElseThrow()))
          throw context.failure(FailureKind.POLICY, "ba2.duplicate-encode-name", null);
        if (!dds) checkU32(source.size(), context);
        if (!dds && source.size() > request.resourceLimits().maxDecodedBytes() - decoded)
          throw context.limit(
              "maxDecodedBytes",
              request.resourceLimits().maxDecodedBytes(),
              java.math.BigInteger.valueOf(decoded)
                  .add(java.math.BigInteger.valueOf(source.size()))
                  .toString());
        if (!dds) decoded += source.size();
        // DDS adds bounded partition/header state beyond the shared source-entry allowance.
        if (dds) budget.reserve(2048, 0, 0, 0);
        items.add(new Item(source, name, Ba2Names.identity(name), compressed));
      }
      if (!unmatched.isEmpty())
        throw context.failure(FailureKind.POLICY, "pack.unmatched-entry-compression", null);
      if (items.isEmpty()) throw context.failure(FailureKind.POLICY, "ba2.empty-entry-set", null);
      boolean anyCompressed = items.stream().anyMatch(item -> item.compressed);
      boolean zlibCompressed = anyCompressed && !rawLz4;
      // One spool bounds heap and handle use independently of entry count, and fixes split sizes.
      scratch = SpillBuffer.open(Path.of(System.getProperty("java.io.tmpdir")), budget, context);
      SpillBuffer stable = scratch;
      try (var codecCredits =
          budget.reserve(
              zlibCompressed ? JdkZlib.ENCODE_HEAP_BYTES + 131072 : 196608,
              zlibCompressed ? JdkZlib.ENCODE_NATIVE_BYTES : 0,
              0,
              0)) {
        Map<String, List<Item>> candidates = new HashMap<>();
        Map<String, List<Chunk>> chunkCandidates = new HashMap<>();
        for (int ordinal = 0; ordinal < items.size(); ordinal++) {
          Item item = items.get(ordinal);
          var processing =
              new IoContext(
                  context.path(),
                  Operation.PACK,
                  OperationPhase.PROCESSING,
                  OptionalLong.of(ordinal));
          item.rawOffset = stable.size();
          PackSources.consume(
              item.source,
              input ->
                  transfer(
                      input, item.source.size(), stable, item.rawOffset, operation, processing),
              processing);
          item.offset = item.rawOffset;
          item.size = item.source.size();
          if (dds) {
            stabilizeDds(item, stable, request, operation, processing, budget, chunkCandidates);
            long entryDecoded =
                item.texture.payloadSize()
                    + DdsEnvelope.canonicalHeader(
                            item.texture.width(),
                            item.texture.height(),
                            item.texture.mipCount(),
                            item.texture.dxgiFormat(),
                            item.texture.cubemap(),
                            item.texture.tileMode(),
                            request.ddsTarget().orElseThrow(),
                            processing)
                        .length;
            if (entryDecoded > request.resourceLimits().maxDecodedBytes() - decoded)
              throw processing.limit(
                  "maxDecodedBytes",
                  request.resourceLimits().maxDecodedBytes(),
                  java.math.BigInteger.valueOf(decoded)
                      .add(java.math.BigInteger.valueOf(entryDecoded))
                      .toString());
            decoded += entryDecoded;
            continue;
          }
          if (request.options().sharing()) {
            String key = digest(stable, item.rawOffset, item.source.size(), operation);
            for (Item previous : candidates.getOrDefault(key, List.of())) {
              if (previous.source.size() == item.source.size()
                  && equal(
                      stable, previous.rawOffset, item.rawOffset, item.source.size(), operation)) {
                item.owner = previous.owner;
                break;
              }
            }
            // Digest collisions cannot establish sharing; retain only byte-distinct owners.
            if (item.owner == item)
              candidates.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
          }
          if (item.owner == item && item.compressed) {
            item.offset = stable.size();
            final long start = item.offset;
            try (ReadableByteChannel input =
                new ReadableByteChannel() {
                  long position;

                  /** Reads stabilized bytes without invoking the caller's payload factory twice. */
                  public int read(ByteBuffer bytes) throws IOException {
                    if (position == item.source.size()) return -1;
                    int count = (int) Math.min(bytes.remaining(), item.source.size() - position);
                    ByteBuffer window = bytes.slice();
                    window.limit(count);
                    stable.read(item.rawOffset + position, window);
                    bytes.position(bytes.position() + count);
                    position += count;
                    return count;
                  }

                  /** The operation controls the scratch lifetime. */
                  public boolean isOpen() {
                    return true;
                  }

                  /** Leaves the operation-owned spool available for replay. */
                  public void close() {
                    /* The operation owns scratch cleanup. */
                  }
                }) {
              if (rawLz4)
                Lz4Raw.encode(
                    (offset, bytes) -> stable.read(item.rawOffset + offset, bytes),
                    item.source.size(),
                    (offset, bytes) -> stable.write(start + offset, bytes),
                    () -> operation.checkpoint(OperationPhase.PROCESSING, processing.ordinal()),
                    budget,
                    processing,
                    12);
              else
                JdkZlib.encode(
                    input,
                    item.source.size(),
                    (offset, bytes) -> stable.write(start + offset, bytes),
                    () -> operation.checkpoint(OperationPhase.PROCESSING, processing.ordinal()),
                    processing);
            }
            item.size = stable.size() - item.offset;
            checkU32(item.size, processing);
          }
        }
      }
      stable.seal();
      List<List<Item>> parts = split(items, request.options());
      long headerSize =
          GeneralBa2Layout.headerSize(archiveEncoding.wireVersion().orElseThrow().value());
      for (var part : parts)
        budget.metadata(
            headerSize + part.stream().mapToLong(i -> i.recordSize() + i.name.length + 2L).sum());
      var writers = new ArrayList<PublicationTransaction.Writer>();
      var completed = new ArrayList<OperationReport.ArchivePart>();
      for (var part : parts) {
        int number = writers.size() + 1;
        writers.add(
            new PublicationTransaction.Writer() {
              /** Replays this independent part before the transaction may publish any sibling. */
              @Override
              public void write(PublicationTransaction.StagedFile output) throws IOException {
                Ba2Packer.write(part, stable, output, dds, archiveEncoding);
                // Stabilization is no longer needed after the last staged part; cleanup must
                // precede
                // commit.
                if (number == parts.size()) stable.close();
                completed.add(
                    new OperationReport.ArchivePart(
                        PublicationTransaction.splitPath(
                            request.destination().toAbsolutePath().normalize(), number),
                        output.size(),
                        part.size()));
              }

              /**
               * Checks canonical structure and every decoded payload after the staged handle
               * closes.
               */
              @Override
              public void validate(Path staged) throws IOException {
                long metadataBytes =
                    headerSize
                        + part.stream().mapToLong(Item::recordSize).sum()
                        + part.stream().mapToLong(item -> item.name.length + 2L).sum();
                boolean validatesRawLz4 = rawLz4 && part.stream().anyMatch(item -> item.compressed);
                long validationCodecHeap =
                    validatesRawLz4
                        ? 4096 + part.stream().mapToLong(item -> item.source.size()).max().orElse(0)
                        : JdkZlib.DECODE_HEAP_BYTES;
                long validationCodecNative =
                    validatesRawLz4 ? Lz4Raw.DECODE_NATIVE_BYTES : JdkZlib.DECODE_NATIVE_BYTES;
                // The reader owns its own budget, so admit its peak against the still-live pack
                // budget as well; readback must not silently exceed the operation's capacity.
                try (var validationCredits =
                        budget.reserve(
                            512L * part.size() + 8L * metadataBytes + 65536 + validationCodecHeap,
                            validationCodecNative,
                            1,
                            0);
                    var archive =
                        ArchiveReaders.open(
                            staged,
                            new OpenOptions(
                                request.compatibilityProfile(),
                                request.resourceLimits(),
                                request.ddsTarget()),
                            Operation.PACK,
                            request.diagnosticPolicy())) {
                  if (archive.inspection().assessment().disposition()
                      != ArchiveDisposition.CONFORMING)
                    throw context.failure(
                        FailureKind.INTERNAL, "ba2.noncanonical-staged-output", null);
                  ByteBuffer window = ByteBuffer.allocate(65536);
                  for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
                    try (var content = archive.entry(ordinal).openContent()) {
                      do {
                        operation.checkpoint(OperationPhase.PROCESSING, OptionalLong.of(ordinal));
                        window.clear();
                      } while (content.read(window) >= 0);
                    }
                  }
                }
              }
            });
      }
      // Reserve the bounded replay and validation windows before staging allocates either.
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

  /** Assigns whole logical entries using each part's unique transformed payload cost. */
  private static List<List<Item>> split(List<Item> items, PackOptions options) {
    long target =
        switch (options.splitting()) {
          case PackOptions.Splitting.FamilyDefault ignored -> 0L;
          case PackOptions.Splitting.UpToBytes value -> value.targetBytes();
          case PackOptions.Splitting.LegacyPerEntry ignored -> 1L;
        };
    List<List<Item>> parts = new ArrayList<>();
    List<Item> current = new ArrayList<>();
    Set<Object> owners = new HashSet<>();
    long estimate = 0;
    for (Item item : items) {
      long overhead = 200L + item.source.displayName().length();
      long cost = overhead + uniqueCost(item, owners);
      if (target != 0 && !current.isEmpty() && cost > target - estimate) {
        parts.add(List.copyOf(current));
        current.clear();
        owners.clear();
        estimate = 0;
        cost = overhead + uniqueCost(item, owners);
      }
      current.add(item);
      if (item.texture == null) owners.add(item.owner);
      else for (Chunk chunk : item.chunks) owners.add(chunk.owner);
      estimate = Math.addExact(estimate, cost);
    }
    if (!current.isEmpty()) parts.add(List.copyOf(current));
    return parts;
  }

  /** Emits record-order payloads and a trailing case-preserving filename table. */
  private static void write(
      List<Item> items,
      SpillBuffer scratch,
      PublicationTransaction.StagedFile output,
      boolean dds,
      ArchiveEncoding encoding)
      throws IOException {
    long version = encoding.wireVersion().orElseThrow().value();
    int headerSize = GeneralBa2Layout.headerSize(version);
    long payload = headerSize + items.stream().mapToLong(Item::recordSize).sum();
    output.reserve(payload);
    Map<Object, Long> emitted = new IdentityHashMap<>();
    long recordPosition = headerSize;
    for (int ordinal = 0; ordinal < items.size(); ordinal++) {
      Item item = items.get(ordinal), owner = item.owner;
      output.checkpoint();
      if (dds) {
        var texture = item.texture;
        ByteBuffer record =
            ByteBuffer.allocate((int) item.recordSize()).order(ByteOrder.LITTLE_ENDIAN);
        record
            .putInt((int) item.identity.baseNameHash())
            .put(item.identity.extension().bytes())
            .putInt((int) item.identity.directoryHash())
            .put((byte) 0)
            .put((byte) item.chunks.size())
            .putShort((short) 24)
            .putShort((short) texture.height())
            .putShort((short) texture.width())
            .put((byte) texture.mipCount())
            .put((byte) texture.dxgiFormat())
            .put((byte) (texture.cubemap() ? 1 : 0))
            .put((byte) texture.tileMode());
        for (Chunk chunk : item.chunks) {
          Long destination = emitted.get(chunk.owner);
          if (destination == null) {
            destination = payload;
            copy(scratch, chunk.owner.offset, chunk.owner.size, output, destination);
            emitted.put(chunk.owner, destination);
            payload = Math.addExact(payload, chunk.owner.size);
          }
          record
              .putLong(destination)
              .putInt((int) chunk.owner.size)
              .putInt((int) chunk.rawSize)
              .putShort((short) chunk.startMip)
              .putShort((short) chunk.endMip)
              .putInt(0xbaadf00d);
        }
        output.write(recordPosition, record.flip());
        recordPosition += item.recordSize();
        output.completedEntry(item.source.size());
        continue;
      }
      Long destination = emitted.get(owner);
      if (destination == null) {
        destination = payload;
        copy(scratch, owner.offset, owner.size, output, destination);
        emitted.put(owner, destination);
        payload = Math.addExact(payload, owner.size);
      }
      output.write(
          headerSize + 36L * ordinal,
          ByteBuffer.allocate(36)
              .order(ByteOrder.LITTLE_ENDIAN)
              .putInt((int) item.identity.baseNameHash())
              .put(item.identity.extension().bytes())
              .putInt((int) item.identity.directoryHash())
              .put((byte) 0)
              .put((byte) 1)
              .putShort((short) 16)
              .putLong(destination)
              .putInt(owner.compressed ? (int) owner.size : 0)
              .putInt((int) item.source.size())
              .putInt(0xbaadf00d)
              .flip());
      output.completedEntry(item.source.size());
    }
    ByteBuffer header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
    header
        .putInt(0x58445442)
        .putInt((int) version)
        .putInt(dds ? 0x30315844 : 0x4c524e47)
        .putInt(items.size())
        .putLong(payload);
    if (version >= 2) header.putLong(1);
    if (version == 3) header.putInt(3);
    output.write(0, header.flip());
    for (Item item : items) {
      output.write(
          payload,
          ByteBuffer.allocate(2)
              .order(ByteOrder.LITTLE_ENDIAN)
              .putShort((short) item.name.length)
              .flip());
      output.write(payload + 2, ByteBuffer.wrap(item.name));
      payload += 2L + item.name.length;
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

  /** Compares stable record bytes in a fixed window before reusing any payload offset. */
  private static boolean equal(
      SpillBuffer scratch, long left, long right, long size, OperationSession operation)
      throws IOException {
    ByteBuffer a = ByteBuffer.allocate(65536), b = ByteBuffer.allocate(65536);
    for (long offset = 0; offset < size; ) {
      operation.checkpoint(OperationPhase.PROCESSING, OptionalLong.empty());
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
      SpillBuffer scratch, long position, long size, OperationSession operation)
      throws IOException {
    java.security.MessageDigest digest;
    try {
      digest = java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
    ByteBuffer bytes = ByteBuffer.allocate(65536);
    for (long offset = 0; offset < size; ) {
      operation.checkpoint(OperationPhase.PROCESSING, OptionalLong.empty());
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
      throw context.failure(FailureKind.POLICY, "ba2.wire-limit", null);
  }

  /**
   * Keeps bounded metadata and stable coordinates; the earliest equal entry owns representation.
   */
  private static final class Item {
    final PackSources.Entry source;
    final byte[] name;
    final EntryMetadata.Ba2Identity identity;
    final boolean compressed;
    Item owner = this;
    long rawOffset, offset, size;
    DdsEnvelope.Analysis texture;
    final List<Chunk> chunks = new ArrayList<>();

    /** Returns the variable DX10 record extent or the fixed General record extent. */
    long recordSize() {
      return texture == null ? 36 : 24 + 24L * chunks.size();
    }

    /** Retains the plan's original spelling and codec choice until stabilization. */
    Item(
        PackSources.Entry source,
        byte[] name,
        EntryMetadata.Ba2Identity identity,
        boolean compressed) {
      this.source = source;
      this.name = name;
      this.identity = identity;
      this.compressed = compressed;
    }
  }

  /** Tracks independently shareable chunks without coupling sharing to a texture's mip metadata. */
  private static final class Chunk {
    final long rawOffset, rawSize;
    final int startMip, endMip;
    long offset, size;
    Chunk owner = this;

    /** Retains normalized source coordinates until encoded bytes are stable. */
    Chunk(long rawOffset, long rawSize, int startMip, int endMip) {
      this.rawOffset = rawOffset;
      this.rawSize = rawSize;
      this.startMip = startMip;
      this.endMip = endMip;
    }
  }

  /** Charges each shared representation only once within a split part. */
  private static long uniqueCost(Item item, Set<Object> owners) {
    if (item.texture == null) return owners.contains(item.owner) ? 0 : item.owner.size;
    Set<Chunk> counted = new HashSet<>();
    long cost = 0;
    for (Chunk chunk : item.chunks)
      if (!owners.contains(chunk.owner) && counted.add(chunk.owner))
        cost = Math.addExact(cost, chunk.owner.size);
    return cost;
  }

  /** Validates the DDS envelope before independently encoding every normalized mip partition. */
  private static void stabilizeDds(
      Item item,
      SpillBuffer stable,
      PackRequest request,
      OperationSession operation,
      IoContext context,
      ResourceBudget budget,
      Map<String, List<Chunk>> candidates)
      throws IOException {
    ByteBuffer header = ByteBuffer.allocate((int) Math.min(164, item.source.size()));
    stable.read(item.rawOffset, header);
    item.texture =
        DdsEnvelope.analyze(
            header.flip(), item.source.size(), request.ddsTarget().orElseThrow(), context);
    budget.metadata(item.texture.headerSize());
    long payloadStart = item.rawOffset + item.texture.headerSize();
    if (item.texture.normalizeBgr24()) {
      // Append normalization so source and destination cannot overlap during BGR expansion.
      long normalized = stable.size();
      ByteBuffer input = ByteBuffer.allocate(49152), output = ByteBuffer.allocate(65536);
      long sourceBytes = item.source.size() - item.texture.headerSize();
      for (long position = 0, written = 0; position < sourceBytes; ) {
        operation.checkpoint(OperationPhase.PROCESSING, context.ordinal());
        int count = (int) Math.min(input.capacity(), sourceBytes - position);
        input.clear().limit(count);
        stable.read(payloadStart + position, input);
        input.flip();
        output.clear();
        while (input.hasRemaining())
          output.put(input.get()).put(input.get()).put(input.get()).put((byte) 255);
        int expanded = output.position();
        stable.write(normalized + written, output.flip());
        position += count;
        written += expanded;
      }
      payloadStart = normalized;
    }
    for (var slice : item.texture.chunks()) {
      Chunk chunk =
          new Chunk(payloadStart + slice.offset(), slice.size(), slice.startMip(), slice.endMip());
      checkU32(chunk.rawSize, context);
      if (request.options().sharing()) {
        String key = digest(stable, chunk.rawOffset, chunk.rawSize, operation);
        for (Chunk previous : candidates.getOrDefault(key, List.of()))
          if (previous.rawSize == chunk.rawSize
              && equal(stable, previous.rawOffset, chunk.rawOffset, chunk.rawSize, operation)) {
            chunk.owner = previous;
            break;
          }
        if (chunk.owner == chunk)
          candidates.computeIfAbsent(key, ignored -> new ArrayList<>()).add(chunk);
      }
      if (chunk.owner == chunk) {
        chunk.offset = stable.size();
        try (var input = sliceChannel(stable, chunk.rawOffset, chunk.rawSize)) {
          JdkZlib.encode(
              input,
              chunk.rawSize,
              (offset, bytes) -> stable.write(chunk.offset + offset, bytes),
              () -> operation.checkpoint(OperationPhase.PROCESSING, context.ordinal()),
              context);
        }
        chunk.size = stable.size() - chunk.offset;
        checkU32(chunk.size, context);
      }
      item.chunks.add(chunk);
    }
  }

  /** Exposes one bounded spool slice without transferring ownership of the operation's scratch. */
  private static ReadableByteChannel sliceChannel(SpillBuffer stable, long start, long size) {
    return new ReadableByteChannel() {
      long position;

      /** Reads at most the remaining slice; EOF cannot consume an adjacent mip. */
      public int read(ByteBuffer bytes) throws IOException {
        if (position == size) return -1;
        int count = (int) Math.min(bytes.remaining(), size - position);
        ByteBuffer window = bytes.slice();
        window.limit(count);
        stable.read(start + position, window);
        bytes.position(bytes.position() + count);
        position += count;
        return count;
      }

      public boolean isOpen() {
        return true;
      }

      /** The pack operation retains the spool for publication replay. */
      public void close() {
        /* Scratch cleanup belongs to the operation. */
      }
    };
  }
}
