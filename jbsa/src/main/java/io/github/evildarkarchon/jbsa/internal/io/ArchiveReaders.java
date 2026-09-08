package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.bsa.BsaReader;
import io.github.evildarkarchon.jbsa.internal.tes3.Tes3Reader;
import java.io.IOException;
import java.nio.file.Path;

/** Dispatches every query and mutation through one pinned source and one warning policy. */
public final class ArchiveReaders {
  private ArchiveReaders() {}

  /** Opens an owned family index, preserving the originating mutation in all diagnostics. */
  public static OpenArchive open(
      Path path, OpenOptions options, Operation operation, DiagnosticPolicy policy)
      throws IOException {
    return OwnedArchive.load(
        path,
        options.resourceLimits(),
        operation,
        policy,
        builder -> {
          ArchiveDetection detection =
              Detection.recognize(
                  builder.readSelectors((int) Math.min(36, builder.size())).array());
          if (detection.family().filter(ArchiveFamily.TES3_BSA::equals).isPresent())
            return Tes3Reader.load(builder, path, options, operation, policy);
          if (detection.family().filter(ArchiveFamily.TES4_BSA::equals).isPresent())
            return BsaReader.load(builder, path, options, operation, policy);
          IoContext context = IoContext.of(path, operation);
          throw switch (detection.status()) {
            case UNRECOGNIZED -> context.failure(FailureKind.FORMAT, "archive.unrecognized", null);
            case INDETERMINATE ->
                context.failure(FailureKind.FORMAT, "archive.incomplete-selector", null);
            case UNSUPPORTED_VARIANT ->
                context.failure(FailureKind.UNSUPPORTED, "archive.unsupported-variant", null);
            case SUPPORTED_FAMILY ->
                context.failure(
                    FailureKind.CAPABILITY, "baseline.archive-operation-unavailable", null);
          };
        });
  }
}
