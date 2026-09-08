package io.github.evildarkarchon.jbsa.internal.tes3;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.util.*;

/** Sequential TES3 planning and stored encoding under the shared publication contract. */
public final class Tes3Packer {
  private Tes3Packer() {}

  /** Resolves the complete plan before staging, then publishes its stored archive atomically. */
  public static OperationReport pack(PackRequest request, OperationControl control)
      throws ArchiveException {
    var operation =
        new OperationSession(
            Operation.PACK, request.resourceLimits(), request.diagnosticPolicy(), control);
    var context = IoContext.of(request.destination(), Operation.PACK);
    boolean publicationOwnsSession = false;
    try (ResourceBudget budget = ResourceBudget.forMutation(request.resourceLimits(), context)) {
      operation.begin();
      if (!request.options().entryCompression().isEmpty())
        throw context.failure(FailureKind.UNSUPPORTED, "tes3.entry-compression-inapplicable", null);
      Tes3Names.encoding(request.compatibilityProfile(), context);
      if (!request.encoding().equals(ArchiveEncoding.tes3()))
        throw context.failure(FailureKind.UNSUPPORTED, "archive.unsupported-encoding", null);
      if (request.options().archiveFlags() instanceof FlagSelection.Explicit
          || request.options().fileFlags() instanceof FlagSelection.Explicit)
        throw context.failure(FailureKind.UNSUPPORTED, "tes3.flags-inapplicable", null);
      List<PackSources.Entry> entries = PackSources.plan(request, operation, budget);
      if (entries.isEmpty())
        throw context.failure(FailureKind.POLICY, "tes3.empty-entry-set", null);
      entries.sort(
          (a, b) -> {
            int low = Long.compare(a.hash() & 0xffff_ffffL, b.hash() & 0xffff_ffffL);
            int high = Long.compare(a.hash() >>> 32, b.hash() >>> 32);
            return low != 0 ? low : high != 0 ? high : Arrays.compareUnsigned(a.name(), b.name());
          });
      List<List<PackSources.Entry>> parts = split(entries, request.options(), context);
      long decoded = 0;
      for (PackSources.Entry entry : entries) {
        if (entry.size() > request.resourceLimits().maxDecodedBytes() - decoded)
          throw context.limit(
              "maxDecodedBytes",
              request.resourceLimits().maxDecodedBytes(),
              java.math.BigInteger.valueOf(decoded)
                  .add(java.math.BigInteger.valueOf(entry.size()))
                  .toString());
        decoded += entry.size();
      }
      for (List<PackSources.Entry> part : parts) {
        long metadata = 12 + part.size() * 20L;
        long relative = 0;
        for (PackSources.Entry entry : part) {
          checkU32(entry.size(), context);
          checkU32(relative, context);
          relative = Math.addExact(relative, entry.size());
          metadata = Math.addExact(metadata, entry.name().length + 1L);
        }
        checkU32(metadata - 12 - part.size() * 8L, context);
        Math.addExact(metadata, relative);
        budget.metadata(metadata);
      }
      List<PublicationTransaction.Writer> writers = new ArrayList<>();
      List<OperationReport.ArchivePart> completedParts = new ArrayList<>();
      long nextOrdinal = 0;
      for (var part : parts) {
        long firstOrdinal = nextOrdinal;
        int partNumber = writers.size() + 1;
        writers.add(
            output -> {
              write(
                  part,
                  output,
                  context,
                  request.options().sharing(),
                  request.resourceLimits(),
                  firstOrdinal);
              completedParts.add(
                  new OperationReport.ArchivePart(
                      PublicationTransaction.splitPath(
                          request.destination().toAbsolutePath().normalize(), partNumber),
                      output.size(),
                      part.size()));
            });
        nextOrdinal += part.size();
      }
      publicationOwnsSession = true;
      OperationReport report =
          PublicationTransaction.archives(
              request.destination(),
              writers,
              request.targetPolicy(),
              request.resourceLimits(),
              operation);
      return new OperationReport(
          report.operation(),
          report.artifacts(),
          report.diagnostics(),
          report.assessment(),
          completedParts);
    } catch (IOException failure) {
      if (publicationOwnsSession && failure instanceof ArchiveException archiveFailure)
        throw archiveFailure;
      operation.accept(
          failure instanceof ArchiveException archiveFailure
              ? archiveFailure
              : context.failure(FailureKind.SOURCE, "operation.source-io", failure));
      operation.cleanup();
      operation.cleaned(0);
      return operation.finish(List.of());
    }
  }

  /** Forms whole-entry parts using the format's advisory per-entry estimate in canonical order. */
  private static List<List<PackSources.Entry>> split(
      List<PackSources.Entry> entries, PackOptions options, IoContext context)
      throws ArchiveException {
    long target =
        switch (options.splitting()) {
          case PackOptions.Splitting.FamilyDefault ignored -> 2_147_483_647L;
          case PackOptions.Splitting.UpToBytes explicit -> explicit.targetBytes();
          case PackOptions.Splitting.LegacyPerEntry ignored -> 1L;
        };
    List<List<PackSources.Entry>> parts = new ArrayList<>();
    List<PackSources.Entry> current = new ArrayList<>();
    long estimated = 0;
    for (PackSources.Entry entry : entries) {
      checkU32(entry.size(), context);
      long cost = entry.size() + 200L + entry.name().length;
      if (target != 0 && !current.isEmpty() && cost > target - estimated) {
        parts.add(List.copyOf(current));
        current.clear();
        estimated = 0;
      }
      current.add(entry);
      estimated = Math.addExact(estimated, cost);
    }
    if (!current.isEmpty()) parts.add(List.copyOf(current));
    return parts;
  }

  /** Rejects a wire field before narrowing or opening any source payload. */
  private static void checkU32(long value, IoContext context) throws ArchiveException {
    if (value < 0 || value > 0xffff_ffffL)
      throw context.failure(FailureKind.POLICY, "tes3.wire-limit", null);
  }

  /**
   * Writes bounded header/table records and sequential source bytes without whole-entry buffers.
   */
  private static void write(
      List<PackSources.Entry> entries,
      PublicationTransaction.StagedFile output,
      IoContext context,
      boolean sharing,
      ResourceLimits limits,
      long firstOrdinal)
      throws IOException {
    long names = entries.stream().mapToLong(entry -> entry.name().length + 1L).sum();
    long hashOffset = entries.size() * 12L + names;
    long dataBase = 12 + hashOffset + entries.size() * 8L;
    output.write(0, words(0x100, hashOffset, entries.size()));
    long nameOffset = 0;
    long dataOffset = 0;
    Map<String, List<StoredPayload>> shared = new HashMap<>();
    for (int index = 0; index < entries.size(); index++) {
      PackSources.Entry entry = entries.get(index);
      IoContext processing =
          new IoContext(
              context.path(),
              Operation.PACK,
              OperationPhase.PROCESSING,
              OptionalLong.of(firstOrdinal + index));
      output.write(12 + entries.size() * 8L + index * 4L, words(nameOffset));
      output.write(
          12 + entries.size() * 12L + nameOffset,
          ByteBuffer.wrap(Arrays.copyOf(entry.name(), entry.name().length + 1)));
      output.write(
          12 + hashOffset + index * 8L,
          ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(entry.hash()).flip());
      long position = dataBase + dataOffset;
      long relative = dataOffset;
      if (sharing) {
        SpillBuffer scratch = output.scratch();
        ArchiveException primary = null;
        try {
          var digest = digest();
          PackSources.consume(
              entry,
              input ->
                  transfer(
                      input,
                      entry.size(),
                      (offset, bytes) -> {
                        digest.update(bytes.duplicate());
                        scratch.write(offset, bytes);
                      },
                      output,
                      processing),
              processing);
          scratch.seal();
          String key = entry.size() + ":" + HexFormat.of().formatHex(digest.digest());
          List<StoredPayload> candidates = shared.getOrDefault(key, List.of());
          StoredPayload match = null;
          for (StoredPayload candidate : candidates) {
            if (equal(scratch, candidate.offset, output)) {
              match = candidate;
              break;
            }
          }
          if (match == null) {
            replay(scratch, position, output);
            shared
                .computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new StoredPayload(position));
            dataOffset += entry.size();
          } else relative = match.offset - dataBase;
        } catch (ArchiveException failure) {
          primary = failure;
          throw failure;
        } finally {
          try {
            scratch.close();
          } catch (ArchiveException cleanup) {
            if (primary == null) throw cleanup;
            // Keep cleanup as structured secondary evidence, including any exact residual path.
            var failures = new FailureRetention(limits, Operation.PACK);
            failures.accept(primary);
            failures.accept(cleanup);
            throw failures.finish(List.of());
          }
        }
      } else {
        PackSources.consume(
            entry,
            input ->
                transfer(
                    input,
                    entry.size(),
                    (offset, bytes) -> output.write(position + offset, bytes),
                    output,
                    processing),
            processing);
        dataOffset += entry.size();
      }
      output.write(12 + index * 8L, words(entry.size(), relative));
      output.completedEntry(entry.size());
      nameOffset += entry.name().length + 1L;
    }
  }

  /** Enforces the declared source length, including a terminal read for excess generated bytes. */
  private static void transfer(
      ReadableByteChannel input,
      long length,
      ByteSink sink,
      PublicationTransaction.StagedFile output,
      IoContext context)
      throws IOException {
    if (input == null) throw context.failure(FailureKind.SOURCE, "source.invalid-channel", null);
    ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
    long count = 0;
    int idle = 0;
    while (true) {
      output.checkpoint();
      buffer
          .clear()
          .limit((int) Math.min(buffer.capacity(), length - count + (count == length ? 1 : 0)));
      int read = input.read(buffer);
      if (read < 0) break;
      if (read == 0) {
        if (++idle > 16) throw context.failure(FailureKind.SOURCE, "io.no-progress", null);
        continue;
      }
      idle = 0;
      if (read > length - count)
        throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      sink.write(count, buffer.flip());
      count += read;
    }
    if (count != length) throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
  }

  /** Confirms digest candidates with bounded exact-byte reads from stable scratch and staging. */
  private static boolean equal(
      SpillBuffer scratch, long position, PublicationTransaction.StagedFile output)
      throws IOException {
    ByteBuffer left = ByteBuffer.allocate(64 * 1024);
    ByteBuffer right = ByteBuffer.allocate(64 * 1024);
    for (long offset = 0; offset < scratch.size(); ) {
      int count = (int) Math.min(left.capacity(), scratch.size() - offset);
      left.clear().limit(count);
      right.clear().limit(count);
      scratch.read(offset, left);
      output.read(position + offset, right);
      if (!left.flip().equals(right.flip())) return false;
      offset += count;
    }
    return true;
  }

  /** Replays one stabilized payload without counting its logical bytes twice in progress. */
  private static void replay(
      SpillBuffer scratch, long position, PublicationTransaction.StagedFile output)
      throws IOException {
    ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
    for (long offset = 0; offset < scratch.size(); ) {
      int count = (int) Math.min(bytes.capacity(), scratch.size() - offset);
      bytes.clear().limit(count);
      scratch.read(offset, bytes);
      output.write(position + offset, bytes.flip());
      offset += count;
    }
  }

  /** Obtains the mandatory JDK SHA-256 implementation used only to shortlist byte comparisons. */
  private static java.security.MessageDigest digest() {
    try {
      return java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /** A positional bounded write destination, kept entirely beneath the public API. */
  @FunctionalInterface
  private interface ByteSink {
    void write(long offset, ByteBuffer bytes) throws IOException;
  }

  /** Exact previously emitted payload start; the enclosing digest key includes its length. */
  private record StoredPayload(long offset) {}

  /** Serializes checked unsigned TES3 fields in little-endian order. */
  private static ByteBuffer words(long... values) {
    var bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (long value : values) bytes.putInt((int) value);
    return bytes.flip();
  }
}
