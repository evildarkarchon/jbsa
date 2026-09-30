package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Layout;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Patch;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Placed;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Planned;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * The Pack Pipeline: one owner for every pack's I/O, lifecycle, Content Sharing, and failure
 * handling, driven by a pure {@link FamilyAdapter}. It owns the operation session, resource budget,
 * the fixed admission and planning sequence, splitting, target preflight, stabilization, payload
 * placement, table patching, the report, and the single failure epilogue.
 */
public final class PackPipeline {
  private PackPipeline() {}

  /**
   * Packs a request through one family adapter and publishes its archive set atomically.
   *
   * <p>Admission and planning always run in this order: {@code adapter.admit}, source planning
   * under the adapter's name charset, {@code admitted.plan}, {@code
   * pack.unmatched-entry-compression}, {@code <prefix>.empty-entry-set}, then the decoded-size
   * limit charged in Logical Plan Order. Target preflight always runs before any source payload is
   * read, for the part count the plan already knows.
   *
   * @return the report, whose failure outcome is thrown after cleanup has settled
   * @throws ArchiveException the Primary Failure, with Secondary Failures and artifact evidence
   */
  public static <K> OperationReport pack(
      PackRequest request, OperationControl control, FamilyAdapter<K> adapter)
      throws ArchiveException {
    var operation =
        new OperationSession(
            Operation.PACK, request.resourceLimits(), request.diagnosticPolicy(), control);
    var context = IoContext.of(request.destination(), Operation.PACK);
    OperationReport result = null;
    var failures = new FailureRetention(request.resourceLimits(), Operation.PACK);
    // The pipeline owns the budget lifetime (D10); it closes before the epilogue settles.
    ResourceBudget budget = ResourceBudget.forMutation(request.resourceLimits(), context);
    var run = new Run(request, operation, context, budget);
    try {
      operation.begin();
      run.workers = WorkerLimits.snapshot(request.workerSelection());
      Admitted<K> admitted = adapter.admit(request, context);
      validate(admitted);
      List<PackSources.Entry> sources =
          PackSources.plan(request, operation, budget, admitted.nameCharset());
      List<Planned<K>> planned = admitted.plan(sources, context);
      // Each lease stays live until the budget closes: it covers per-entry state the family keeps
      // for the whole operation.
      if (admitted.entryReserveBytes() > 0)
        for (int index = 0; index < planned.size(); index++)
          budget.reserve(admitted.entryReserveBytes(), 0, 0, 0);
      rejectUnmatchedOverrides(request, sources, context);
      if (planned.isEmpty())
        throw context.failure(
            FailureKind.POLICY, admitted.diagnosticPrefix() + ".empty-entry-set", null);
      // A chunking family's decoded extent is known only from each envelope, so stabilization
      // charges it instead, still in Logical Plan Order.
      if (admitted.chunkHeadBytes() == 0) chargeDecoded(sources, request.resourceLimits(), context);
      result =
          switch (admitted.emission()) {
            case STREAMING -> streamed(run, admitted, planned);
            case STABILIZED -> stabilized(run, admitted, planned);
          };
    } catch (IOException failure) {
      failures.accept(structured(failure, context));
    } finally {
      // Stabilization scratch normally closes inside the last writer, before commit; this settles
      // it after any earlier failure. Closing an already closed spool is a no-op.
      if (run.stable != null) {
        try {
          run.stable.close();
        } catch (ArchiveException cleanup) {
          failures.accept(cleanup);
        }
      }
      budget.close();
    }
    // The single failure epilogue (D7). Publication already settled cleanup and progress for any
    // failure it owns; an earlier failure still needs the session's cleanup phase and report.
    if (failures.failed()) {
      ArchiveException failure = failures.finish(result == null ? List.of() : result.artifacts());
      if (run.publicationOwnsSession) throw failure;
      operation.accept(failure);
      operation.cleanup();
      operation.cleaned(0);
      return operation.finish(List.of());
    }
    return result;
  }

  /**
   * Rejects adapter declarations the pipeline cannot honor. A streamed part cannot know encoded
   * sizes, other parts' owners, or chunk envelopes before it stages. A stabilized plan shares
   * either complete stored records per part (versioned BSA) or raw bytes across the whole archive
   * set (BA2); only set-wide owners give a first-owner split cost or a chunk slice an identity.
   */
  private static void validate(Admitted<?> admitted) {
    Admitted.Sharing sharing = admitted.sharing();
    Admitted.PayloadCost payload = admitted.splitCost().payload();
    switch (admitted.emission()) {
      case STREAMING -> {
        if (payload != Admitted.PayloadCost.DECODED
            || sharing.scope() != Admitted.Sharing.Scope.PER_PART
            || admitted.chunkHeadBytes() != 0)
          throw new IllegalStateException(
              "Streaming emission needs decoded costs, per-part sharing, and no chunking");
      }
      case STABILIZED -> {
        boolean perPartStored =
            sharing.scope() == Admitted.Sharing.Scope.PER_PART
                && sharing.basis() == Admitted.Sharing.Basis.STORED
                && payload != Admitted.PayloadCost.UNIQUE_STORED
                && admitted.chunkHeadBytes() == 0;
        boolean setRaw =
            sharing.scope() == Admitted.Sharing.Scope.ARCHIVE_SET
                && sharing.basis() == Admitted.Sharing.Basis.RAW;
        if (!perPartStored && !setRaw)
          throw new UnsupportedOperationException(
              "Stabilized emission shares stored records per part or raw bytes set-wide");
      }
    }
  }

  /** Rejects an entry-compression override whose identity no planned source carries. */
  private static void rejectUnmatchedOverrides(
      PackRequest request, List<PackSources.Entry> sources, IoContext context)
      throws ArchiveException {
    Set<NormalizedNameIdentity> unmatched =
        new HashSet<>(request.options().entryCompression().keySet());
    for (PackSources.Entry source : sources)
      unmatched.remove(new NormalizedNameIdentity(source.identity()));
    if (!unmatched.isEmpty())
      throw context.failure(FailureKind.POLICY, "pack.unmatched-entry-compression", null);
  }

  /**
   * Charges declared sizes against {@code maxDecodedBytes} once, in Logical Plan Order (Q14), so
   * the failing entry does not depend on the family's output order.
   */
  private static void chargeDecoded(
      List<PackSources.Entry> sources, ResourceLimits limits, IoContext context)
      throws ArchiveException {
    long decoded = 0;
    for (PackSources.Entry source : sources) {
      if (source.size() > limits.maxDecodedBytes() - decoded)
        throw context.limit(
            "maxDecodedBytes",
            limits.maxDecodedBytes(),
            BigInteger.valueOf(decoded).add(BigInteger.valueOf(source.size())).toString());
      decoded += source.size();
    }
  }

  /** Returns the byte target the request's Splitting arm selects; 0 never splits. */
  private static long target(Admitted<?> admitted, PackOptions.Splitting splitting) {
    return switch (splitting) {
      case PackOptions.Splitting.FamilyDefault ignored -> admitted.defaultSplitTarget();
      case PackOptions.Splitting.UpToBytes explicit -> explicit.targetBytes();
      case PackOptions.Splitting.LegacyPerEntry ignored -> 1L;
    };
  }

  /**
   * Forms whole-entry parts in output order from the adapter's advisory split cost. A zero target
   * never splits; legacy per-entry splitting uses a one-byte target. A first-owner cost tracks the
   * owners each part already references, and re-costs the entry that opens a new part.
   */
  static <K> List<List<Planned<K>>> split(
      List<Planned<K>> planned, Admitted<K> admitted, PackOptions.Splitting splitting) {
    long target = target(admitted, splitting);
    Admitted.SplitCost<K> rule = admitted.splitCost();
    List<List<Planned<K>>> parts = new ArrayList<>();
    List<Planned<K>> current = new ArrayList<>();
    Set<Integer> owners = new HashSet<>();
    long estimated = 0;
    for (Planned<K> entry : planned) {
      long overhead = Math.addExact(rule.fixed(), rule.nameCost().applyAsLong(entry.key()));
      long cost = Math.addExact(overhead, payloadCost(entry, rule.payload(), owners));
      if (target != 0 && !current.isEmpty() && cost > target - estimated) {
        parts.add(List.copyOf(current));
        current.clear();
        owners.clear();
        estimated = 0;
        // Owners the closed part already paid for are charged again in the new one.
        cost = Math.addExact(overhead, payloadCost(entry, rule.payload(), owners));
      }
      current.add(entry);
      for (Admitted.Segment segment : entry.segments()) owners.add(segment.owner());
      estimated = Math.addExact(estimated, cost);
    }
    if (!current.isEmpty()) parts.add(List.copyOf(current));
    return parts;
  }

  /**
   * Returns one entry's payload split cost; a stored cost needs the record's known size. A
   * first-owner cost charges each distinct segment owner the part does not reference yet; an
   * unsegmented entry (before stabilization, where nothing can be shared) costs its stored size.
   *
   * @param owners the segment owners the current part already references
   */
  private static long payloadCost(
      Planned<?> entry, Admitted.PayloadCost payload, Set<Integer> owners) {
    return switch (payload) {
      case DECODED -> entry.source().size();
      case STORED -> knownStoredSize(entry);
      case UNIQUE_STORED -> {
        if (entry.segments().isEmpty()) yield knownStoredSize(entry);
        Set<Integer> counted = new HashSet<>();
        long cost = 0;
        for (Admitted.Segment segment : entry.segments())
          if (!owners.contains(segment.owner()) && counted.add(segment.owner()))
            cost = Math.addExact(cost, segment.storedSize());
        yield cost;
      }
    };
  }

  /** Returns a stored size that splitting may charge, which must already be known. */
  private static long knownStoredSize(Planned<?> entry) {
    if (entry.storedSize() == Planned.UNKNOWN_SIZE)
      throw new IllegalStateException("A stored split cost needs a stabilized record size");
    return entry.storedSize();
  }

  /**
   * Returns how many parts a stabilized plan will certainly publish before any source is read. When
   * every stored size is predictable and sharing cannot lower a first-owner cost, the split is
   * exact, and each predicted part's layout is admitted now so a wire limit fails before any source
   * read. Otherwise the count is known only when no entry can share a part (every entry's minimum
   * cost reaches a nonzero target) or nothing splits; any other plan is certain only of its first
   * part.
   *
   * @param sharing whether the request enables Content Sharing
   */
  static <K> int knownPartCount(
      List<Planned<K>> planned,
      Admitted<K> admitted,
      PackOptions.Splitting splitting,
      boolean sharing,
      IoContext context)
      throws ArchiveException {
    boolean shareable =
        sharing && admitted.splitCost().payload() == Admitted.PayloadCost.UNIQUE_STORED;
    if (!shareable
        && planned.stream().allMatch(entry -> entry.storedSize() != Planned.UNKNOWN_SIZE)) {
      List<List<Planned<K>>> parts = split(planned, admitted, splitting);
      for (var part : parts) admitted.layout(part, context);
      return parts.size();
    }
    long target = target(admitted, splitting);
    if (target == 0) return 1;
    Admitted.SplitCost<K> rule = admitted.splitCost();
    for (Planned<K> entry : planned)
      // An unknown payload costs at least zero, so this is the entry's minimum split cost.
      if (Math.addExact(rule.fixed(), rule.nameCost().applyAsLong(entry.key())) < target) return 1;
    return planned.size();
  }

  /**
   * Streams each part: its payloads are read while it stages, after every part's layout and the
   * complete target set were admitted during preflight.
   */
  private static <K> OperationReport streamed(
      Run run, Admitted<K> admitted, List<Planned<K>> planned) throws IOException {
    PackRequest request = run.request;
    List<List<Planned<K>>> parts = split(planned, admitted, request.options().splitting());
    List<Layout> layouts = new ArrayList<>(parts.size());
    for (var part : parts) {
      Layout layout = admitted.layout(part, run.context);
      run.budget.metadata(layout.metadataBytes());
      layouts.add(layout);
    }
    PublicationTransaction.preflightArchiveTargets(
        request.destination(),
        parts.size(),
        request.targetPolicy(),
        request.resourceLimits(),
        run.context);
    // D8: admit the coordinator windows and codec working set before any writer allocates them.
    try (var windows = reserveWindows(planned, request.options().sharing(), run.budget)) {
      var writers = new ArrayList<PublicationTransaction.Writer>();
      var completed = new ArrayList<OperationReport.ArchivePart>();
      long nextOrdinal = 0;
      for (int index = 0; index < parts.size(); index++) {
        List<Planned<K>> part = parts.get(index);
        Layout layout = layouts.get(index);
        long firstOrdinal = nextOrdinal;
        int number = index + 1;
        writers.add(
            writer(
                output -> {
                  stream(admitted, part, layout, output, firstOrdinal, run, windows);
                  completed.add(archivePart(request, number, output, part));
                },
                admitted.readback(part, layout),
                run));
        nextOrdinal += part.size();
      }
      run.publicationOwnsSession = true;
      return withParts(
          PublicationTransaction.archives(
              request.destination(),
              writers,
              request.targetPolicy(),
              request.resourceLimits(),
              run.operation,
              run.budget),
          completed);
    }
  }

  /**
   * Stabilizes every payload in output order before splitting, because the family's split cost and
   * layout depend on exact stored sizes. Stabilization is processing work, so progress enters
   * PROCESSING before the first source is read (Q10) and publication continues in that phase.
   */
  private static <K> OperationReport stabilized(
      Run run, Admitted<K> admitted, List<Planned<K>> planned) throws IOException {
    PackRequest request = run.request;
    PackOptions.Splitting splitting = request.options().splitting();
    boolean sharing = request.options().sharing();
    // D9: preflight never depends on sharing; it checks every part the plan can already count.
    PublicationTransaction.preflightArchiveTargets(
        request.destination(),
        knownPartCount(planned, admitted, splitting, sharing, run.context),
        request.targetPolicy(),
        request.resourceLimits(),
        run.context);
    run.operation.completePhase();
    run.operation.processing(false);
    // One spool bounds heap and handle use independently of entry count, and fixes split sizes.
    run.stable =
        SpillBuffer.open(Path.of(System.getProperty("java.io.tmpdir")), run.budget, run.context);
    SpillBuffer stable = run.stable;
    List<Planned<K>> sized;
    Replay<K> replay;
    long replayHeap;
    if (admitted.sharing().scope() == Admitted.Sharing.Scope.ARCHIVE_SET) {
      List<SetOwner> owners = new ArrayList<>();
      sized = stabilizeSet(run, admitted, planned, owners);
      replay = (part, layout, output) -> emitOwners(admitted, part, layout, output, stable, owners);
      // One replay window and one readback window, plus bounded record and header buffers.
      replayHeap = 2L * PayloadWindows.WINDOW_BYTES + 1024;
    } else {
      List<Stable<K>> records = stabilize(run, admitted, planned);
      // Parts hold the sized entries themselves, so identity locates each one's stabilized record.
      Map<Planned<K>, Long> offsets = new IdentityHashMap<>();
      sized = new ArrayList<>(records.size());
      for (Stable<K> record : records) {
        offsets.put(record.planned(), record.offset());
        sized.add(record.planned());
      }
      replay =
          (part, layout, output) ->
              replay(admitted, part, offsets, layout, output, stable, sharing);
      // Replay holds one window, or two while confirming a sharing candidate; reserve the peak.
      replayHeap = (sharing ? 2L : 1L) * PayloadWindows.WINDOW_BYTES;
    }
    stable.seal();
    List<List<Planned<K>>> parts = split(sized, admitted, splitting);
    List<Layout> layouts = new ArrayList<>(parts.size());
    for (var part : parts) {
      Layout layout = admitted.layout(part, run.context);
      run.budget.metadata(layout.metadataBytes());
      layouts.add(layout);
    }
    var writers = new ArrayList<PublicationTransaction.Writer>();
    var completed = new ArrayList<OperationReport.ArchivePart>();
    for (int index = 0; index < parts.size(); index++) {
      List<Planned<K>> part = parts.get(index);
      Layout layout = layouts.get(index);
      int number = index + 1;
      writers.add(
          writer(
              output -> {
                replay.write(part, layout, output);
                // The last part has consumed stabilization; its cleanup must precede the commit.
                if (number == parts.size()) stable.close();
                completed.add(archivePart(request, number, output, part));
              },
              admitted.readback(part, layout),
              run));
    }
    try (var windows = run.budget.reserve(replayHeap, 0, 0, 0)) {
      run.publicationOwnsSession = true;
      return withParts(
          PublicationTransaction.stabilizedArchives(
              request.destination(),
              writers,
              request.targetPolicy(),
              request.resourceLimits(),
              run.operation,
              run.budget),
          completed);
    }
  }

  /**
   * Writes each entry's framed stored record into the operation's stable spool in output order and
   * measures it. Workers encode independently through the merged transform stage; the coordinator
   * alone orders records, so their offsets never depend on worker completion order.
   */
  private static <K> List<Stable<K>> stabilize(
      Run run, Admitted<K> admitted, List<Planned<K>> planned) throws IOException {
    SpillBuffer stable = run.stable;
    TransformStage stage =
        new TransformStage(
            planned.stream().map(Planned::source).toList(),
            run.workers,
            run.budget,
            run.context,
            0,
            ordinal -> {
              Planned<K> entry = planned.get(ordinal);
              // Stored records keep the raw spool; encoded ones need only the encoder's output.
              return TransformStage.encoding(
                  entry.codec(), entry.source().size(), false, run.budget);
            });
    List<Stable<K>> records = new ArrayList<>(planned.size());
    Throwable pending = null;
    try {
      // Worker handle leases must not starve the coordinator's ordered stable spool.
      if (stage.parallel()) stable.reserveSpillHandle();
      try (var coordinator = reserveCoordinator(planned, stage.parallel(), run.budget)) {
        for (int ordinal = 0; ordinal < planned.size(); ordinal++) {
          try (TransformStage.Input staged = stage.next(run.operation)) {
            Planned<K> entry = planned.get(ordinal);
            IoContext processing = processing(run.context, ordinal);
            OptionalLong location = processing.ordinal();
            PayloadWindows.Checkpoint checkpoint =
                () -> run.operation.checkpoint(OperationPhase.PROCESSING, location);
            long offset = stable.size();
            byte[] frame = entry.frame();
            if (frame.length > 0) stable.write(offset, ByteBuffer.wrap(frame));
            long start = offset + frame.length;
            PayloadWindows.Sink sink = (relative, bytes) -> stable.write(start + relative, bytes);
            if (staged == null)
              PackSources.consume(
                  entry.source(),
                  input ->
                      Codecs.encode(
                          entry.codec(),
                          input,
                          entry.source().size(),
                          sink,
                          checkpoint,
                          run.budget,
                          coordinator,
                          processing),
                  processing);
            else {
              boolean encoded = staged.hasEncoded();
              try (ReadableByteChannel input =
                  encoded ? staged.encodedChannel() : staged.channel()) {
                PayloadWindows.transfer(
                    input,
                    encoded ? staged.encodedSize() : entry.source().size(),
                    sink,
                    checkpoint,
                    processing);
              }
            }
            Planned<K> measured = entry.stabilized(stable.size() - offset);
            admitted.checkStored(measured, processing);
            records.add(new Stable<>(measured, offset));
          }
        }
      }
    } catch (Throwable failure) {
      pending = failure;
    }
    closeStage(stage, pending, run);
    return records;
  }

  /**
   * Reserves the coordinator's stabilization working set. With parallel workers it only copies
   * finished results through one window. Alone it also encodes, but never while a transfer window
   * is live, so the peak is the larger of the window and the heaviest codec.
   */
  private static ResourceBudget.Lease reserveCoordinator(
      List<? extends Planned<?>> planned, boolean parallel, ResourceBudget budget)
      throws ArchiveException {
    long heap = PayloadWindows.WINDOW_BYTES;
    long nativeBytes = 0;
    if (!parallel)
      for (Planned<?> entry : planned) {
        Codecs.Cost cost = Codecs.cost(entry.codec(), entry.source().size());
        heap = Math.max(heap, cost.heap());
        nativeBytes = Math.max(nativeBytes, cost.nativeBytes());
      }
    return budget.reserve(heap, nativeBytes, 0, 0);
  }

  /**
   * Stabilizes every payload in output order for archive-set raw-basis sharing. Each entry's raw
   * bytes are written to the stable spool first; a chunking family's envelope is then chunked (and
   * rewritten when asked), and each slice, or the whole payload, is resolved to the earliest
   * byte-identical raw owner in the set. Only a new owner is encoded, so sharing never depends on
   * the codec. A chunked entry's decoded size is charged here, in Logical Plan Order.
   *
   * @param owners receives every distinct owner in creation order; a segment's owner identity is
   *     its index here
   * @return the entries, each resolved to its segments
   */
  private static <K> List<Planned<K>> stabilizeSet(
      Run run, Admitted<K> admitted, List<Planned<K>> planned, List<SetOwner> owners)
      throws IOException {
    SpillBuffer stable = run.stable;
    boolean chunked = admitted.chunkHeadBytes() > 0;
    TransformStage stage =
        new TransformStage(
            planned.stream().map(Planned::source).toList(),
            run.workers,
            run.budget,
            run.context,
            0,
            ordinal -> {
              Planned<K> entry = planned.get(ordinal);
              // Chunks are sliced from the stabilized envelope, so only the coordinator can encode
              // them.
              if (chunked || !Codecs.workerEncodes(entry.codec())) return null;
              // Raw bytes stay staged: sharing is decided on them before the owner is encoded.
              return TransformStage.encoding(
                  entry.codec(), entry.source().size(), true, run.budget);
            });
    boolean sharing = run.request.options().sharing();
    ContentSharing<SetOwner> index = new ContentSharing<>();
    List<Planned<K>> sized = new ArrayList<>(planned.size());
    ResourceLimits limits = run.request.resourceLimits();
    long decoded = 0;
    Throwable pending = null;
    try {
      // Worker handle leases must not starve the coordinator's ordered stable spool.
      if (stage.parallel()) stable.reserveSpillHandle();
      try (var coordinator =
          reserveSetCoordinator(planned, stage.parallel(), chunked, run.budget)) {
        for (int ordinal = 0; ordinal < planned.size(); ordinal++) {
          try (TransformStage.Input staged = stage.next(run.operation)) {
            Planned<K> entry = planned.get(ordinal);
            IoContext processing = processing(run.context, ordinal);
            OptionalLong location = processing.ordinal();
            PayloadWindows.Checkpoint checkpoint =
                () -> run.operation.checkpoint(OperationPhase.PROCESSING, location);
            long size = entry.source().size();
            long rawOffset = stable.size();
            PayloadWindows.Sink raw =
                (relative, bytes) -> stable.write(rawOffset + relative, bytes);
            if (staged == null)
              PackSources.consume(
                  entry.source(),
                  input -> PayloadWindows.transfer(input, size, raw, checkpoint, processing),
                  processing);
            else {
              try (ReadableByteChannel input = staged.channel()) {
                PayloadWindows.transfer(input, size, raw, checkpoint, processing);
              }
            }
            // BA2 has always observed cancellation during sharing comparisons without an entry
            // ordinal; keeping that preserves the reported failure location.
            PayloadWindows.Checkpoint comparison =
                () -> run.operation.checkpoint(OperationPhase.PROCESSING, OptionalLong.empty());
            var owner =
                new OwnerResolver(
                    stable,
                    index,
                    owners,
                    sharing,
                    checkpoint,
                    comparison,
                    run.budget,
                    coordinator,
                    processing);
            List<Admitted.Segment> segments = new ArrayList<>();
            K key = entry.key();
            long chargedDecoded = -1;
            if (chunked) {
              ByteBuffer head =
                  ByteBuffer.allocate((int) Math.min(admitted.chunkHeadBytes(), size));
              stable.read(rawOffset, head);
              Admitted.Chunks<K> chunks = admitted.chunk(entry, head.flip(), processing);
              run.budget.metadata(chunks.metadataBytes());
              long payload = rawOffset + chunks.payloadStart();
              if (chunks.rewrite() == Admitted.Rewrite.BGR24_TO_BGRA32)
                payload = expandBgr24(stable, payload, size - chunks.payloadStart(), checkpoint);
              for (Admitted.Slice slice : chunks.slices())
                segments.add(
                    owner.resolve(payload + slice.offset(), slice.size(), entry.codec(), null));
              key = chunks.key();
              chargedDecoded = chunks.decodedBytes();
            } else segments.add(owner.resolve(rawOffset, size, entry.codec(), staged));
            Planned<K> measured = entry.segmented(key, segments);
            admitted.checkStored(measured, processing);
            if (chunked) {
              if (chargedDecoded > limits.maxDecodedBytes() - decoded)
                throw processing.limit(
                    "maxDecodedBytes",
                    limits.maxDecodedBytes(),
                    BigInteger.valueOf(decoded).add(BigInteger.valueOf(chargedDecoded)).toString());
              decoded += chargedDecoded;
            }
            sized.add(measured);
          }
        }
      }
    } catch (Throwable failure) {
      pending = failure;
    }
    closeStage(stage, pending, run);
    return sized;
  }

  /**
   * Resolves raw payload ranges of one entry to archive-set Content Sharing owners, encoding a new
   * owner into the stable spool. The spool, index, owner list, budget, and coordinator lease are
   * run-wide; only the checkpoints and the PROCESSING location belong to the entry.
   *
   * @param checkpoint observes cancellation at the entry's ordinal while transferring or encoding
   * @param comparison observes cancellation while digesting and comparing sharing candidates
   */
  private record OwnerResolver(
      SpillBuffer stable,
      ContentSharing<SetOwner> index,
      List<SetOwner> owners,
      boolean sharing,
      PayloadWindows.Checkpoint checkpoint,
      PayloadWindows.Checkpoint comparison,
      ResourceBudget budget,
      ResourceBudget.Lease coordinator,
      IoContext processing) {
    /**
     * Returns the segment for one stabilized raw range: the earliest byte-identical owner when
     * sharing finds one, otherwise a new owner. A stored owner is the raw range itself; an encoded
     * one is the worker's finished result when there is one, else encoded here from the spool.
     *
     * @param staged the entry's worker result, or null when the coordinator must encode
     */
    Admitted.Segment resolve(long rawStart, long rawSize, Codec codec, TransformStage.Input staged)
        throws IOException {
      String key = null;
      if (sharing) {
        key =
            ContentSharing.key(
                rawSize, PayloadWindows.digest(stable::read, rawStart, rawSize, comparison), false);
        SetOwner match =
            index.find(
                key,
                candidate ->
                    PayloadWindows.equal(
                        stable::read,
                        candidate.rawOffset(),
                        stable::read,
                        rawStart,
                        rawSize,
                        comparison));
        if (match != null) return match.segment(rawSize);
      }
      long storedOffset = rawStart;
      long storedSize = rawSize;
      if (codec != Codec.STORED) {
        storedOffset = stable.size();
        long start = storedOffset;
        PayloadWindows.Sink sink = (relative, bytes) -> stable.write(start + relative, bytes);
        if (staged != null && staged.hasEncoded()) {
          try (ReadableByteChannel encoded = staged.encodedChannel()) {
            PayloadWindows.transfer(encoded, staged.encodedSize(), sink, checkpoint, processing);
          }
        } else {
          try (ReadableByteChannel input =
              PayloadWindows.channel(stable::read, rawStart, rawSize)) {
            Codecs.encode(codec, input, rawSize, sink, checkpoint, budget, coordinator, processing);
          }
        }
        storedSize = stable.size() - storedOffset;
      }
      SetOwner owner = new SetOwner(owners.size(), rawStart, storedOffset, storedSize, codec);
      owners.add(owner);
      // Digest collisions cannot establish sharing; only byte-distinct owners are indexed.
      if (sharing) index.add(key, owner);
      return owner.segment(rawSize);
    }
  }

  /**
   * Appends a BGR24 payload rewritten to BGRA32 and returns where the rewrite starts. Appending
   * keeps the source and destination from overlapping during expansion. Input windows hold a whole
   * number of three-byte pixels, so each expands into exactly one output window.
   */
  private static long expandBgr24(
      SpillBuffer stable, long start, long size, PayloadWindows.Checkpoint checkpoint)
      throws IOException {
    long normalized = stable.size();
    ByteBuffer input = ByteBuffer.allocate(49152);
    ByteBuffer output = ByteBuffer.allocate(65536);
    for (long position = 0, written = 0; position < size; ) {
      checkpoint.check();
      int count = (int) Math.min(input.capacity(), size - position);
      input.clear().limit(count);
      stable.read(start + position, input);
      input.flip();
      output.clear();
      while (input.hasRemaining())
        output.put(input.get()).put(input.get()).put(input.get()).put((byte) 255);
      int expanded = output.position();
      stable.write(normalized + written, output.flip());
      position += count;
      written += expanded;
    }
    return normalized;
  }

  /**
   * Reserves the coordinator's set-wide stabilization working set. It holds at most three windows
   * at once (a transfer, a sharing comparison's two, or a BGR24 rewrite's input and output). When
   * it also encodes, the encoder's heap joins a two-window comparison peak instead.
   */
  private static ResourceBudget.Lease reserveSetCoordinator(
      List<? extends Planned<?>> planned, boolean parallel, boolean chunked, ResourceBudget budget)
      throws ArchiveException {
    long heap = 3L * PayloadWindows.WINDOW_BYTES;
    long nativeBytes = 0;
    for (Planned<?> entry : planned) {
      boolean coordinatorEncodes =
          entry.codec() != Codec.STORED
              && (chunked || !parallel || !Codecs.workerEncodes(entry.codec()));
      if (!coordinatorEncodes) continue;
      Codecs.Cost cost = Codecs.cost(entry.codec(), entry.source().size());
      heap = Math.max(heap, cost.heap() + 2L * PayloadWindows.WINDOW_BYTES);
      nativeBytes = Math.max(nativeBytes, cost.nativeBytes());
    }
    return budget.reserve(heap, nativeBytes, 0, 0);
  }

  /**
   * Emits one part of a set-wide stabilized plan. Owners were decided for the whole set during
   * stabilization; each is copied once per part, at the first reference in output order, and every
   * later reference in the part repeats that position.
   */
  private static <K> void emitOwners(
      Admitted<K> admitted,
      List<Planned<K>> part,
      Layout layout,
      PublicationTransaction.StagedFile output,
      SpillBuffer stable,
      List<SetOwner> owners)
      throws IOException {
    output.reserve(layout.payloadStart());
    Map<Integer, Long> emitted = new HashMap<>();
    List<Placed<K>> placed = new ArrayList<>(part.size());
    long next = layout.payloadStart();
    for (Planned<K> entry : part) {
      output.checkpoint();
      List<Long> positions = new ArrayList<>(entry.segments().size());
      for (Admitted.Segment segment : entry.segments()) {
        Long position = emitted.get(segment.owner());
        if (position == null) {
          SetOwner owner = owners.get(segment.owner());
          long destination = next;
          PayloadWindows.replay(
              stable::read,
              owner.storedOffset(),
              owner.storedSize(),
              (offset, bytes) -> output.write(destination + offset, bytes));
          position = destination;
          emitted.put(segment.owner(), position);
          next = Math.addExact(next, owner.storedSize());
        }
        positions.add(position);
      }
      placed.add(
          new Placed<>(
              entry,
              positions.isEmpty() ? next : positions.getFirst(),
              entry.storedSize(),
              positions));
      output.completedEntry(entry.source().size());
    }
    for (Patch patch : admitted.tables(placed, layout))
      output.write(patch.position(), ByteBuffer.wrap(patch.bytes()));
  }

  /**
   * Wraps one part's staging with the adapter's readback declaration, which publication runs once
   * the staged handle has closed and before any member of the set is published.
   *
   * @param readback the part's declaration, or null when the family does not read its output back
   */
  private static PublicationTransaction.Writer writer(
      PublicationTransaction.Writer body, Admitted.Readback readback, Run run) {
    return new PublicationTransaction.Writer() {
      @Override
      public void write(PublicationTransaction.StagedFile output) throws IOException {
        body.write(output);
      }

      @Override
      public void validate(Path staged) throws IOException {
        if (readback != null) readBack(staged, readback, run);
      }
    };
  }

  /**
   * Reopens one staged part, requires a CONFORMING assessment, and decodes every entry through one
   * bounded window. The reader owns its own budget, so its peak is also admitted against the
   * still-live pack budget: readback must not silently exceed the operation's capacity.
   *
   * @throws ArchiveException INTERNAL {@link Admitted.Readback#nonconformingId()} for a part that
   *     reopens as nonconforming, or the reader's own structured failure
   */
  private static void readBack(Path staged, Admitted.Readback readback, Run run)
      throws IOException {
    PackRequest request = run.request;
    try (var credits =
            run.budget.reserve(
                readback.heapBytes(), readback.nativeBytes(), readback.handles(), 0);
        var archive =
            ArchiveReaders.open(
                staged,
                new OpenOptions(
                    request.compatibilityProfile(), request.resourceLimits(), request.ddsTarget()),
                Operation.PACK,
                request.diagnosticPolicy())) {
      if (archive.inspection().assessment().disposition() != ArchiveDisposition.CONFORMING)
        throw run.context.failure(FailureKind.INTERNAL, readback.nonconformingId(), null);
      ByteBuffer window = ByteBuffer.allocate(PayloadWindows.WINDOW_BYTES);
      for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
        try (var content = archive.entry(ordinal).openContent()) {
          do {
            run.operation.checkpoint(OperationPhase.PROCESSING, OptionalLong.of(ordinal));
            window.clear();
          } while (content.read(window) >= 0);
        }
      }
    }
  }

  /**
   * Replays one stabilized part: each record either references the earliest byte-identical record
   * already placed in this part or is copied to the next payload position. The stored/compressed
   * discriminator keeps a stored record from aliasing a compressed one with identical framed bytes.
   */
  private static <K> void replay(
      Admitted<K> admitted,
      List<Planned<K>> part,
      Map<Planned<K>, Long> offsets,
      Layout layout,
      PublicationTransaction.StagedFile output,
      SpillBuffer stable,
      boolean sharing)
      throws IOException {
    // Per-part scope: a part can reference only records already placed in this file.
    ContentSharing<Owner> index = new ContentSharing<>();
    List<Placed<K>> placed = new ArrayList<>(part.size());
    long next = layout.payloadStart();
    for (Planned<K> entry : part) {
      output.checkpoint();
      long source = offsets.get(entry);
      long size = entry.storedSize();
      Owner owner = null;
      String key = null;
      if (sharing) {
        key =
            ContentSharing.key(
                size,
                PayloadWindows.digest(stable::read, source, size, output::checkpoint),
                entry.codec() != Codec.STORED);
        owner =
            index.find(
                key,
                candidate ->
                    PayloadWindows.equal(
                        stable::read,
                        candidate.spoolOffset(),
                        stable::read,
                        source,
                        size,
                        output::checkpoint));
      }
      if (owner == null) {
        long position = next;
        PayloadWindows.replay(
            stable::read, source, size, (offset, bytes) -> output.write(position + offset, bytes));
        if (sharing) index.add(key, new Owner(source, position));
        placed.add(new Placed<>(entry, position, size));
        next = Math.addExact(next, size);
      } else placed.add(new Placed<>(entry, owner.position(), size));
      output.completedEntry(entry.source().size());
    }
    for (Patch patch : admitted.tables(placed, layout))
      output.write(patch.position(), ByteBuffer.wrap(patch.bytes()));
  }

  /**
   * Reserves the peak coordinator windows a streamed part holds at once (two while comparing a
   * sharing candidate, otherwise one transfer window) plus the largest codec working set.
   */
  private static ResourceBudget.Lease reserveWindows(
      List<? extends Planned<?>> planned, boolean sharing, ResourceBudget budget)
      throws ArchiveException {
    long heap = 0;
    long nativeBytes = 0;
    for (Planned<?> entry : planned) {
      Codecs.Cost cost = Codecs.cost(entry.codec(), entry.source().size());
      heap = Math.max(heap, cost.heap());
      nativeBytes = Math.max(nativeBytes, cost.nativeBytes());
    }
    return budget.reserve(
        (sharing ? 2L : 1L) * PayloadWindows.WINDOW_BYTES + heap, nativeBytes, 0, 0);
  }

  /**
   * Streams one part: places each payload after the layout's payload start in output order, then
   * applies the adapter's table patches. Worker results are consumed in Logical Plan Order.
   */
  private static <K> void stream(
      Admitted<K> admitted,
      List<Planned<K>> part,
      Layout layout,
      PublicationTransaction.StagedFile output,
      long firstOrdinal,
      Run run,
      ResourceBudget.Lease windows)
      throws IOException {
    // Per-part scope: a streamed part can reference only copies already staged in this file.
    ContentSharing<Long> sharing = new ContentSharing<>();
    List<Placed<K>> placed = new ArrayList<>(part.size());
    long next = layout.payloadStart();
    TransformStage stage =
        new TransformStage(
            part.stream().map(Planned::source).toList(),
            run.workers,
            run.budget,
            run.context,
            firstOrdinal,
            ignored -> null);
    Throwable pending = null;
    try {
      for (int index = 0; index < part.size(); index++) {
        try (TransformStage.Input staged = stage.next(run.operation)) {
          Planned<K> entry = part.get(index);
          IoContext processing = processing(run.context, firstOrdinal + index);
          Placed<K> result =
              run.request.options().sharing()
                  ? placeShared(
                      entry,
                      staged,
                      next,
                      output,
                      sharing,
                      processing,
                      run,
                      windows,
                      admitted.sharing().basis())
                  : place(entry, staged, next, output, processing, run, windows);
          // Shared copies always lie before `next`; only a zero-length match can equal it, and
          // advancing by its zero size is a no-op, so equality identifies a fresh placement.
          if (result.offset() == next) next = Math.addExact(next, result.storedSize());
          placed.add(result);
          output.completedEntry(entry.source().size());
        }
      }
    } catch (Throwable failure) {
      pending = failure;
    }
    closeStage(stage, pending, run);
    for (Patch patch : admitted.tables(placed, layout))
      output.write(patch.position(), ByteBuffer.wrap(patch.bytes()));
  }

  /**
   * Joins the transform stage's workers and rethrows the first outcome. When processing and the
   * close both failed with structured outcomes, both are kept, the close as a Secondary Failure.
   */
  private static void closeStage(TransformStage stage, Throwable pending, Run run)
      throws IOException {
    try {
      stage.close();
    } catch (Throwable cleanup) {
      if (pending == null) pending = cleanup;
      else if (pending instanceof IOException first && cleanup instanceof IOException second) {
        // Closing worker results can fail after processing; keep both structured outcomes.
        var failures = new FailureRetention(run.request.resourceLimits(), Operation.PACK);
        failures.accept(structured(first, run.context));
        failures.accept(structured(second, run.context));
        pending = failures.finish(List.of());
      } else pending.addSuppressed(cleanup);
    }
    if (pending instanceof IOException checked) throw checked;
    if (pending instanceof Error fatal) throw fatal;
    if (pending instanceof RuntimeException unchecked) throw unchecked;
    if (pending != null) throw new AssertionError(pending);
  }

  /** Writes one unshared stored record directly at the next payload position. */
  private static <K> Placed<K> place(
      Planned<K> entry,
      TransformStage.Input staged,
      long position,
      PublicationTransaction.StagedFile output,
      IoContext processing,
      Run run,
      ResourceBudget.Lease windows)
      throws IOException {
    byte[] frame = entry.frame();
    if (frame.length > 0) output.write(position, ByteBuffer.wrap(frame));
    long start = position + frame.length;
    long[] extent = {0};
    consume(
        entry.source(),
        staged,
        input ->
            Codecs.encode(
                entry.codec(),
                input,
                entry.source().size(),
                (offset, bytes) -> {
                  extent[0] = Math.max(extent[0], offset + bytes.remaining());
                  output.write(start + offset, bytes);
                },
                output::checkpoint,
                run.budget,
                windows,
                processing),
        processing);
    return new Placed<>(entry, position, frame.length + extent[0]);
  }

  /**
   * Stabilizes one record in part-owned scratch while digesting it, then either references the
   * earliest byte-identical copy in this part or replays it at the next payload position.
   */
  private static <K> Placed<K> placeShared(
      Planned<K> entry,
      TransformStage.Input staged,
      long position,
      PublicationTransaction.StagedFile output,
      ContentSharing<Long> sharing,
      IoContext processing,
      Run run,
      ResourceBudget.Lease windows,
      Admitted.Sharing.Basis basis)
      throws IOException {
    SpillBuffer scratch = output.scratch();
    ArchiveException primary = null;
    try {
      MessageDigest digest = ContentSharing.sha256();
      byte[] frame = entry.frame();
      if (frame.length > 0) {
        scratch.write(0, ByteBuffer.wrap(frame));
        // A raw basis compares decoded bytes only; the frame is part of a stored record.
        if (basis == Admitted.Sharing.Basis.STORED) digest.update(frame);
      }
      consume(
          entry.source(),
          staged,
          input ->
              Codecs.encode(
                  entry.codec(),
                  input,
                  entry.source().size(),
                  (offset, bytes) -> {
                    digest.update(bytes.duplicate());
                    scratch.write(frame.length + offset, bytes);
                  },
                  output::checkpoint,
                  run.budget,
                  windows,
                  processing),
          processing);
      scratch.seal();
      long size = scratch.size();
      String key =
          ContentSharing.key(
              size,
              digest.digest(),
              basis == Admitted.Sharing.Basis.STORED && entry.codec() != Codec.STORED);
      Long match =
          sharing.find(
              key,
              candidate ->
                  PayloadWindows.equal(
                      scratch::read, 0, output::read, candidate, size, output::checkpoint));
      if (match != null) return new Placed<>(entry, match, size);
      PayloadWindows.replay(
          scratch::read, 0, size, (offset, bytes) -> output.write(position + offset, bytes));
      sharing.add(key, position);
      return new Placed<>(entry, position, size);
    } catch (ArchiveException failure) {
      primary = failure;
      throw failure;
    } finally {
      try {
        scratch.close();
      } catch (ArchiveException cleanup) {
        if (primary == null) throw cleanup;
        // Keep cleanup as structured secondary evidence, including any exact residual path.
        var failures = new FailureRetention(run.request.resourceLimits(), Operation.PACK);
        failures.accept(primary);
        failures.accept(cleanup);
        throw failures.finish(List.of());
      }
    }
  }

  /** Uses a staged worker result or the established direct stream for one logical source. */
  private static void consume(
      PackSources.Entry entry,
      TransformStage.Input staged,
      PackSources.Reader reader,
      IoContext processing)
      throws IOException {
    if (staged == null) PackSources.consume(entry, reader, processing);
    else {
      try (ReadableByteChannel input = staged.channel()) {
        reader.read(input);
      }
    }
  }

  /** Returns the PROCESSING location of one operation-wide output ordinal. */
  private static IoContext processing(IoContext context, long ordinal) {
    return new IoContext(
        context.path(), Operation.PACK, OperationPhase.PROCESSING, OptionalLong.of(ordinal));
  }

  /** Describes one staged part for the report. */
  private static OperationReport.ArchivePart archivePart(
      PackRequest request,
      int number,
      PublicationTransaction.StagedFile output,
      List<? extends Planned<?>> part) {
    return new OperationReport.ArchivePart(
        PublicationTransaction.splitPath(
            request.destination().toAbsolutePath().normalize(), number),
        output.size(),
        part.size());
  }

  /** Attaches the staged Archive Parts to publication's settled report. */
  private static OperationReport withParts(
      OperationReport report, List<OperationReport.ArchivePart> parts) {
    return new OperationReport(
        report.operation(), report.artifacts(), report.diagnostics(), report.assessment(), parts);
  }

  /** Wraps an unstructured I/O failure as SOURCE {@code operation.source-io}. */
  private static ArchiveException structured(IOException failure, IoContext context) {
    return failure instanceof ArchiveException archive
        ? archive
        : context.failure(FailureKind.SOURCE, "operation.source-io", failure);
  }

  /**
   * One stabilized record: the entry with its exact stored size, and where its framed bytes start
   * in the stable spool.
   */
  private record Stable<K>(Planned<K> planned, long offset) {}

  /** A placed Content Sharing owner: its stable spool offset and its position in the part. */
  private record Owner(long spoolOffset, long position) {}

  /**
   * One archive-set Content Sharing owner in the stable spool.
   *
   * @param id the owner's identity, its index in creation order
   * @param rawOffset where its raw bytes start, which later candidates are compared against
   * @param storedOffset where its stored bytes start; equal to {@code rawOffset} when stored
   * @param storedSize the stored length every part emits once
   * @param codec the encoding its stored bytes use
   */
  private record SetOwner(int id, long rawOffset, long storedOffset, long storedSize, Codec codec) {
    /** Returns a segment of {@code rawSize} raw bytes that this owner stores. */
    Admitted.Segment segment(long rawSize) {
      return new Admitted.Segment(rawSize, storedSize, codec, id);
    }
  }

  /** Stages one part of a stabilized plan from the stable spool. */
  @FunctionalInterface
  private interface Replay<K> {
    /** Places the part's payloads, then applies the adapter's tables. */
    void write(List<Planned<K>> part, Layout layout, PublicationTransaction.StagedFile output)
        throws IOException;
  }

  /**
   * Operation-owned collaborators every emission path borrows, plus the lifecycle state the
   * epilogue reads after a failure.
   */
  private static final class Run {
    final PackRequest request;
    final OperationSession operation;
    final IoContext context;
    final ResourceBudget budget;
    WorkerSelection.UpTo workers;

    /** Once publication runs, it owns cleanup, progress, and the delivered failure. */
    boolean publicationOwnsSession;

    /** The stabilized emission's ordered spool, closed by the last writer or the epilogue. */
    SpillBuffer stable;

    Run(PackRequest request, OperationSession operation, IoContext context, ResourceBudget budget) {
      this.request = request;
      this.operation = operation;
      this.context = context;
      this.budget = budget;
    }
  }
}
