package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pack's source-side failures are located at the source they read (ADR-0006): a source in the
 * location's {@code archive} field, and a generated source, which has no path, by its entry ordinal
 * and name alone. Neither is ever located at the pack's destination.
 */
class PackSourceLocationTest {
  private static final ArchiveEncoding GENERAL =
      new ArchiveEncoding(
          Optional.of(new WireVersion(1)), Optional.of(Ba2Subtype.GNRL), OptionalLong.empty());

  /** A generated source's read fault carries its ordinal and entry name, with no path at all. */
  @Test
  void generatedSourceFailureIsLocatedByOrdinalAndEntryName(@TempDir Path directory) {
    Path destination = directory.resolve("generated.ba2");
    var request =
        PackRequest.standard(
            destination,
            ArchiveFamily.FO4_GENERAL_BA2,
            GENERAL,
            List.of(
                new PackSource.GeneratedEntry(
                    "Data/Generated.bin",
                    4,
                    () -> {
                      throw new IOException("factory");
                    })),
            Optional.empty());

    var failure =
        assertThrows(
            ArchiveException.class,
            () -> BethesdaArchives.standard().pack(request, OperationControl.standard()));

    assertEquals(FailureKind.SOURCE, failure.kind());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertEquals(OptionalLong.of(0), failure.primaryFailure().ordinal());
    var location = failure.primaryFailure().location().orElseThrow();
    assertEquals(Optional.empty(), location.archive());
    assertEquals(Optional.empty(), location.artifact());
    assertEquals(OptionalLong.of(0), location.entryOrdinal());
    assertEquals(Optional.of("Data/Generated.bin"), location.entryName());
    assertFalse(Files.exists(destination));
  }

  /**
   * A raw fault while planning walks a detected directory, here a subdirectory it may not list, is
   * structured where it happens: SOURCE at the walked root, never an unstructured fault for the
   * operation template.
   */
  @Test
  void treeWalkFaultIsSourceAtTheWalkedRoot(@TempDir Path directory) throws Exception {
    Path root = directory.resolve("Data");
    Path locked = root.resolve("locked");
    Files.createDirectories(locked);
    Files.write(root.resolve("visible.bin"), new byte[] {1});
    Files.write(locked.resolve("hidden.bin"), new byte[] {2});
    Runnable restore = denyListing(locked);
    try {
      boolean denied;
      try (var listing = Files.newDirectoryStream(locked)) {
        denied = false;
      } catch (IOException expected) {
        denied = true;
      }
      // A superuser lists anything, so the fault cannot be provoked there.
      assumeTrue(denied, "The host lists a directory this test denied");
      var request =
          PackRequest.standard(
              directory.resolve("walked.ba2"),
              ArchiveFamily.FO4_GENERAL_BA2,
              GENERAL,
              List.of(new PackSource.DetectedPath(root)),
              Optional.empty());

      var failure =
          assertThrows(
              ArchiveException.class,
              () -> BethesdaArchives.standard().pack(request, OperationControl.standard()));

      assertEquals(FailureKind.SOURCE, failure.kind());
      assertEquals(
          Optional.of("operation.source-io"), failure.primaryFailure().diagnosticIdentifier());
      assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
      var location = failure.primaryFailure().location().orElseThrow();
      assertEquals(Optional.of(root.toAbsolutePath().normalize()), location.archive());
      assertEquals(Optional.empty(), location.artifact());
    } finally {
      restore.run();
    }
  }

  /**
   * Denies listing one directory until the returned action runs: an ACL deny entry for the current
   * user on Windows, or no permissions at all on a POSIX host.
   */
  private static Runnable denyListing(Path directory) throws IOException {
    var posix =
        Files.getFileStore(directory).supportsFileAttributeView("posix")
            ? Files.getPosixFilePermissions(directory)
            : null;
    if (posix != null) {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("---------"));
      return () -> {
        try {
          Files.setPosixFilePermissions(directory, posix);
        } catch (IOException failure) {
          throw new java.io.UncheckedIOException(failure);
        }
      };
    }
    AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
    UserPrincipal user =
        directory
            .getFileSystem()
            .getUserPrincipalLookupService()
            .lookupPrincipalByName(System.getProperty("user.name"));
    List<AclEntry> original = view.getAcl();
    List<AclEntry> denied = new ArrayList<>();
    denied.add(
        AclEntry.newBuilder()
            .setType(AclEntryType.DENY)
            .setPrincipal(user)
            .setPermissions(Set.of(AclEntryPermission.LIST_DIRECTORY))
            .build());
    denied.addAll(original);
    view.setAcl(denied);
    return () -> {
      try {
        view.setAcl(original);
      } catch (IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      }
    };
  }
}
