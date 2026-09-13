package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.Operation;
import io.github.evildarkarchon.jbsa.TargetPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises extraction preflight and identity rechecks on the qualified Windows filesystem. */
@EnabledOnOs(OS.WINDOWS)
final class ExtractionPathsTest {
  @TempDir Path directory;

  /** Unsafe names fail as policy before any missing root or valid preceding entry is created. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "../escape",
        "/absolute",
        "C:stream",
        "a//b",
        "a/./b",
        "a/../b",
        "a. ",
        "a.",
        "a/CON.txt",
        "prn",
        "aux.bin",
        "NUL",
        "COM1",
        "lpt9.txt",
        "COM\u00b9",
        "LPT\u00b2.ext",
        "com\u00b3",
        "a?b",
        "a*b",
        "a|b",
        "a<b",
        "a>b",
        "a\"b",
        "a\u0001b",
        "a\u001fb",
        "a\u0000b",
        "\ud800"
      })
  void rejectsUnsafeNamesBeforeDestinationEffects(String name) throws Exception {
    Path root = directory.resolve("missing");
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> preflight(root, List.of("valid.bin", name), TargetPolicy.FAIL));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertFalse(Files.exists(root));
    try (var contents = Files.list(directory)) {
      assertEquals(0, contents.count());
    }
  }

  /** Distinct safe names retain display spelling and produce a detached ordered target list. */
  @Test
  void plansMissingRootWithoutCreatingIt() throws Exception {
    Path root = directory.resolve("new-root");
    try (ExtractionPaths plan =
        preflight(root, List.of("Textures/Mixed.DDS", "COM10.bin"), TargetPolicy.FAIL)) {
      assertFalse(plan.existingRoot());
      assertEquals(root, plan.root());
      assertEquals(
          List.of(root.resolve("Textures/Mixed.DDS"), root.resolve("COM10.bin")), plan.targets());
      plan.recheckRoot();
      assertFalse(Files.exists(root));
    }
  }

  /**
   * Both archive identity duplicates and Windows Unicode case collisions reject the complete set.
   */
  @Test
  void rejectsNameCollisionsAndFileDirectoryConflicts() throws Exception {
    for (List<String> names :
        List.of(
            List.of("A/x", "a\\X"),
            List.of("\u00c4.bin", "\u00e4.bin"),
            List.of("parent", "PARENT/file"))) {
      ArchiveException failure =
          assertThrows(
              ArchiveException.class, () -> preflight(directory, names, TargetPolicy.FAIL));
      assertEquals(FailureKind.POLICY, failure.kind());
    }
  }

  /** Different host spellings cannot schedule the same existing file identity twice. */
  @Test
  void rejectsExistingTargetIdentityAliases() throws Exception {
    Path original = Files.writeString(directory.resolve("original.bin"), "unchanged");
    Files.createLink(directory.resolve("alias.bin"), original);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> {
              try (ExtractionPaths plan =
                  preflight(
                      directory, List.of("original.bin", "alias.bin"), TargetPolicy.REPLACE)) {
                plan.recheckRoot();
              }
            });
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals("unchanged", Files.readString(original));
  }

  /**
   * Missing outputs must collide before publication even through distinct existing-directory
   * aliases.
   */
  @Test
  void rejectsMissingTargetsAndPrefixConflictsUnderDirectoryAliases() throws Exception {
    Path first = Files.createDirectory(directory.resolve("first"));
    Path second = Files.createDirectory(directory.resolve("second"));
    WindowsPathIdentity.Snapshot common = WindowsPathIdentity.inspect(first);
    for (List<String> names :
        List.of(
            List.of("first/missing/entry.bin", "second/MISSING/ENTRY.bin"),
            List.of("first/missing", "second/missing/entry.bin"),
            List.of("first/missing/entry.bin", "second/missing"))) {
      ArchiveException failure =
          assertThrows(
              ArchiveException.class,
              () -> {
                try (ExtractionPaths plan =
                    ExtractionPaths.preflight(
                        directory,
                        names,
                        TargetPolicy.FAIL,
                        IoContext.of(directory, Operation.EXTRACT),
                        WindowsPathIdentity.Pin::close,
                        path -> path.equals(second) ? common : WindowsPathIdentity.inspect(path))) {
                  plan.recheckRoot();
                }
              });
      assertEquals(FailureKind.POLICY, failure.kind());
      assertEquals(
          "extraction.name-collision",
          failure.primaryFailure().diagnosticIdentifier().orElseThrow());
    }
    assertFalse(Files.exists(first.resolve("missing")));
    assertFalse(Files.exists(second.resolve("missing")));
  }

  /** Sharing an existing parent through aliases is safe when the future output names diverge. */
  @Test
  void acceptsDivergingMissingOutputsUnderDirectoryAliases() throws Exception {
    Path first = Files.createDirectory(directory.resolve("first"));
    Path second = Files.createDirectory(directory.resolve("second"));
    try (ExtractionPaths plan =
        ExtractionPaths.preflight(
            directory,
            List.of("first/missing/one.bin", "second/missing/two.bin"),
            TargetPolicy.FAIL,
            IoContext.of(directory, Operation.EXTRACT),
            WindowsPathIdentity.Pin::close,
            path ->
                WindowsPathIdentity.inspect(
                    path.startsWith(second) ? first.resolve(second.relativize(path)) : path))) {
      assertEquals(2, plan.targets().size());
      plan.recheckRoot();
      Path created = Files.createDirectory(first.resolve("missing"));
      plan.createdDirectory(created);
      plan.recheck(second.resolve("missing/two.bin"));
    }
  }

  /**
   * Qualifies absent-target collision checks against real NTFS short-directory names when enabled.
   */
  @Test
  void rejectsMissingOutputUnderRealShortDirectoryAlias() throws Exception {
    Path longParent =
        Files.createDirectory(directory.resolve("Long directory for archive extraction"));
    Process process =
        new ProcessBuilder("cmd", "/c", "for %I in (\"" + longParent + "\") do @echo %~sI")
            .redirectErrorStream(true)
            .start();
    String output =
        new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            .strip();
    assertEquals(0, process.waitFor(), output);
    String shortName = Path.of(output).getFileName().toString();
    org.junit.jupiter.api.Assumptions.assumeFalse(
        shortName.equalsIgnoreCase(longParent.getFileName().toString()),
        "This volume does not generate short directory aliases");
    assertEquals(
        WindowsPathIdentity.inspect(longParent).identity(),
        WindowsPathIdentity.inspect(directory.resolve(shortName)).identity());
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> {
              try (ExtractionPaths plan =
                  preflight(
                      directory,
                      List.of(
                          longParent.getFileName() + "/new/entry.bin",
                          shortName + "/NEW/ENTRY.bin"),
                      TargetPolicy.FAIL)) {
                plan.recheckRoot();
              }
            });
    assertEquals(FailureKind.POLICY, failure.kind());
    assertFalse(Files.exists(longParent.resolve("new")));
  }

  /**
   * Cleanup failures stay structured, retaining policy as primary without duplicate suppression.
   */
  @Test
  void preservesPreflightPinCleanupAsSecondary() throws Exception {
    Files.writeString(directory.resolve("existing.bin"), "untouched");
    var cleanup = new java.io.IOException("injected close failure after release");
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                ExtractionPaths.preflight(
                    directory,
                    List.of("existing.bin"),
                    TargetPolicy.FAIL,
                    IoContext.of(directory, Operation.EXTRACT),
                    pin -> {
                      pin.close();
                      throw cleanup;
                    }));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(1, failure.secondaryFailures().size());
    assertEquals(
        io.github.evildarkarchon.jbsa.OperationPhase.CLEANUP,
        failure.secondaryFailures().getFirst().phase());
    assertSame(cleanup, failure.secondaryFailures().getFirst().cause().orElseThrow());
    assertEquals(0, failure.getSuppressed().length);
  }

  /** Existing predecessors are admitted only by REPLACE and their later replacement is detected. */
  @Test
  void rechecksTargetIdentityAndHonorsTargetPolicy() throws Exception {
    Path target = Files.writeString(directory.resolve("entry.bin"), "old");
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> preflight(directory, List.of("entry.bin"), TargetPolicy.FAIL));
    assertEquals(FailureKind.POLICY, failure.kind());
    try (ExtractionPaths plan = preflight(directory, List.of("entry.bin"), TargetPolicy.REPLACE)) {
      plan.recheck(target);
      Files.move(target, directory.resolve("moved.bin"));
      Files.writeString(target, "new");
      ArchiveException changed = assertThrows(ArchiveException.class, () -> plan.recheck(target));
      assertEquals(FailureKind.DESTINATION, changed.kind());
    }
  }

  /**
   * A root handle prevents rename until closed, without locking descendant files against writes.
   */
  @Test
  void pinsRootAndReleasesHandle() throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    try (ExtractionPaths plan = preflight(root, List.of("entry.bin"), TargetPolicy.FAIL)) {
      assertTrue(plan.existingRoot());
      assertThrows(java.io.IOException.class, () -> Files.move(root, directory.resolve("renamed")));
      Files.writeString(root.resolve("unrelated"), "allowed");
      plan.recheck(root.resolve("entry.bin"));
    }
    Files.move(root, directory.resolve("renamed"));
  }

  /** Parent identities are checked again even if a replacement has the same pathname. */
  @Test
  void detectsChangedAncestorAndUnexpectedTarget() throws Exception {
    Path ancestor = Files.createDirectory(directory.resolve("folder"));
    try (ExtractionPaths plan =
        preflight(directory, List.of("folder/entry.bin"), TargetPolicy.FAIL)) {
      Files.move(ancestor, directory.resolve("previous-folder"));
      Files.createDirectory(ancestor);
      ArchiveException changed =
          assertThrows(
              ArchiveException.class, () -> plan.recheckParents(ancestor.resolve("entry.bin")));
      assertEquals(FailureKind.DESTINATION, changed.kind());
    }
    try (ExtractionPaths plan =
        preflight(directory, List.of("unexpected.bin"), TargetPolicy.FAIL)) {
      Files.writeString(directory.resolve("unexpected.bin"), "racer");
      assertEquals(
          FailureKind.DESTINATION,
          assertThrows(
                  ArchiveException.class, () -> plan.recheck(directory.resolve("unexpected.bin")))
              .kind());
    }
  }

  /**
   * Only explicitly recorded operation-created directories advance the expected destination state.
   */
  @Test
  void acceptsRecordedDirectoriesAndRechecksParentsAfterBackup() throws Exception {
    Path predecessor = Files.writeString(directory.resolve("old.bin"), "old");
    try (ExtractionPaths plan =
        preflight(directory, List.of("new/deep/file.bin", "old.bin"), TargetPolicy.REPLACE)) {
      Path first = Files.createDirectory(directory.resolve("new"));
      plan.createdDirectory(first);
      Path second = Files.createDirectory(first.resolve("deep"));
      plan.createdDirectory(second);
      plan.recheck(second.resolve("file.bin"));
      plan.recheck(predecessor);
      Files.move(predecessor, directory.resolve("backup.bin"));
      plan.recheckParents(predecessor);
    }
  }

  /**
   * A junction is classified without traversing it, including when a normal directory is swapped.
   */
  @Test
  void rejectsJunctionsDuringPreflightAndRecheck() throws Exception {
    Path outside = Files.createDirectory(directory.resolve("outside"));
    Path root = Files.createDirectory(directory.resolve("root"));
    Path link = root.resolve("folder");
    createJunction(link, outside);
    try {
      assertEquals(
          FailureKind.POLICY,
          assertThrows(
                  ArchiveException.class,
                  () -> preflight(root, List.of("folder/file.bin"), TargetPolicy.FAIL))
              .kind());
    } finally {
      Files.delete(link);
    }
    Files.createDirectory(link);
    try (ExtractionPaths plan = preflight(root, List.of("folder/file.bin"), TargetPolicy.FAIL)) {
      Files.delete(link);
      createJunction(link, outside);
      assertEquals(
          FailureKind.DESTINATION,
          assertThrows(ArchiveException.class, () -> plan.recheck(root.resolve("folder/file.bin")))
              .kind());
    } finally {
      Files.delete(link);
    }
    assertFalse(Files.exists(outside.resolve("file.bin")));
  }

  /** Builds the plan using the same semantic location as the operation coordinator. */
  private static ExtractionPaths preflight(Path root, List<String> names, TargetPolicy policy)
      throws Exception {
    return ExtractionPaths.preflight(root, names, policy, IoContext.of(root, Operation.EXTRACT));
  }

  /** Creates a directory junction without requiring the Windows symbolic-link privilege. */
  private static void createJunction(Path link, Path target) throws Exception {
    Process process =
        new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start();
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
