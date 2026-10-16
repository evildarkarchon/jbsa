package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;

/** Selected-entry extraction through shared validated-path staging and publication semantics. */
public final class ArchiveExtractor {
  private ArchiveExtractor() {}

  /**
   * Validates the complete selected plan before staging and settles the source before final commit.
   * The extraction runs as a body of the session's operation template, which owns the epilogue:
   * mapping unexpected throwables, closing the source archive, and finishing the operation once.
   */
  public static OperationReport extract(ExtractRequest request, OperationControl control)
      throws ArchiveException {
    OperationSession operation =
        new OperationSession(
            Operation.EXTRACT, request.resourceLimits(), request.diagnosticPolicy(), control);
    IoContext context = IoContext.of(request.source(), Operation.EXTRACT);
    return operation.run(context, resources -> extract(request, operation, context, resources));
  }

  /**
   * The extraction body: preflight of the selected plan, then publication of one extraction plan.
   * The opened source archive is registered with the template, which closes it after publication
   * settles if the plan's source-close action did not already run (closing it again is a no-op).
   * The mutation budget is registered after it, so it closes first.
   *
   * @return the artifacts publication settled
   * @throws IOException a failure before publication; publication accepts its own failures
   */
  private static OperationSession.Published extract(
      ExtractRequest request,
      OperationSession operation,
      IoContext context,
      OperationSession.Resources resources)
      throws IOException {
    operation.begin();
    WorkerSelection.UpTo selectedWorkers = WorkerLimits.snapshot(request.workerSelection());
    OpenArchive archive =
        ArchiveReaders.open(
            request.source(), request.openOptions(), Operation.EXTRACT, request.diagnosticPolicy());
    resources.register(archive::close);
    operation.assessment(archive.inspection().assessment());
    OpenArchive source = archive;
    Set<Long> requested =
        request.entries() instanceof EntrySelection.Ordinals selection
            ? new HashSet<>(selection.ordinals())
            : null;
    if (requested != null && requested.stream().anyMatch(ordinal -> ordinal >= source.entryCount()))
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
    // The opened archive's native budget admits one decoder. Stored entries still run freely.
    Semaphore compressedDecoder = new Semaphore(1, true);
    for (ArchiveEntry entry : selected) {
      // Every checkpoint, failure, and Artifact is located by the archive entry ordinal, which
      // differs from the selection position for a sparse selection.
      IoContext entryContext =
          new IoContext(
              request.source(),
              Operation.EXTRACT,
              OperationPhase.PROCESSING,
              OptionalLong.of(entry.metadata().ordinal()));
      entries.add(
          new PublicationTransaction.Entry(
              entry.metadata().displayName(),
              output -> copy(entry, output, compressedDecoder, entryContext),
              entry.metadata().decodedSize(),
              entry.metadata().ordinal()));
    }
    var plan =
        new PublicationTransaction.Plan.Extraction(
            request.destination(),
            entries,
            request.targetPolicy(),
            selectedWorkers,
            archiveOrdinal -> {
              validated.add(archiveOrdinal);
              operation.latestAssessment(
                  new ArchiveAssessment(
                      source.inspection().assessment().disposition(),
                      new ValidationExtent.Payloads(validated),
                      source.inspection().assessment().diagnostics()));
            },
            // Source cleanup must succeed before the last selected file can become visible.
            () -> closeSource(source, request.source()));
    // Plan-level evidence, the budget's PREFLIGHT location among it, is located at the first
    // selected entry's archive ordinal, or 0 for an empty selection (JBSA-OPS-006).
    ResourceBudget budget =
        ResourceBudget.forMutation(
            request.resourceLimits(),
            new IoContext(
                request.destination().toAbsolutePath().normalize(),
                Operation.EXTRACT,
                OperationPhase.PREFLIGHT,
                OptionalLong.of(plan.planOrdinal())));
    resources.register(budget::close);
    return new OperationSession.Published(PublicationTransaction.publish(operation, budget, plan));
  }

  /**
   * The extraction's source-close action, which publication runs once before its final commit.
   *
   * @throws ArchiveException SOURCE {@code operation.source-io} at CLEANUP, with no ordinal, when
   *     the archive cannot close; a structured close failure keeps its own location
   */
  private static void closeSource(OpenArchive source, Path path) throws ArchiveException {
    try {
      source.close();
    } catch (ArchiveException failure) {
      throw failure;
    } catch (IOException failure) {
      throw new IoContext(path, Operation.EXTRACT, OperationPhase.CLEANUP, OptionalLong.empty())
          .failure(FailureKind.SOURCE, "operation.source-io", failure);
    }
  }

  /**
   * Copies one decoded payload in bounded windows: the one copy loop for both strategies. Its only
   * strategy-dependent step is the checkpoint, and the staged file supplies that: the coordinator's
   * cancellation checkpoint when publication stages on the caller thread, or the worker stop state
   * on a private worker. The loop never touches coordinator-owned progress or assessment;
   * publication's on-staged hook advances the Validation Extent.
   *
   * <p>Every unstructured failure to open, read, or close the entry's source content is SOURCE
   * {@code operation.source-io} at the entry's archive ordinal, in both strategies. Failures from
   * the staged output, including a worker stop, pass through unchanged.
   *
   * @param sourceContext PROCESSING at the entry's archive entry ordinal
   */
  private static void copy(
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
      try (SourceContent release = () -> closeContent(content, sourceContext)) {
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
      }
    } finally {
      if (compressed) compressedDecoder.release();
    }
  }

  /** Closes one entry's source content, structuring an unstructured close failure as SOURCE. */
  private static void closeContent(EntryContent content, IoContext sourceContext)
      throws ArchiveException {
    try {
      content.close();
    } catch (ArchiveException failure) {
      throw failure;
    } catch (IOException failure) {
      throw sourceContext.failure(FailureKind.SOURCE, "operation.source-io", failure);
    }
  }

  /** Scopes one entry's source content so its close failure is structured like its reads. */
  @FunctionalInterface
  private interface SourceContent extends AutoCloseable {
    /** Closes the content, reporting an unstructured failure as SOURCE. */
    @Override
    void close() throws ArchiveException;
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
