package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Planned;
import java.io.IOException;
import java.util.*;

/**
 * The Pack Pipeline: one owner for every pack's I/O, lifecycle, Content Sharing, and failure
 * handling, driven by a pure {@link FamilyAdapter}. This entry owns the operation session, resource
 * budget, and the fixed admission and planning sequence, then dispatches over the adapter's {@link
 * Admitted.Emission} to the module that splits, preflights targets, stabilizes or streams, places
 * payloads, patches tables, and publishes: {@link StreamedEmitter} or {@link StabilizedEmitter}.
 * The session's operation template owns the report and the single failure epilogue.
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
   * <p>The pack runs as a body of the session's operation template ({@link OperationSession#run}),
   * which owns the single failure epilogue (D7). A {@link RuntimeException} or {@link
   * AssertionError} raised before publication therefore becomes an INTERNAL {@code
   * operation.internal-failure} at the current phase, settled with CLEANUP progress like any other
   * pre-publication failure. A {@link VirtualMachineError} still propagates.
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
    // The pack's own location is its destination; each entry's source locates its own failures.
    var context = IoContext.destination(request.destination(), Operation.PACK);
    return operation.run(
        context, resources -> pack(request, adapter, operation, context, resources));
  }

  /**
   * The pack body: admission, planning, then the Emission's module, which publishes. It registers
   * the resource budget with the template; a stabilized Emission also registers its stable spool.
   *
   * @return the artifacts publication settled and the staged Archive Parts
   * @throws IOException a failure before publication; publication accepts its own failures
   */
  private static <K> OperationSession.Published pack(
      PackRequest request,
      FamilyAdapter<K> adapter,
      OperationSession operation,
      IoContext context,
      OperationSession.Resources resources)
      throws IOException {
    // The pipeline owns the budget lifetime (D10); the template closes it before the epilogue
    // settles.
    ResourceBudget budget = ResourceBudget.forMutation(request.resourceLimits(), context);
    resources.register(budget::close, context);
    operation.begin();
    WorkerSelection.UpTo workers = WorkerLimits.snapshot(request.workerSelection());
    Admitted<K> admitted = adapter.admit(request, context);
    Admitted.Emission<K> emission = admitted.emission();
    Optional<Admitted.Chunker<K>> chunker = emission.chunker();
    // Capability admission is the last step of admit's rung: it runs before source planning, as
    // the family packers' own checks did, but here rather than in the pure adapter.
    Codecs codecs = Codecs.admit(admitted.requiredCodecs(), context);
    // Every collaborator now exists, so the run every Emission path borrows is complete.
    var run = new Run(request, operation, context, budget, resources, workers, codecs);
    List<PackSources.Entry> sources =
        PackSources.plan(request, operation, budget, admitted.nameCharset());
    List<Planned<K>> planned = admitted.plan(sources, context);
    // Each lease stays live until the budget closes: it covers per-entry state the family keeps
    // for the whole operation.
    long entryReserve = chunker.map(Admitted.Chunker::entryReserveBytes).orElse(0L);
    if (entryReserve > 0)
      for (int index = 0; index < planned.size(); index++) budget.reserve(entryReserve, 0, 0, 0);
    rejectUnmatchedOverrides(request, sources, context);
    if (planned.isEmpty())
      throw context.failure(
          FailureKind.POLICY, admitted.diagnosticPrefix() + ".empty-entry-set", null);
    // A chunking family's decoded extent is known only from each envelope, so stabilization
    // charges it instead, still in Logical Plan Order.
    if (chunker.isEmpty()) chargeDecoded(sources, request.resourceLimits(), context);
    SplitPlanner<K> planner = splitPlanner(admitted, emission, request.options().splitting());
    return switch (emission) {
      case Admitted.Emission.Streamed<K> streamed ->
          StreamedEmitter.emit(run, admitted, planner, planned);
      case Admitted.Emission.PerPartRecords<K> records ->
          StabilizedEmitter.emit(
              run, admitted, planner, planned, new PerPartRecordStabilizer<>(admitted));
      case Admitted.Emission.SetOwners<K> owners ->
          StabilizedEmitter.emit(
              run,
              admitted,
              planner,
              planned,
              new SetOwnerStabilizer<>(admitted, owners.chunker()));
    };
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
    var decoded = new DecodedLimit(limits);
    for (PackSources.Entry source : sources) decoded.charge(source.size(), context);
  }

  /**
   * Returns the byte target a Splitting arm selects: an explicit target as given, the family
   * default, or one byte for legacy per-entry splitting; 0 never splits.
   *
   * @param familyDefault the adapter's {@link Admitted#defaultSplitTarget()}
   */
  static long target(long familyDefault, PackOptions.Splitting splitting) {
    return switch (splitting) {
      case PackOptions.Splitting.FamilyDefault ignored -> familyDefault;
      case PackOptions.Splitting.UpToBytes explicit -> explicit.targetBytes();
      case PackOptions.Splitting.LegacyPerEntry ignored -> 1L;
    };
  }

  /**
   * Returns the split planner for one pack: the adapter's split cost, the payload cost its Emission
   * case implies, and the byte target the request's Splitting arm resolves to. Building it is pure,
   * so building it before dispatch moves no failure.
   *
   * @param emission the Emission {@code admitted} declared, read once at admission
   */
  private static <K> SplitPlanner<K> splitPlanner(
      Admitted<K> admitted, Admitted.Emission<K> emission, PackOptions.Splitting splitting) {
    return new SplitPlanner<>(
        admitted.splitCost(),
        SplitPlanner.payloadCost(emission),
        target(admitted.defaultSplitTarget(), splitting));
  }
}
