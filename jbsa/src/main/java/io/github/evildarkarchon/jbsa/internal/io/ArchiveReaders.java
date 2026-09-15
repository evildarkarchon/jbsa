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
          if (detection
              .family()
              .filter(
                  family ->
                      family == ArchiveFamily.TES4_BSA
                          || family == ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA
                          || family == ArchiveFamily.SSE_BSA)
              .isPresent()) return BsaReader.load(builder, path, options, operation, policy);
          boolean generalBa2 =
              detection.ba2Subtype().filter(Ba2Subtype.GNRL::equals).isPresent()
                  && detection.wireVersion().isPresent()
                  && (detection.wireVersion().orElseThrow().value() == 1
                      || detection.wireVersion().orElseThrow().value() == 2
                      || detection.wireVersion().orElseThrow().value() == 3);
          boolean qualifiedFallback =
              detection.status() == DetectionStatus.UNSUPPORTED_VARIANT
                  && options.compatibilityProfile().isPresent()
                  && detection.wireVersion().orElseThrow().value() == 3;
          if (generalBa2
              && (detection
                      .family()
                      .filter(
                          family ->
                              family == ArchiveFamily.FO4_GENERAL_BA2
                                  || family == ArchiveFamily.STARFIELD_GENERAL_BA2)
                      .isPresent()
                  || qualifiedFallback))
            return io.github.evildarkarchon.jbsa.internal.ba2.Ba2Reader.load(
                builder, path, options, operation, policy);
          IoContext context = IoContext.of(path, operation);
          if (detection.family().filter(ArchiveFamily.FO4_DDS_BA2::equals).isPresent()
              && detection.wireVersion().orElseThrow().value() == 1)
            return io.github.evildarkarchon.jbsa.internal.ba2.DdsBa2Reader.load(
                builder, path, options, operation, policy);
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
