package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Layout;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Planned;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The outline both stabilized Emissions share, {@link Admitted.Emission.PerPartRecords} and {@link
 * Admitted.Emission.SetOwners}: every payload is stabilized in output order before splitting,
 * because the family's split cost and layout depend on exact stored sizes. A {@link Stabilizer}
 * supplies how records are stabilized and replayed; this outline owns the order around it and the
 * stable spool's lifetime.
 */
final class StabilizedEmitter {
  private StabilizedEmitter() {}

  /**
   * Stabilizes every payload, splits, and publishes the archive set. The order is fixed: target
   * preflight for the part count the plan already knows, complete the phase and enter PROCESSING,
   * open and register the stable spool, stabilize, seal the spool, split, every part's layout and
   * its metadata charge, the writers, then a stabilized archive-set plan published inside the
   * replay-window reservation. Stabilization is processing work, so progress enters PROCESSING
   * before the first source is read (Q10) and publication continues in that phase.
   *
   * <p>The stable spool is opened here and closed by the publication plan's source-close action,
   * after every part has staged and before the commit, or, after any earlier failure, by the
   * operation template, before the budget it draws on.
   *
   * @param planner the pack's split planner, for this Emission's payload cost
   * @param planned every planned entry in output order
   * @param stabilizer the Emission's stabilization and replay
   * @return the artifacts publication settled and the staged Archive Parts
   * @throws IOException a failure before publication; publication accepts its own failures
   */
  static <K> OperationSession.Published emit(
      Run run,
      Admitted<K> admitted,
      SplitPlanner<K> planner,
      List<Planned<K>> planned,
      Stabilizer<K> stabilizer)
      throws IOException {
    PackRequest request = run.request();
    boolean sharing = request.options().sharing();
    // D9: preflight never depends on sharing; it checks every part the plan can already count.
    PublicationTransaction.preflightArchiveTargets(
        request.destination(),
        planner.knownPartCount(planned, sharing, part -> admitted.layout(part, run.context())),
        request.targetPolicy(),
        request.resourceLimits(),
        run.context());
    run.operation().completePhase();
    run.operation().processing(false);
    // One spool bounds heap and handle use independently of entry count, and fixes split sizes.
    Path scratch = Path.of(System.getProperty("java.io.tmpdir"));
    SpillBuffer stable = SpillBuffer.open(scratch, run.budget(), run.context());
    // Stabilization scratch normally closes through the publication plan's source-close action,
    // after every part has staged and before commit; the template settles it after any earlier
    // failure, before the budget it draws on. Closing an already closed spool is a no-op.
    run.resources().register(stable::close, IoContext.destination(scratch, Operation.PACK));
    Stabilizer.Stabilized<K> stabilized = stabilizer.stabilize(run, planned, stable);
    long replayHeap = stabilizer.replayHeap(sharing);
    stable.seal();
    List<List<Planned<K>>> parts = planner.split(stabilized.sized());
    List<Layout> layouts = PartStaging.layouts(admitted, parts, run);
    var writers = new ArrayList<PublicationTransaction.Writer>();
    var completed = new ArrayList<OperationReport.ArchivePart>();
    for (int index = 0; index < parts.size(); index++) {
      List<Planned<K>> part = parts.get(index);
      Layout layout = layouts.get(index);
      int number = index + 1;
      writers.add(
          PartStaging.writer(
              output -> {
                stabilized.replay().write(part, layout, output);
                completed.add(PartStaging.archivePart(request, number, output, part));
              },
              admitted.readback(part, layout),
              run));
    }
    // Progress already entered PROCESSING above, and the parts have consumed stabilization once
    // they have all staged, so the spool is the plan's source: it closes before the commit.
    var plan =
        PublicationTransaction.Plan.ArchiveSet.stabilized(
            request.destination(), writers, request.targetPolicy(), stable::close);
    try (var windows = run.budget().reserve(replayHeap, 0, 0, 0)) {
      return new OperationSession.Published(
          PublicationTransaction.publish(run.operation(), run.budget(), plan), completed);
    }
  }

  /**
   * How one stabilized Emission writes its records into the stable spool and replays them into each
   * part. Replay is returned by {@link #stabilize}, so no part can be replayed before the records
   * it reads exist.
   *
   * @param <K> the family's per-entry wire key
   */
  sealed interface Stabilizer<K> permits PerPartRecordStabilizer, SetOwnerStabilizer {
    /**
     * Stabilizes every planned payload into the open, unsealed spool in output order, while
     * progress is in PROCESSING. The adapter's {@link Admitted#checkStored} runs once per entry,
     * before the next entry is read. The caller seals the spool afterwards and owns its lifetime.
     *
     * @param planned every planned entry in output order
     * @param stable the operation's stable spool
     * @return the entries with their exact stored sizes, in output order, and their replay
     * @throws IOException the first entry's failure, at its PROCESSING location
     */
    Stabilized<K> stabilize(Run run, List<Planned<K>> planned, SpillBuffer stable)
        throws IOException;

    /**
     * Returns the coordinator heap one part's replay holds at once, which the outline reserves
     * around publication.
     *
     * @param sharing whether the request enables Content Sharing
     */
    long replayHeap(boolean sharing);

    /**
     * A stabilized plan.
     *
     * @param sized the entries with their exact stored sizes, in output order, which splitting and
     *     layouts use
     * @param replay stages one part of this plan from the sealed spool
     */
    record Stabilized<K>(List<Planned<K>> sized, Replay<K> replay) {}
  }

  /** Stages one part of a stabilized plan from the stable spool. */
  @FunctionalInterface
  interface Replay<K> {
    /** Places the part's payloads, then applies the adapter's tables. */
    void write(List<Planned<K>> part, Layout layout, PublicationTransaction.StagedFile output)
        throws IOException;
  }
}
