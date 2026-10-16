package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/** Internal semantic location; provider details are retained only as exception causes. */
public record IoContext(
    Path path, Operation operation, OperationPhase phase, OptionalLong ordinal) {
  /** Creates a preflight context for a path-based operation. */
  public static IoContext of(Path path, Operation operation) {
    return new IoContext(path, operation, OperationPhase.PREFLIGHT, OptionalLong.empty());
  }

  /** Creates a checked failure without deriving public data from a provider's message. */
  public ArchiveException failure(FailureKind kind, String identifier, Throwable cause) {
    return failure(kind, identifier, cause, Map.of());
  }

  /**
   * Structures a failure to open, read, or close a source at this location. A structured failure
   * keeps its own evidence and location; any other {@link IOException} becomes SOURCE {@code
   * operation.source-io} here, with the provider exception retained only as its cause.
   *
   * @param failure the source-side failure
   * @return the failure to throw or accept
   */
  public ArchiveException sourceFailure(IOException failure) {
    return failure instanceof ArchiveException archive
        ? archive
        : failure(FailureKind.SOURCE, "operation.source-io", failure);
  }

  /** Records exact decimal policy evidence, including values beyond signed long after overflow. */
  public ArchiveException limit(String field, long ceiling, String observed) {
    return failure(
        FailureKind.POLICY,
        "operation.resource-limit",
        null,
        Map.of("field", field, "ceiling", Long.toString(ceiling), "observed", observed));
  }

  /**
   * Keeps stable diagnostic evidence independent of provider and operating-system exception text.
   */
  private ArchiveException failure(
      FailureKind kind, String identifier, Throwable cause, Map<String, String> values) {
    boolean destination = kind == FailureKind.DESTINATION;
    DiagnosticLocation location =
        new DiagnosticLocation(
            destination ? Optional.empty() : Optional.of(path),
            ordinal,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            destination ? Optional.of(path) : Optional.empty());
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
