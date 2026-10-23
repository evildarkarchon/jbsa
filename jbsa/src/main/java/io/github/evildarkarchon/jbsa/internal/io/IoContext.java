package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Internal semantic location; provider details are retained only as exception causes.
 *
 * <p>A location is either an Operation Source or an Operation Destination. Its role decides two
 * things for every failure it structures (ADR-0006): the {@link DiagnosticLocation} slot its path
 * fills ({@code archive} for a source, {@code artifact} for a destination), and the Failure Kind of
 * an I/O fault, which is SOURCE or DESTINATION by role and never by the direction of the I/O. A
 * path's side is fixed by its place in the operation: reading the operation's own staged output is
 * a destination read.
 *
 * <p>{@link #structure} is the one structuring entry. Every catch site hands its throwable over;
 * the entry rethrows both stop signals and fatal errors, keeps structured failures, and maps the
 * rest. The raw {@link #failure(FailureKind, String, Throwable)} constructor exists for kinds the
 * role does not decide.
 *
 * @param role whether the location is an Operation Source or an Operation Destination
 * @param path the source or destination path; empty for a generated source, which has none
 * @param entryName the entry's complete name, set only for a generated source, which has no path
 * @param operation the operation the failure belongs to
 * @param phase the phase the failure occurs in
 * @param ordinal the logical input or artifact ordinal, if the failure has one
 */
public record IoContext(
    Role role,
    Optional<Path> path,
    Optional<String> entryName,
    Operation operation,
    OperationPhase phase,
    OptionalLong ordinal) {

  /** Which side of the operation a location is on. */
  public enum Role {
    /** What the operation reads to obtain its content; located in {@code archive}. */
    SOURCE(FailureKind.SOURCE, "operation.source-io"),
    /**
     * What the operation creates, including staged and scratch data; located in {@code artifact}.
     */
    DESTINATION(FailureKind.DESTINATION, "operation.destination-io");

    private final FailureKind kind;
    private final String ioIdentifier;

    /** Binds the side to the one Failure Kind and plain I/O identifier its failures derive. */
    Role(FailureKind kind, String ioIdentifier) {
      this.kind = kind;
      this.ioIdentifier = ioIdentifier;
    }

    /** Returns the Failure Kind every role failure on this side has: SOURCE or DESTINATION. */
    public FailureKind kind() {
      return kind;
    }
  }

  /** Requires every member; a generated source's empty path is the only absent location. */
  public IoContext {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(entryName, "entryName");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(ordinal, "ordinal");
  }

  /** Creates a PREFLIGHT location at a source the operation reads. */
  public static IoContext source(Path path, Operation operation) {
    return new IoContext(
        Role.SOURCE,
        Optional.of(path),
        Optional.empty(),
        operation,
        OperationPhase.PREFLIGHT,
        OptionalLong.empty());
  }

  /** Creates a PREFLIGHT location at a destination the operation creates. */
  public static IoContext destination(Path path, Operation operation) {
    return new IoContext(
        Role.DESTINATION,
        Optional.of(path),
        Optional.empty(),
        operation,
        OperationPhase.PREFLIGHT,
        OptionalLong.empty());
  }

  /**
   * Creates a PREFLIGHT location at a generated source. It has no path, so it is located by its
   * entry name and, once {@link #at(OperationPhase, OptionalLong)} gives it one, its entry ordinal.
   */
  public static IoContext generated(String entryName, Operation operation) {
    return new IoContext(
        Role.SOURCE,
        Optional.empty(),
        Optional.of(entryName),
        operation,
        OperationPhase.PREFLIGHT,
        OptionalLong.empty());
  }

  /** Returns this location at another phase and ordinal, keeping its role, path and name. */
  public IoContext at(OperationPhase phase, OptionalLong ordinal) {
    return new IoContext(role, path, entryName, operation, phase, ordinal);
  }

  /** Returns this location at another phase, keeping its ordinal. */
  public IoContext at(OperationPhase phase) {
    return at(phase, ordinal);
  }

  /**
   * Returns this location moved to another path on the same side, such as an owned scratch file.
   */
  public IoContext at(Path path) {
    return new IoContext(role, Optional.of(path), entryName, operation, phase, ordinal);
  }

  /** Returns where this location points: its path in the slot its role selects. */
  public DiagnosticLocation location() {
    return location(ordinal, entryName, Optional.empty(), Optional.empty());
  }

  /**
   * Returns an exact place inside this location's path, such as a wire field of an archive being
   * read, with the path in the slot its role selects.
   */
  public DiagnosticLocation location(
      OptionalLong entryOrdinal,
      Optional<String> name,
      Optional<String> field,
      Optional<DiagnosticLocation.ByteSpan> byteSpan) {
    boolean source = role == Role.SOURCE;
    return new DiagnosticLocation(
        source ? path : Optional.empty(),
        entryOrdinal,
        name,
        field,
        byteSpan,
        source ? Optional.empty() : path);
  }

  /**
   * The structuring entry: hands any throwable over and returns the failure to throw or accept.
   *
   * <ul>
   *   <li>A stop signal, either {@link OperationStopped} or {@link WorkerStopped}, is rethrown
   *       unchanged: it is never a Failure Kind.
   *   <li>An {@link Error} other than {@link AssertionError} is rethrown, a {@link
   *       VirtualMachineError} or thread death among them, matching the template (ADR-0005 §1).
   *   <li>An {@link ArchiveException} is kept with its own evidence and location.
   *   <li>Any other {@link IOException} becomes this role's I/O failure, SOURCE {@code
   *       operation.source-io} or DESTINATION {@code operation.destination-io}, with the provider
   *       exception retained only as its cause.
   *   <li>A {@link RuntimeException}, an {@link AssertionError} or any other throwable becomes
   *       INTERNAL {@code operation.internal-failure}.
   * </ul>
   *
   * @throws StopSignal the failure itself, when it is a stop
   */
  public ArchiveException structure(Throwable failure) throws StopSignal {
    return structure(failure, role.ioIdentifier);
  }

  /**
   * Creates this role's plain I/O failure for a provider fault the entry would not read as I/O,
   * such as a {@link SecurityException} denying access to the path.
   */
  public ArchiveException ioFailure(Throwable cause) {
    return roleFailure(role.ioIdentifier, cause);
  }

  /**
   * The structuring entry with an identifier override for a raw {@link IOException}, such as {@code
   * source.attributes} or {@code extraction.destination-io}. Every other case is handled as {@link
   * #structure(Throwable)} handles it.
   *
   * @throws StopSignal the failure itself, when it is a stop
   */
  public ArchiveException structure(Throwable failure, String identifier) throws StopSignal {
    Objects.requireNonNull(failure, "failure");
    switch (failure) {
      case StopSignal stop -> throw stop;
      case AssertionError bug -> {
        return failure(FailureKind.INTERNAL, "operation.internal-failure", bug);
      }
      case Error fatal -> throw fatal;
      case ArchiveException archive -> {
        return archive;
      }
      case IOException io -> {
        return roleFailure(identifier, io);
      }
      default -> {
        return failure(FailureKind.INTERNAL, "operation.internal-failure", failure);
      }
    }
  }

  /**
   * Hands a worker's in-flight throwable over after its catch site closed its resources. A stop or
   * a propagating {@link Error} carries each cleanup failure with {@link Throwable#addSuppressed}
   * and is rethrown. The ordered worker runner settles the evidence a {@link WorkerStopped} carries
   * into the session; nothing settles evidence attached to an {@link OperationStopped}, so a
   * coordinator site that unwinds the session's stop accepts its cleanup into the session itself.
   * Any other throwable is structured as {@link #structure(Throwable)} does and folded with the
   * cleanup failures into one failure, omitting no record.
   *
   * @param cleanup the structured failures of closing the worker's resources, in close order
   * @return the folded failure for the worker to throw
   * @throws StopSignal the failure itself, carrying the cleanup evidence, when it is a stop
   */
  public ArchiveException settle(Throwable failure, List<ArchiveException> cleanup)
      throws StopSignal {
    Objects.requireNonNull(failure, "failure");
    if (failure instanceof StopSignal
        || (failure instanceof Error && !(failure instanceof AssertionError))) {
      cleanup.forEach(failure::addSuppressed);
    }
    ArchiveException primary = structure(failure);
    if (cleanup.isEmpty()) return primary;
    // A fold, not a retention: the session applies the policy and ceilings once accepted.
    var failures = FailureRetention.folding(operation);
    failures.accept(primary);
    cleanup.forEach(failures::accept);
    return failures.finish(List.of());
  }

  /**
   * Creates this role's failure: SOURCE at a source, DESTINATION at a destination, so a failure
   * cannot name one side while being located at the other.
   *
   * @param cause the retained cause, or null for a role failure observed without one
   */
  public ArchiveException roleFailure(String identifier, Throwable cause) {
    return build(role.kind(), identifier, cause, Map.of());
  }

  /**
   * Creates a checked failure of a kind the role does not decide, without deriving public data from
   * a provider's message.
   *
   * @throws IllegalArgumentException if {@code kind} is SOURCE or DESTINATION, which only {@link
   *     #roleFailure} and {@link #structure} derive
   * @throws IllegalStateException if {@code cause} is a stop signal, which code must hand to {@link
   *     #structure} rather than wrap
   */
  public ArchiveException failure(FailureKind kind, String identifier, Throwable cause) {
    if (kind == FailureKind.SOURCE || kind == FailureKind.DESTINATION)
      throw new IllegalArgumentException(
          "SOURCE and DESTINATION are derived from the location's role");
    return build(kind, identifier, cause, Map.of());
  }

  /** Records exact decimal policy evidence, including values beyond signed long after overflow. */
  public ArchiveException limit(String field, long ceiling, String observed) {
    return build(
        FailureKind.POLICY,
        "operation.resource-limit",
        null,
        Map.of("field", field, "ceiling", Long.toString(ceiling), "observed", observed));
  }

  /**
   * Moves a failure raised elsewhere, such as by a shared resource budget at its own phase, to this
   * location's phase, ordinal and location, keeping its kind, identifier, values and cause.
   *
   * @throws IllegalArgumentException if the failure is the other side's SOURCE or DESTINATION,
   *     which this location could not report without naming one side while locating the other
   */
  public ArchiveException relocated(ArchiveException failure) {
    DiagnosticLocation location = location();
    Failure previous = failure.primaryFailure();
    if ((previous.kind() == FailureKind.SOURCE || previous.kind() == FailureKind.DESTINATION)
        && previous.kind() != role.kind())
      throw new IllegalArgumentException("A role failure cannot move to the other side");
    Failure primary =
        new Failure(
            previous.kind(),
            phase,
            ordinal,
            previous.diagnosticIdentifier(),
            Optional.of(location),
            previous.cause());
    List<Diagnostic> diagnostics =
        failure.diagnostics().stream()
            .map(
                diagnostic ->
                    new Diagnostic(
                        diagnostic.identifier(),
                        diagnostic.severity(),
                        diagnostic.operation(),
                        phase,
                        location,
                        diagnostic.values(),
                        diagnostic.explanation()))
            .toList();
    return new ArchiveException(
        failure.getMessage(),
        primary,
        diagnostics,
        failure.artifacts(),
        failure.assessment(),
        failure.secondaryFailures());
  }

  /**
   * Keeps stable diagnostic evidence independent of provider and operating-system exception text.
   */
  private ArchiveException build(
      FailureKind kind, String identifier, Throwable cause, Map<String, String> values) {
    // A stop signal is not a failure, and whoever it stops for already holds the evidence.
    // Wrapping it would admit a bogus failure, so code that bypassed the entry fails loudly.
    if (cause instanceof StopSignal)
      throw new IllegalStateException(
          "A stop signal must be handed to the structuring entry, never wrapped", cause);
    DiagnosticLocation location = location();
    Diagnostic diagnostic =
        new Diagnostic(
            identifier,
            DiagnosticSeverity.ERROR,
            operation,
            phase,
            location,
            new TreeMap<>(values),
            Optional.empty());
    Failure primary =
        new Failure(
            kind,
            phase,
            ordinal,
            Optional.of(identifier),
            Optional.of(location),
            Optional.ofNullable(cause));
    return new ArchiveException(
        identifier, primary, List.of(diagnostic), List.of(), Optional.empty(), List.of());
  }
}
