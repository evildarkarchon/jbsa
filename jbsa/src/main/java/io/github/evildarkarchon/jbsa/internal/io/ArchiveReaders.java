package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.ba2.Ba2Reader;
import io.github.evildarkarchon.jbsa.internal.ba2.DdsBa2Reader;
import io.github.evildarkarchon.jbsa.internal.bsa.BsaReader;
import io.github.evildarkarchon.jbsa.internal.tes3.Tes3Reader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

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
        session -> {
          // The same recognizer and prefix length as BethesdaArchives.detect. Every reader reports
          // this value unchanged, so detect and inspect agree on the evidence (JBSA-DET-001).
          ArchiveDetection detection =
              Detection.recognize(
                  session.readSelectors((int) Math.min(36, session.size())).array());
          Optional<CompatibilityProfile> profile = options.compatibilityProfile();
          return switch (detection.family().orElse(null)) {
            case TES3_BSA -> session.read(detection, profile, Tes3Reader::walk);
            case TES4_BSA, FO3_FNV_SKYRIM_LE_BSA, SSE_BSA ->
                session.read(detection, profile, BsaReader::walk);
            case FO4_GENERAL_BA2, STARFIELD_GENERAL_BA2 ->
                session.read(detection, profile, s -> Ba2Reader.walk(s, profile));
            case FO4_DDS_BA2, STARFIELD_DDS_BA2 ->
                session.read(detection, profile, s -> DdsBa2Reader.walk(s, options));
            case null -> starfieldFallback(session, detection, options);
          };
        });
  }

  /**
   * Reads a BA2 that detection could not assign to a family, or reports why it cannot be read.
   *
   * <p>A Starfield version 3 BA2 whose compression method is not raw LZ4 is an unsupported variant.
   * Only the qualified Compatibility Profile decodes it, with the reader's zlib fallback; detection
   * keeps reporting it as an unsupported variant without a family.
   */
  private static ArchiveInspection starfieldFallback(
      IndexSession session, ArchiveDetection detection, OpenOptions options) throws IOException {
    Optional<CompatibilityProfile> profile = options.compatibilityProfile();
    // An unsupported variant always carries the wire version that made it unsupported.
    boolean qualified =
        detection.status() == DetectionStatus.UNSUPPORTED_VARIANT
            && profile.isPresent()
            && detection.wireVersion().orElseThrow().value() == 3;
    Optional<Ba2Subtype> subtype = detection.ba2Subtype();
    if (qualified && subtype.filter(Ba2Subtype.GNRL::equals).isPresent())
      return session.read(detection, profile, s -> Ba2Reader.walk(s, profile));
    if (qualified && subtype.filter(Ba2Subtype.DX10::equals).isPresent())
      return session.read(detection, profile, s -> DdsBa2Reader.walk(s, options));
    IoContext context = session.context();
    throw switch (detection.status()) {
      case UNRECOGNIZED -> context.failure(FailureKind.FORMAT, "archive.unrecognized", null);
      case INDETERMINATE ->
          context.failure(FailureKind.FORMAT, "archive.incomplete-selector", null);
      case UNSUPPORTED_VARIANT ->
          context.failure(FailureKind.UNSUPPORTED, "archive.unsupported-variant", null);
      // Every recognized family is dispatched above, so this arm is a guard for a future family.
      case SUPPORTED_FAMILY ->
          context.failure(FailureKind.CAPABILITY, "baseline.archive-operation-unavailable", null);
    };
  }
}
