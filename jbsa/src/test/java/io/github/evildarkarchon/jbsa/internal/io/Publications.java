package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

/**
 * Runs the single publication entry the way production does: inside the session's operation
 * template, with a mutation budget the body creates and registers, and a sealed plan. The
 * publication suites use it so their faults reach the same entry and configuration production uses.
 *
 * <p>The list-returning forms reproduce the old whole-operation shape: they return the settled
 * artifacts on success and throw the finished failure otherwise.
 */
final class Publications {
  private Publications() {}

  /**
   * Publishes a streamed archive set with the standard Diagnostic Policy and no source to close.
   */
  static List<Artifact> archives(
      Path destination,
      List<PublicationTransaction.Writer> writers,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationControl control)
      throws ArchiveException {
    return archives(
        destination, writers, policy, limits, control, new PublicationTransaction.FileActions());
  }

  /** Publishes a streamed archive set through a filesystem fault seam. */
  static List<Artifact> archives(
      Path destination,
      List<PublicationTransaction.Writer> writers,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationControl control,
      PublicationTransaction.FileActions files)
      throws ArchiveException {
    var operation =
        new OperationSession(Operation.PACK, limits, DiagnosticPolicy.standard(), control);
    return archives(destination, writers, policy, limits, operation, files).artifacts();
  }

  /**
   * Publishes a streamed archive set in a caller-built session, which may already have entered
   * PREFLIGHT and carry evidence, and returns the settled report.
   */
  static OperationReport archives(
      Path destination,
      List<PublicationTransaction.Writer> writers,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationSession operation)
      throws ArchiveException {
    return archives(
        destination, writers, policy, limits, operation, new PublicationTransaction.FileActions());
  }

  /**
   * Runs an archive-set plan the way the Pack Pipeline does: the budget is located at the
   * destination before any part exists, and the template closes it after publication settles.
   */
  static OperationReport archives(
      Path destination,
      List<PublicationTransaction.Writer> writers,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationSession operation,
      PublicationTransaction.FileActions files)
      throws ArchiveException {
    IoContext context = IoContext.of(destination, Operation.PACK);
    var plan =
        new PublicationTransaction.Plan.ArchiveSet(
            destination, writers, policy, false, PublicationTransaction.SourceClose.NONE);
    return operation.run(
        context,
        resources -> {
          ResourceBudget budget = ResourceBudget.forMutation(limits, context);
          resources.register(budget::close);
          return new OperationSession.Published(
              PublicationTransaction.publish(operation, budget, plan, files));
        });
  }

  /** Extracts sequentially with the standard Diagnostic Policy and no source to close. */
  static List<Artifact> extract(
      Path root,
      List<PublicationTransaction.Entry> entries,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationControl control)
      throws ArchiveException {
    return extract(
        root, entries, policy, limits, control, new PublicationTransaction.FileActions());
  }

  /** Extracts sequentially through a filesystem fault seam. */
  static List<Artifact> extract(
      Path root,
      List<PublicationTransaction.Entry> entries,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationControl control,
      PublicationTransaction.FileActions files)
      throws ArchiveException {
    var operation =
        new OperationSession(Operation.EXTRACT, limits, DiagnosticPolicy.standard(), control);
    return extract(root, entries, policy, limits, operation, files).artifacts();
  }

  /** Extracts sequentially in a caller-built session and returns the settled report. */
  static OperationReport extract(
      Path root,
      List<PublicationTransaction.Entry> entries,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationSession operation)
      throws ArchiveException {
    return extract(
        root, entries, policy, limits, operation, new PublicationTransaction.FileActions());
  }

  /**
   * Runs a one-worker extraction plan the way the extractor does: the budget is located at the
   * normalized root and the first entry's archive ordinal (plan-level evidence, JBSA-OPS-006).
   */
  static OperationReport extract(
      Path root,
      List<PublicationTransaction.Entry> entries,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationSession operation,
      PublicationTransaction.FileActions files)
      throws ArchiveException {
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            entries,
            policy,
            new WorkerSelection.UpTo(1),
            ordinal -> {},
            PublicationTransaction.SourceClose.NONE);
    return run(operation, plan, limits, files);
  }

  /** Runs any extraction plan inside the template with an extractor-shaped budget. */
  static OperationReport run(
      OperationSession operation,
      PublicationTransaction.Plan.Extraction plan,
      ResourceLimits limits,
      PublicationTransaction.FileActions files)
      throws ArchiveException {
    long planOrdinal = plan.entries().isEmpty() ? 0 : plan.entries().getFirst().archiveOrdinal();
    var budgetContext =
        new IoContext(
            plan.root().toAbsolutePath().normalize(),
            Operation.EXTRACT,
            OperationPhase.PREFLIGHT,
            OptionalLong.of(planOrdinal));
    return operation.run(
        IoContext.of(plan.root(), Operation.EXTRACT),
        resources -> {
          ResourceBudget budget = ResourceBudget.forMutation(limits, budgetContext);
          resources.register(budget::close);
          return new OperationSession.Published(
              PublicationTransaction.publish(operation, budget, plan, files));
        });
  }
}
