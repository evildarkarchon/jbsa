package io.github.evildarkarchon.jbsa.internal.ba2;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Canonical sequential Fallout 4 General BA2 writer with bounded stabilization before split
 * planning.
 */
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
    try {
      operation.begin();
      var encoding =
          io.github.evildarkarchon.jbsa.internal.tes3.Tes3Names.encoding(
              request.compatibilityProfile(), context);
      if (!request
          .encoding()
          .equals(
              new ArchiveEncoding(
                  Optional.of(new WireVersion(1)),
                  Optional.of(Ba2Subtype.GNRL),
                  OptionalLong.empty())))
        throw context.failure(FailureKind.UNSUPPORTED, "archive.unsupported-encoding", null);
      boolean defaultCompressed = request.options().compression() == PackOptions.Compression.ZLIB;
      if (request.options().archiveFlags() instanceof FlagSelection.Explicit
          || request.options().fileFlags() instanceof FlagSelection.Explicit)
        throw context.failure(FailureKind.UNSUPPORTED, "ba2.flags-inapplicable", null);
      if (request.options().compression() != PackOptions.Compression.FAMILY_DEFAULT
          && request.options().compression() != PackOptions.Compression.STORED
          && !defaultCompressed)
        throw context.failure(FailureKind.UNSUPPORTED, "ba2.unsupported-codec", null);
      for (var choice : request.options().entryCompression().values())
        if (choice != PackOptions.Compression.STORED && choice != PackOptions.Compression.ZLIB)
          throw context.failure(FailureKind.UNSUPPORTED, "ba2.unsupported-entry-codec", null);
      Set<NormalizedNameIdentity> unmatched =
          new HashSet<>(request.options().entryCompression().keySet());
      List<Item> items = new ArrayList<>();
      Set<NormalizedNameIdentity> wireIdentities = new HashSet<>();
      long decoded = 0;
      for (PackSources.Entry source : PackSources.plan(request, operation, budget, encoding)) {
        unmatched.remove(new NormalizedNameIdentity(source.identity()));
        boolean compressed =
            request
                    .options()
                    .entryCompression()
                    .getOrDefault(
                        new NormalizedNameIdentity(source.identity()),
                        request.options().compression())
                == PackOptions.Compression.ZLIB;
        String display = source.displayName().replace('\\', '/');
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
        checkU32(source.size(), context);
        if (source.size() > request.resourceLimits().maxDecodedBytes() - decoded)
          throw context.limit(
              "maxDecodedBytes",
              request.resourceLimits().maxDecodedBytes(),
              java.math.BigInteger.valueOf(decoded)
                  .add(java.math.BigInteger.valueOf(source.size()))
                  .toString());
        decoded += source.size();
        items.add(new Item(source, name, Ba2Names.identity(name), compressed));
      }
      if (!unmatched.isEmpty())
        throw context.failure(FailureKind.POLICY, "pack.unmatched-entry-compression", null);
      if (items.isEmpty()) throw context.failure(FailureKind.POLICY, "ba2.empty-entry-set", null);
      boolean anyCompressed = items.stream().anyMatch(item -> item.compressed);
      // One spool bounds heap and handle use independently of entry count, and fixes split sizes.
      scratch = SpillBuffer.open(Path.of(System.getProperty("java.io.tmpdir")), budget, context);
      SpillBuffer stable = scratch;
      try (var codecCredits =
          budget.reserve(
              anyCompressed ? JdkZlib.ENCODE_HEAP_BYTES + 131072 : 196608,
              anyCompressed ? JdkZlib.ENCODE_NATIVE_BYTES : 0,
              0,
              0)) {
        Map<String, List<Item>> candidates = new HashMap<>();
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
      for (var part : parts)
        budget.metadata(
            24 + part.size() * 36L + part.stream().mapToLong(i -> i.name.length + 2L).sum());
      var writers = new ArrayList<PublicationTransaction.Writer>();
      var completed = new ArrayList<OperationReport.ArchivePart>();
      for (var part : parts) {
        int number = writers.size() + 1;
        writers.add(
            new PublicationTransaction.Writer() {
              /** Replays this independent part before the transaction may publish any sibling. */
              @Override
              public void write(PublicationTransaction.StagedFile output) throws IOException {
                Ba2Packer.write(part, stable, output);
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
                    24
                        + 36L * part.size()
                        + part.stream().mapToLong(item -> item.name.length + 2L).sum();
                // The reader owns its own budget, so admit its peak against the still-live pack
                // budget as well; readback must not silently exceed the operation's capacity.
                try (var validationCredits =
                        budget.reserve(
                            512L * part.size()
                                + 8L * metadataBytes
                                + 65536
                                + JdkZlib.DECODE_HEAP_BYTES,
                            JdkZlib.DECODE_NATIVE_BYTES,
                            1,
                            0);
                    var archive =
                        ArchiveReaders.open(
                            staged,
                            new OpenOptions(
                                request.compatibilityProfile(),
                                request.resourceLimits(),
                                Optional.empty()),
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
    Set<Item> owners = new HashSet<>();
    long estimate = 0;
    for (Item item : items) {
      long overhead = 200L + item.source.displayName().length();
      long cost = overhead + (owners.contains(item.owner) ? 0 : item.owner.size);
      if (target != 0 && !current.isEmpty() && cost > target - estimate) {
        parts.add(List.copyOf(current));
        current.clear();
        owners.clear();
        estimate = 0;
        cost = overhead + item.owner.size;
      }
      current.add(item);
      owners.add(item.owner);
      estimate = Math.addExact(estimate, cost);
    }
    if (!current.isEmpty()) parts.add(List.copyOf(current));
    return parts;
  }

  /** Emits record-order payloads and a trailing case-preserving filename table. */
  private static void write(
      List<Item> items, SpillBuffer scratch, PublicationTransaction.StagedFile output)
      throws IOException {
    long payload = 24 + 36L * items.size();
    output.reserve(payload);
    Map<Item, Long> emitted = new IdentityHashMap<>();
    for (int ordinal = 0; ordinal < items.size(); ordinal++) {
      Item item = items.get(ordinal), owner = item.owner;
      output.checkpoint();
      Long destination = emitted.get(owner);
      if (destination == null) {
        destination = payload;
        copy(scratch, owner.offset, owner.size, output, destination);
        emitted.put(owner, destination);
        payload = Math.addExact(payload, owner.size);
      }
      output.write(
          24 + 36L * ordinal,
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
    output.write(
        0,
        ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x58445442)
            .putInt(1)
            .putInt(0x4c524e47)
            .putInt(items.size())
            .putLong(payload)
            .flip());
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
}
