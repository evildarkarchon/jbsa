package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;

/** Selected-entry extraction through shared validated-path staging and publication semantics. */
public final class ArchiveExtractor {
  private ArchiveExtractor() {}

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
      WorkerSelection.UpTo selectedWorkers = WorkerLimits.snapshot(request.workerSelection());
      archive =
          ArchiveReaders.open(
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
      boolean sequential = selected.isEmpty() || selectedWorkers.workers() == 1;
      // The opened archive's native budget admits one decoder. Stored entries still run freely.
      Semaphore compressedDecoder = new Semaphore(1, true);
      for (int index = 0; index < selected.size(); index++) {
        ArchiveEntry entry = selected.get(index);
        boolean last = index == selected.size() - 1;
        if (sequential) {
          entries.add(
              new PublicationTransaction.Entry(
                  entry.metadata().displayName(),
                  output -> {
                    transfer(entry, output, operation, source.inspection().assessment(), validated);
                    // Source cleanup must succeed before the last selected file can become visible.
                    if (last) source.close();
                  }));
        } else {
          IoContext entryContext =
              new IoContext(
                  request.source(),
                  Operation.EXTRACT,
                  OperationPhase.PROCESSING,
                  OptionalLong.of(entry.metadata().ordinal()));
          entries.add(
              new PublicationTransaction.Entry(
                  entry.metadata().displayName(),
                  output -> transferParallel(entry, output, compressedDecoder, entryContext),
                  entry.metadata().decodedSize(),
                  entry.metadata().ordinal()));
        }
      }
      if (selected.isEmpty()) source.close();
      publicationOwnsSession = true;
      artifacts =
          (sequential
                  ? PublicationTransaction.extract(
                      request.destination(),
                      entries,
                      request.targetPolicy(),
                      request.resourceLimits(),
                      operation)
                  : PublicationTransaction.extractParallel(
                      request.destination(),
                      entries,
                      request.targetPolicy(),
                      request.resourceLimits(),
                      operation,
                      selectedWorkers,
                      index -> {
                        validated.add(selected.get(index).metadata().ordinal());
                        operation.latestAssessment(
                            new ArchiveAssessment(
                                source.inspection().assessment().disposition(),
                                new ValidationExtent.Payloads(validated),
                                source.inspection().assessment().diagnostics()));
                      },
                      () -> {
                        try {
                          source.close();
                        } catch (ArchiveException failure) {
                          throw failure;
                        } catch (IOException failure) {
                          throw new IoContext(
                                  request.source(),
                                  Operation.EXTRACT,
                                  OperationPhase.CLEANUP,
                                  OptionalLong.empty())
                              .failure(FailureKind.SOURCE, "operation.source-io", failure);
                        }
                      }))
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
   * Copies one decoded payload in bounded windows and records EOF evidence under extraction
   * ownership.
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

  /** Copies one payload on a worker without touching coordinator-owned progress or assessment. */
  private static void transferParallel(
      ArchiveEntry entry,
      PublicationTransaction.StagedFile output,
      Semaphore compressedDecoder,
      IoContext sourceContext)
      throws IOException {
    boolean compressed = requiresDecoder(entry.metadata());
    if (compressed) {
      // The source archive admits one native decoder, so waiting workers poll the stop state.
      while (!compressedDecoder.tryAcquire()) {
        output.checkpointWork();
        LockSupport.parkNanos(1_000_000L);
      }
    }
    try {
      EntryContent content;
      try {
        content = entry.openContent();
      } catch (ArchiveException failure) {
        throw failure;
      } catch (IOException failure) {
        throw sourceContext.failure(FailureKind.SOURCE, "operation.source-io", failure);
      }
      try (content) {
        ByteBuffer window = ByteBuffer.allocate(65536);
        long position = 0;
        while (true) {
          output.checkpointWork();
          int count;
          try {
            count = content.read(window);
          } catch (ArchiveException failure) {
            throw failure;
          } catch (IOException failure) {
            throw sourceContext.failure(FailureKind.SOURCE, "operation.source-io", failure);
          }
          if (count < 0) break;
          window.flip();
          output.write(position, window);
          position += count;
          window.clear();
        }
      } catch (ArchiveException failure) {
        throw failure;
      } catch (IOException failure) {
        throw sourceContext.failure(FailureKind.SOURCE, "operation.source-io", failure);
      }
    } finally {
      if (compressed) compressedDecoder.release();
    }
  }

  /** Identifies payloads that may reserve the opened archive's sole native decoder capacity. */
  private static boolean requiresDecoder(EntryMetadata metadata) {
    return switch (metadata.facts()) {
      case EntryMetadata.Tes3 ignored -> false;
      case EntryMetadata.VersionedBsa bsa -> bsa.compressed();
      case EntryMetadata.GeneralBa2 general -> general.packedSize() != 0;
      case EntryMetadata.DdsBa2 dds ->
          dds.chunks().stream().anyMatch(chunk -> chunk.packedSize() != 0);
    };
  }
}
