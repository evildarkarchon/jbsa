package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * The one structuring entry and the role on the location (ADR-0006): stops are rethrown, never a
 * Failure Kind; SOURCE and DESTINATION follow only from the role; and the role picks the location
 * slot for every kind.
 */
class IoContextTest {
  private static final Path SOURCE_PATH = Path.of("source.bsa").toAbsolutePath();
  private static final Path DESTINATION_PATH = Path.of("output.ba2").toAbsolutePath();
  private static final IoContext SOURCE =
      IoContext.source(SOURCE_PATH, Operation.PACK)
          .at(OperationPhase.PROCESSING, OptionalLong.of(3));
  private static final IoContext DESTINATION =
      IoContext.destination(DESTINATION_PATH, Operation.PACK)
          .at(OperationPhase.PROCESSING, OptionalLong.of(3));
  private static final IoContext GENERATED =
      IoContext.generated("Data/Generated.bin", Operation.PACK)
          .at(OperationPhase.PROCESSING, OptionalLong.of(3));

  /** Both stop signals come back unchanged from every handing-over method. */
  @Test
  void stopSignalsAreRethrownUnchangedNeverAFailureKind() {
    for (StopSignal stop : new StopSignal[] {new OperationStopped(), new WorkerStopped()}) {
      for (IoContext context : List.of(SOURCE, DESTINATION, GENERATED)) {
        assertSame(stop, assertThrows(StopSignal.class, () -> context.structure(stop)));
        assertSame(
            stop, assertThrows(StopSignal.class, () -> context.structure(stop, "source.changed")));
        assertSame(stop, assertThrows(StopSignal.class, () -> context.settle(stop, List.of())));
      }
    }
  }

  /** Code that bypasses the entry and wraps a stop fails loudly instead of admitting a failure. */
  @Test
  void wrappingAStopIsRejected() {
    for (StopSignal stop : new StopSignal[] {new OperationStopped(), new WorkerStopped()}) {
      assertThrows(
          IllegalStateException.class,
          () -> SOURCE.failure(FailureKind.INTERNAL, "operation.internal-failure", stop));
      assertThrows(
          IllegalStateException.class, () -> DESTINATION.roleFailure("io.no-progress", stop));
    }
  }

  /** SOURCE and DESTINATION are derived only from the role, so neither can be passed explicitly. */
  @Test
  void sourceAndDestinationKindsCannotBePassedExplicitly() {
    for (FailureKind kind : new FailureKind[] {FailureKind.SOURCE, FailureKind.DESTINATION}) {
      for (IoContext context : List.of(SOURCE, DESTINATION, GENERATED)) {
        assertThrows(
            IllegalArgumentException.class, () -> context.failure(kind, "operation.io", null));
      }
    }
    assertEquals(FailureKind.SOURCE, SOURCE.roleFailure("io.no-progress", null).kind());
    assertEquals(FailureKind.DESTINATION, DESTINATION.roleFailure("io.no-progress", null).kind());
    assertEquals(FailureKind.SOURCE, GENERATED.roleFailure("io.no-progress", null).kind());
  }

  /** A source fills {@code archive} and a destination {@code artifact}, whatever the kind. */
  @Test
  void theRolePicksTheLocationSlotForEveryKind() throws Exception {
    for (FailureKind kind : FailureKind.values()) {
      if (kind == FailureKind.SOURCE
          || kind == FailureKind.DESTINATION
          || kind == FailureKind.CANCELLED) continue;
      assertSlots(SOURCE.failure(kind, "example.failure", null), Optional.of(SOURCE_PATH), null);
      assertSlots(
          DESTINATION.failure(kind, "example.failure", null), null, Optional.of(DESTINATION_PATH));
    }
    assertSlots(SOURCE.roleFailure("io.no-progress", null), Optional.of(SOURCE_PATH), null);
    assertSlots(
        DESTINATION.roleFailure("io.no-progress", null), null, Optional.of(DESTINATION_PATH));
    assertSlots(SOURCE.limit("maxScratchBytes", 1, "2"), Optional.of(SOURCE_PATH), null);
    assertSlots(DESTINATION.limit("maxScratchBytes", 1, "2"), null, Optional.of(DESTINATION_PATH));
    assertSlots(SOURCE.structure(new IOException("disk")), Optional.of(SOURCE_PATH), null);
    assertSlots(
        DESTINATION.structure(new IOException("disk")), null, Optional.of(DESTINATION_PATH));
  }

  /**
   * Relocation keeps a failure's kind, so it moves a policy failure but refuses to place the other
   * side's role failure at this location.
   */
  @Test
  void relocationNeverPlacesOneSidesFailureAtTheOther() {
    ArchiveException limit = SOURCE.limit("maxScratchBytes", 1, "2");
    ArchiveException moved = DESTINATION.at(OperationPhase.CLEANUP).relocated(limit);
    assertEquals(FailureKind.POLICY, moved.kind());
    assertEquals(OperationPhase.CLEANUP, moved.primaryFailure().phase());
    assertSlots(moved, null, Optional.of(DESTINATION_PATH));
    assertThrows(
        IllegalArgumentException.class,
        () -> DESTINATION.relocated(SOURCE.roleFailure("io.no-progress", null)));
    assertEquals(
        FailureKind.DESTINATION,
        DESTINATION.relocated(DESTINATION.roleFailure("io.no-progress", null)).kind());
  }

  /** A generated source has no path: it is located by its ordinal and entry name alone. */
  @Test
  void aGeneratedSourceIsLocatedByOrdinalAndEntryName() throws Exception {
    ArchiveException failure = GENERATED.structure(new IOException("factory"));
    assertEquals(FailureKind.SOURCE, failure.kind());
    DiagnosticLocation location = failure.primaryFailure().location().orElseThrow();
    assertEquals(Optional.empty(), location.archive());
    assertEquals(Optional.empty(), location.artifact());
    assertEquals(OptionalLong.of(3), location.entryOrdinal());
    assertEquals(Optional.of("Data/Generated.bin"), location.entryName());
    assertEquals(location, failure.diagnostics().getFirst().location());
  }

  /**
   * An I/O fault maps by role, with an optional identifier override; a structured failure is kept;
   * an unexpected throwable is INTERNAL; any other error propagates.
   */
  @Test
  void theEntryMapsEveryThrowableOneWay() throws Exception {
    var io = new IOException("provider text");
    ArchiveException source = SOURCE.structure(io);
    assertEquals(FailureKind.SOURCE, source.kind());
    assertEquals(
        Optional.of("operation.source-io"), source.primaryFailure().diagnosticIdentifier());
    assertSame(io, source.getCause());
    ArchiveException destination = DESTINATION.structure(io);
    assertEquals(FailureKind.DESTINATION, destination.kind());
    assertEquals(
        Optional.of("operation.destination-io"),
        destination.primaryFailure().diagnosticIdentifier());
    ArchiveException overridden = SOURCE.structure(io, "source.attributes");
    assertEquals(FailureKind.SOURCE, overridden.kind());
    assertEquals(
        Optional.of("source.attributes"), overridden.primaryFailure().diagnosticIdentifier());

    ArchiveException structured = SOURCE.failure(FailureKind.FORMAT, "format.example", null);
    assertSame(structured, DESTINATION.structure(structured));
    assertSame(structured, DESTINATION.structure(structured, "extraction.destination-io"));

    for (Throwable internal :
        new Throwable[] {new IllegalStateException("bug"), new AssertionError("bug")}) {
      ArchiveException failure = DESTINATION.structure(internal);
      assertEquals(FailureKind.INTERNAL, failure.kind(), internal.toString());
      assertEquals(
          Optional.of("operation.internal-failure"),
          failure.primaryFailure().diagnosticIdentifier());
      assertSame(internal, failure.getCause());
    }

    var fatal = new OutOfMemoryError("fatal");
    assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> SOURCE.structure(fatal)));
    var nonFatal = new LinkageError("not an assertion");
    assertSame(nonFatal, assertThrows(LinkageError.class, () -> SOURCE.structure(nonFatal)));
  }

  /**
   * A worker's cleanup evidence rides on its stop, and is folded into any other failure without
   * omitting a record.
   */
  @Test
  void settlingKeepsCleanupEvidence() throws Exception {
    ArchiveException cleanup =
        DESTINATION.at(OperationPhase.CLEANUP).roleFailure("operation.destination-io", null);
    var stop = new WorkerStopped();
    assertSame(stop, assertThrows(StopSignal.class, () -> SOURCE.settle(stop, List.of(cleanup))));
    assertArrayEquals(new Throwable[] {cleanup}, stop.getSuppressed());

    ArchiveException folded = SOURCE.settle(new IOException("read"), List.of(cleanup));
    assertEquals(FailureKind.SOURCE, folded.kind());
    assertEquals(List.of(cleanup.primaryFailure()), folded.secondaryFailures());
  }

  /**
   * ADR-0006's tie order: among failures that tie on phase, ordinal and identifier, the location
   * decides, comparing {@code archive}, then the entry name, then {@code artifact}, with present
   * members first. A source's failure therefore precedes a generated source's, which precedes a
   * destination's.
   */
  @Test
  void sourceLocatedFailuresWinTiesThenGeneratedThenDestination() {
    var retention = FailureRetention.folding(Operation.PACK);
    ArchiveException generated = GENERATED.failure(FailureKind.FORMAT, "format.example", null);
    ArchiveException destination = DESTINATION.failure(FailureKind.FORMAT, "format.example", null);
    ArchiveException source = SOURCE.failure(FailureKind.FORMAT, "format.example", null);
    retention.accept(generated);
    retention.accept(destination);
    retention.accept(source);
    ArchiveException outcome = retention.finish(List.of());
    assertEquals(source.primaryFailure(), outcome.primaryFailure());
    assertEquals(
        List.of(generated.primaryFailure(), destination.primaryFailure()),
        outcome.secondaryFailures());
  }

  /** Asserts the primary failure's and its diagnostic's two path slots. */
  private static void assertSlots(
      ArchiveException failure, Optional<Path> archive, Optional<Path> artifact) {
    for (DiagnosticLocation location :
        List.of(
            failure.primaryFailure().location().orElseThrow(),
            failure.diagnostics().getFirst().location())) {
      assertEquals(archive == null ? Optional.empty() : archive, location.archive());
      assertEquals(artifact == null ? Optional.empty() : artifact, location.artifact());
    }
  }
}
