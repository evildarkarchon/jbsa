package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec;
import io.github.evildarkarchon.jbsa.internal.io.*;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Layout;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Planned;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * The part-staging steps every Emission shares once its parts are formed: each part's layout and
 * metadata charge, the readback-wrapped writer publication stages it through, and the report's
 * description of a staged part.
 */
final class PartStaging {
  private PartStaging() {}

  /**
   * Admits every part's layout in part order and charges its metadata against the budget, before
   * any writer is created.
   *
   * @return the layouts, one per part in the same order
   * @throws ArchiveException the first part whose layout cannot be encoded, or the metadata limit
   */
  static <K> List<Layout> layouts(Admitted<K> admitted, List<List<Planned<K>>> parts, Run run)
      throws ArchiveException {
    List<Layout> layouts = new ArrayList<>(parts.size());
    for (var part : parts) {
      Layout layout = admitted.layout(part, run.context());
      run.budget().metadata(layout.metadataBytes());
      layouts.add(layout);
    }
    return layouts;
  }

  /**
   * Wraps one part's staging with the adapter's readback declaration, which publication runs once
   * the staged handle has closed and before any member of the set is published.
   *
   * @param readback the part's declaration, or null when the family does not read its output back
   */
  static PublicationTransaction.Writer writer(
      PublicationTransaction.Writer body, Admitted.Readback readback, Run run) {
    return new PublicationTransaction.Writer() {
      @Override
      public void write(PublicationTransaction.StagedFile output) throws IOException {
        body.write(output);
      }

      @Override
      public void validate(Path staged) throws IOException {
        if (readback != null) readBack(staged, readback, run);
      }
    };
  }

  /**
   * Reopens one staged part, requires a CONFORMING assessment, and decodes every entry through one
   * bounded window. The reader owns its own budget, so its peak is also admitted against the
   * still-live pack budget: readback must not silently exceed the operation's capacity. The part is
   * the operation's own staged output, so it opens as a destination and a read fault is DESTINATION
   * (ADR-0006).
   *
   * @throws ArchiveException INTERNAL {@link Admitted.Readback#nonconformingId()} for a part that
   *     reopens as nonconforming, or the reader's own structured failure
   */
  private static void readBack(Path staged, Admitted.Readback readback, Run run)
      throws IOException {
    PackRequest request = run.request();
    // The adapter declares which codecs its reader decodes; their cost comes from the codecs this
    // request admitted, so readback charges the same rule, under the same provider, as the read.
    AdmittedCodec.Cost codec = run.codecs().readbackCost(readback);
    try (var credits =
            run.budget()
                .reserve(
                    readback.heapBytes() + codec.heapBytes(),
                    readback.nativeBytes() + codec.nativeBytes(),
                    readback.handles(),
                    0);
        var archive =
            ArchiveReaders.open(
                IoContext.destination(staged, Operation.PACK),
                new OpenOptions(
                    request.compatibilityProfile(), request.resourceLimits(), request.ddsTarget()),
                request.diagnosticPolicy())) {
      if (archive.inspection().assessment().disposition() != ArchiveDisposition.CONFORMING)
        throw run.context().failure(FailureKind.INTERNAL, readback.nonconformingId(), null);
      ByteBuffer window = ByteBuffer.allocate(PayloadWindows.WINDOW_BYTES);
      for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
        try (var content = archive.entry(ordinal).openContent()) {
          do {
            run.operation().checkpoint(OperationPhase.PROCESSING, OptionalLong.of(ordinal));
            window.clear();
          } while (content.read(window) >= 0);
        }
      }
    }
  }

  /** Describes one staged part for the report. */
  static OperationReport.ArchivePart archivePart(
      PackRequest request,
      int number,
      PublicationTransaction.StagedFile output,
      List<? extends Planned<?>> part) {
    return new OperationReport.ArchivePart(
        PublicationTransaction.splitPath(
            request.destination().toAbsolutePath().normalize(), number),
        output.size(),
        part.size());
  }
}
