package io.github.evildarkarchon.jbsa;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Immutable conformance evidence. Explanations are presentation only and must be excluded when
 * comparing conformance; record equality compares the entire value.
 *
 * @param identifier stable diagnostic identifier
 * @param severity intrinsic severity, including for rejected warnings
 * @param operation operation that established the evidence
 * @param phase stable operation phase
 * @param location structured evidence location
 * @param values canonical string values, copied into natural key order
 * @param explanation optional English explanation outside conformance comparison
 */
public record Diagnostic(
    String identifier,
    DiagnosticSeverity severity,
    Operation operation,
    OperationPhase phase,
    DiagnosticLocation location,
    SortedMap<String, String> values,
    Optional<String> explanation) {
  /** Copies the canonical values and rejects missing or blank semantic identifiers. */
  public Diagnostic {
    Objects.requireNonNull(identifier, "identifier");
    if (identifier.isBlank()) {
      throw new IllegalArgumentException("Diagnostic identifier must not be blank");
    }
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(values, "values");
    Objects.requireNonNull(explanation, "explanation");
    var copy = new TreeMap<String, String>();
    values.forEach(
        (key, value) ->
            copy.put(
                Objects.requireNonNull(key, "value key"), Objects.requireNonNull(value, "value")));
    values = Collections.unmodifiableSortedMap(copy);
  }
}
