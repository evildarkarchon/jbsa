package io.github.evildarkarchon.jbsa.internal.tes3;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;

/** Stored TES3 extraction through shared validated-path staging and publication semantics. */
public final class Tes3Extractor {
  private Tes3Extractor() {}

  /**
   * Validates the complete selected plan before staging and settles the source before final commit.
   */
  public static OperationReport extract(ExtractRequest request, OperationControl control)
      throws ArchiveException {
    OperationSession operation =
        new OperationSession(
            Operation.EXTRACT, request.resourceLimits(), request.diagnosticPolicy(), control);
    IoContext context = IoContext.of(request.source(), Operation.EXTRACT);
    OpenArchive archive = null;
    boolean publicationOwnsSession = false;
    List<Artifact> artifacts = List.of();
    try {
      operation.begin();
      archive =
          Tes3Reader.open(
              request.source(),
              request.openOptions(),
              Operation.EXTRACT,
              request.diagnosticPolicy());
      operation.assessment(archive.inspection().assessment());
      OpenArchive source = archive;
      Set<Long> requested =
          request.entries() instanceof EntrySelection.Ordinals selection
              ? new HashSet<>(selection.ordinals())
              : null;
      if (requested != null
          && requested.stream().anyMatch(ordinal -> ordinal >= source.entryCount()))
        throw context.failure(FailureKind.POLICY, "extract.invalid-entry-ordinal", null);
      List<ArchiveEntry> selected = new ArrayList<>();
      long decodedBytes = 0;
      for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
        if (requested != null && !requested.contains(ordinal)) continue;
        operation.checkpoint(OperationPhase.PREFLIGHT, OptionalLong.of(ordinal));
        ArchiveEntry entry = archive.entry(ordinal);
        if (entry.metadata().normalizedNameIdentity().isEmpty())
          throw new IoContext(
                  request.source(),
                  Operation.EXTRACT,
                  OperationPhase.PREFLIGHT,
                  OptionalLong.of(ordinal))
              .failure(FailureKind.POLICY, "extract.ineligible-name", null);
        if (entry.metadata().decodedSize()
            > request.resourceLimits().maxDecodedBytes() - decodedBytes)
          throw context.limit(
              "maxDecodedBytes",
              request.resourceLimits().maxDecodedBytes(),
              BigInteger.valueOf(decodedBytes)
                  .add(BigInteger.valueOf(entry.metadata().decodedSize()))
                  .toString());
        decodedBytes += entry.metadata().decodedSize();
        selected.add(entry);
      }
      Set<Long> validated = new TreeSet<>();
      List<PublicationTransaction.Entry> entries = new ArrayList<>();
      for (int index = 0; index < selected.size(); index++) {
        ArchiveEntry entry = selected.get(index);
        boolean last = index == selected.size() - 1;
        entries.add(
            new PublicationTransaction.Entry(
                entry.metadata().displayName(),
                output -> {
                  transfer(entry, output, operation, source.inspection().assessment(), validated);
                  // Source cleanup must succeed before the last selected file can become visible.
                  if (last) source.close();
                }));
      }
      if (selected.isEmpty()) source.close();
      publicationOwnsSession = true;
      artifacts =
          PublicationTransaction.extract(
                  request.destination(),
                  entries,
                  request.targetPolicy(),
                  request.resourceLimits(),
                  operation)
              .artifacts();
    } catch (ArchiveException failure) {
      if (publicationOwnsSession) artifacts = failure.artifacts();
      else operation.accept(failure);
    } catch (IOException failure) {
      operation.accept(context.failure(FailureKind.SOURCE, "operation.source-io", failure));
    } finally {
      if (archive != null) {
        try {
          archive.close();
        } catch (IOException failure) {
          operation.accept(
              failure instanceof ArchiveException checked
                  ? checked
                  : new IoContext(
                          request.source(),
                          Operation.EXTRACT,
                          OperationPhase.CLEANUP,
                          OptionalLong.empty())
                      .failure(FailureKind.SOURCE, "operation.source-io", failure));
        }
      }
      if (!publicationOwnsSession) {
        operation.cleanup();
        operation.cleaned(0);
      }
    }
    return operation.finish(artifacts);
  }

  /**
   * Copies one stored span in bounded windows and records EOF evidence under extraction ownership.
   */
  private static void transfer(
      ArchiveEntry entry,
      PublicationTransaction.StagedFile output,
      OperationSession operation,
      ArchiveAssessment structure,
      Set<Long> validated)
      throws IOException {
    try (EntryContent content = entry.openContent()) {
      ByteBuffer window = ByteBuffer.allocate(65536);
      long position = 0;
      while (true) {
        operation.checkpoint(
            OperationPhase.PROCESSING, OptionalLong.of(entry.metadata().ordinal()));
        int count = content.read(window);
        if (count < 0) break;
        window.flip();
        output.write(position, window);
        position += count;
        window.clear();
      }
      validated.add(entry.metadata().ordinal());
      operation.latestAssessment(
          new ArchiveAssessment(
              structure.disposition(),
              new ValidationExtent.Payloads(validated),
              structure.diagnostics()));
    }
  }
}
