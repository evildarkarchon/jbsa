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
   * limit charged in Logical Plan Order. Splitting, per-part layout admission, and always-on target
   * preflight follow before any source payload is read.
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
    boolean publicationOwnsSession = false;
    OperationReport result = null;
    var failures = new FailureRetention(request.resourceLimits(), Operation.PACK);
    // The pipeline owns the budget lifetime (D10); it closes before the epilogue settles.
    ResourceBudget budget = ResourceBudget.forMutation(request.resourceLimits(), context);
    try {
      operation.begin();
      WorkerSelection.UpTo workers = WorkerLimits.snapshot(request.workerSelection());
      Admitted<K> admitted = adapter.admit(request, context);
      requireStreaming(admitted);
      List<PackSources.Entry> sources =
          PackSources.plan(request, operation, budget, admitted.nameCharset());
      List<Planned<K>> planned = admitted.plan(sources, context);
      rejectUnmatchedOverrides(request, sources, context);
      if (planned.isEmpty())
        throw context.failure(
            FailureKind.POLICY, admitted.diagnosticPrefix() + ".empty-entry-set", null);
      chargeDecoded(sources, request.resourceLimits(), context);
      List<List<Planned<K>>> parts = split(planned, admitted, request.options().splitting());
      List<Layout> layouts = new ArrayList<>(parts.size());
      for (var part : parts) {
        Layout layout = admitted.layout(part, context);
        budget.metadata(layout.metadataBytes());
        layouts.add(layout);
      }
      PublicationTransaction.preflightArchiveTargets(
          request.destination(),
          parts.size(),
          request.targetPolicy(),
          request.resourceLimits(),
          context);
      var streaming =
          new Streaming(
              context,
              operation,
              budget,
              workers,
              request.options().sharing(),
              admitted.sharing().basis(),
              request.resourceLimits());
      var writers = new ArrayList<PublicationTransaction.Writer>();
      var completed = new ArrayList<OperationReport.ArchivePart>();
      long nextOrdinal = 0;
      for (int index = 0; index < parts.size(); index++) {
        List<Planned<K>> part = parts.get(index);
        Layout layout = layouts.get(index);
        long firstOrdinal = nextOrdinal;
        int number = index + 1;
        writers.add(
            output -> {
              stream(admitted, part, layout, output, firstOrdinal, streaming);
              completed.add(
                  new OperationReport.ArchivePart(
                      PublicationTransaction.splitPath(
                          request.destination().toAbsolutePath().normalize(), number),
                      output.size(),
                      part.size()));
            });
        nextOrdinal += part.size();
      }
      // D8: admit the coordinator windows and codec working set before any writer allocates them.
      try (var windows = reserveWindows(planned, streaming.sharing(), budget)) {
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
      failures.accept(structured(failure, context));
    } finally {
      budget.close();
    }
    // The single failure epilogue (D7). Publication already settled cleanup and progress for any
    // failure it owns; an earlier failure still needs the session's cleanup phase and report.
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

  /**
   * Rejects adapter declarations the pipeline cannot honor yet. Stabilized emission, and with it
   * stored split costs and set-wide sharing, arrives when versioned BSA migrates (#71).
   */
  private static void requireStreaming(Admitted<?> admitted) {
    if (admitted.emission() != Admitted.Emission.STREAMING)
      throw new UnsupportedOperationException("Stabilized emission is not implemented yet");
    // A streamed part cannot know encoded sizes or other parts' owners before it stages.
    if (admitted.splitCost().payload() != Admitted.PayloadCost.DECODED
        || admitted.sharing().scope() != Admitted.Sharing.Scope.PER_PART)
      throw new IllegalStateException(
          "Streaming emission needs decoded costs and per-part sharing");
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

  /**
   * Forms whole-entry parts in output order from the adapter's advisory split cost. A zero target
   * never splits; legacy per-entry splitting uses a one-byte target.
   */
  static <K> List<List<Planned<K>>> split(
      List<Planned<K>> planned, Admitted<K> admitted, PackOptions.Splitting splitting) {
    long target =
        switch (splitting) {
          case PackOptions.Splitting.FamilyDefault ignored -> admitted.defaultSplitTarget();
          case PackOptions.Splitting.UpToBytes explicit -> explicit.targetBytes();
          case PackOptions.Splitting.LegacyPerEntry ignored -> 1L;
        };
    Admitted.SplitCost<K> rule = admitted.splitCost();
    List<List<Planned<K>>> parts = new ArrayList<>();
    List<Planned<K>> current = new ArrayList<>();
    long estimated = 0;
    for (Planned<K> entry : planned) {
      long cost =
          Math.addExact(
              Math.addExact(rule.fixed(), rule.nameCost().applyAsLong(entry.key())),
              payloadCost(entry, rule.payload()));
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

  /** Returns one entry's payload split cost; streaming validation admits only decoded costs. */
  private static long payloadCost(Planned<?> entry, Admitted.PayloadCost payload) {
    return switch (payload) {
      case DECODED -> entry.source().size();
      case STORED, UNIQUE_STORED ->
          throw new IllegalStateException("Stored costs need stabilized emission");
    };
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
      Streaming env)
      throws IOException {
    // Per-part scope: a streamed part can reference only copies already staged in this file.
    ContentSharing<Long> sharing = new ContentSharing<>();
    List<Placed<K>> placed = new ArrayList<>(part.size());
    long next = layout.payloadStart();
    TransformStage stage =
        new TransformStage(
            part.stream().map(Planned::source).toList(),
            env.workers(),
            env.budget(),
            env.context(),
            firstOrdinal,
            ignored -> null);
    Throwable pending = null;
    try {
      for (int index = 0; index < part.size(); index++) {
        try (TransformStage.Input staged = stage.next(env.operation())) {
          Planned<K> entry = part.get(index);
          IoContext processing =
              new IoContext(
                  env.context().path(),
                  Operation.PACK,
                  OperationPhase.PROCESSING,
                  OptionalLong.of(firstOrdinal + index));
          Placed<K> result =
              env.sharing()
                  ? placeShared(entry, staged, next, output, sharing, processing, env)
                  : place(entry, staged, next, output, processing);
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
    try {
      stage.close();
    } catch (Throwable cleanup) {
      if (pending == null) pending = cleanup;
      else if (pending instanceof IOException first && cleanup instanceof IOException second) {
        // Closing worker results can fail after processing; keep both structured outcomes.
        var failures = new FailureRetention(env.limits(), Operation.PACK);
        failures.accept(structured(first, env.context()));
        failures.accept(structured(second, env.context()));
        pending = failures.finish(List.of());
      } else pending.addSuppressed(cleanup);
    }
    if (pending instanceof IOException checked) throw checked;
    if (pending instanceof Error fatal) throw fatal;
    if (pending instanceof RuntimeException unchecked) throw unchecked;
    if (pending != null) throw new AssertionError(pending);
    for (Patch patch : admitted.tables(placed, layout))
      output.write(patch.position(), ByteBuffer.wrap(patch.bytes()));
  }

  /** Writes one unshared stored record directly at the next payload position. */
  private static <K> Placed<K> place(
      Planned<K> entry,
      TransformStage.Input staged,
      long position,
      PublicationTransaction.StagedFile output,
      IoContext processing)
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
      Streaming env)
      throws IOException {
    SpillBuffer scratch = output.scratch();
    ArchiveException primary = null;
    try {
      MessageDigest digest = ContentSharing.sha256();
      byte[] frame = entry.frame();
      if (frame.length > 0) {
        scratch.write(0, ByteBuffer.wrap(frame));
        // A raw basis compares decoded bytes only; the frame is part of a stored record.
        if (env.basis() == Admitted.Sharing.Basis.STORED) digest.update(frame);
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
                  processing),
          processing);
      scratch.seal();
      long size = scratch.size();
      String key =
          ContentSharing.key(
              size,
              digest.digest(),
              env.basis() == Admitted.Sharing.Basis.STORED && entry.codec() != Codec.STORED);
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
        var failures = new FailureRetention(env.limits(), Operation.PACK);
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

  /** Wraps an unstructured I/O failure as SOURCE {@code operation.source-io}. */
  private static ArchiveException structured(IOException failure, IoContext context) {
    return failure instanceof ArchiveException archive
        ? archive
        : context.failure(FailureKind.SOURCE, "operation.source-io", failure);
  }

  /** Operation-owned collaborators every streamed part borrows. */
  private record Streaming(
      IoContext context,
      OperationSession operation,
      ResourceBudget budget,
      WorkerSelection.UpTo workers,
      boolean sharing,
      Admitted.Sharing.Basis basis,
      ResourceLimits limits) {}
}
