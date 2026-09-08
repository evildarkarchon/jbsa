package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Public query operations distinguish source and recognition failures before parser dispatch. */
final class QueryValidationTest {
  @TempDir Path directory;

  /** Unavailable family parsers cannot mask the fact that the requested input does not exist. */
  @ParameterizedTest
  @ValueSource(strings = {"inspect", "inspect-options", "open"})
  void sourceFailurePrecedesFamilyCapability(String query) {
    ArchiveException failure = invoke(query, directory.resolve("missing.bsa"));
    assertEquals(FailureKind.SOURCE, failure.kind());
    assertInstanceOf(NoSuchFileException.class, failure.getCause());
    assertEquals(
        "operation.source-io", failure.primaryFailure().diagnosticIdentifier().orElseThrow());
    assertTrue(failure.assessment().isEmpty());
    assertTrue(failure.artifacts().isEmpty());
  }

  /** Bounded recognition distinguishes invalid and unsupported inputs without a structure claim. */
  @ParameterizedTest
  @ValueSource(strings = {"inspect", "inspect-options", "open"})
  void recognitionPrecedesFamilyCapability(String query) throws Exception {
    assertRecognition(query, new byte[0], FailureKind.FORMAT, "archive.unrecognized");
    assertRecognition(
        query, new byte[] {66, 83}, FailureKind.FORMAT, "archive.incomplete-selector");
    assertRecognition(
        query,
        new byte[] {66, 83, 65, 0, 0x66, 0, 0, 0},
        FailureKind.UNSUPPORTED,
        "archive.unsupported-variant");
    assertRecognition(
        query,
        new byte[] {66, 83, 65, 0, 0x68, 0, 0, 0},
        FailureKind.CAPABILITY,
        "baseline.archive-operation-unavailable");
  }

  /**
   * Compares public query evidence against literal selector fixtures and verifies handle release.
   */
  private void assertRecognition(String query, byte[] bytes, FailureKind kind, String identifier)
      throws Exception {
    Path path = Files.write(directory.resolve("selector.bin"), bytes);
    ArchiveException failure = invoke(query, path);
    assertEquals(kind, failure.kind());
    assertEquals(identifier, failure.primaryFailure().diagnosticIdentifier().orElseThrow());
    assertTrue(failure.assessment().isEmpty());
    assertTrue(failure.artifacts().isEmpty());
    Files.delete(path);
  }

  /** Invokes each accepted public query overload and checks operation ownership of its failure. */
  private static ArchiveException invoke(String query, Path path) {
    BethesdaArchives archives = BethesdaArchives.standard();
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> {
              switch (query) {
                case "inspect" -> archives.inspect(path);
                case "inspect-options" -> archives.inspect(path, OpenOptions.standard());
                case "open" -> archives.open(path, OpenOptions.standard());
                default -> throw new IllegalArgumentException("Unknown test query");
              }
            });
    Operation operation = query.equals("open") ? Operation.OPEN : Operation.INSPECT;
    assertTrue(failure.diagnostics().stream().allMatch(d -> d.operation() == operation));
    return failure;
  }
}
