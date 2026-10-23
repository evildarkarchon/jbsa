package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec;
import io.github.evildarkarchon.jbsa.internal.codec.Checkpoint;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadDecoder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Parent-owned input and eager payload index; no storage seam is exported through JPMS. */
public final class OwnedArchive implements OpenArchive {
  private final Object lifetime = new Object();
  private final ArchiveInput input;
  private final ResourceLimits limits;
  private final IoContext context;
  private final ResourceBudget budget;
  private final ArchiveInspection inspection;
  private final List<StoredEntry> entries;
  private final Set<Content> children = Collections.newSetFromMap(new IdentityHashMap<>());

  /** The codecs this archive's cursors have admitted; guarded by {@link #lifetime}. */
  private final ReadCodecs codecs = new ReadCodecs();

  private boolean closed;

  /** Adopts the validated index and the sole backing handle after the loader completes. */
  private OwnedArchive(
      ArchiveInput input,
      ResourceLimits limits,
      IoContext context,
      ResourceBudget budget,
      ArchiveInspection inspection,
      List<StoredEntry> entries) {
    this.input = input;
    this.limits = limits;
    this.context = context;
    this.budget = budget;
    this.inspection = inspection;
    this.entries = List.copyOf(entries);
  }

  /** Internal format integration seam; loaders validate metadata without reading payloads. */
  @FunctionalInterface
  public interface IndexLoader {
    /**
     * Returns detached structural evidence after populating the complete bounded index. A
     * production loader returns {@link IndexSession#read}'s inspection; any other evidence is
     * validated by the session before the archive is adopted.
     */
    ArchiveInspection load(IndexSession session) throws IOException;
  }

  /** Opens exactly one input and closes it if eager index construction fails. */
  public static OpenArchive load(
      Path path, ResourceLimits limits, Operation operation, IndexLoader loader)
      throws IOException {
    return load(ArchiveInput.open(path, operation), limits, IoContext.of(path, operation), loader);
  }

  /** Applies a mutation's warning policy before retained structural evidence can be truncated. */
  public static OpenArchive load(
      Path path,
      ResourceLimits limits,
      Operation operation,
      DiagnosticPolicy policy,
      IndexLoader loader)
      throws IOException {
    return load(
        ArchiveInput.open(path, operation), limits, IoContext.of(path, operation), policy, loader);
  }

  /** Adopts an input for internal format integration and deterministic fault-injection tests. */
  static OpenArchive load(
      ArchiveInput input, ResourceLimits limits, IoContext context, IndexLoader loader)
      throws IOException {
    return load(input, limits, context, DiagnosticPolicy.standard(), loader);
  }

  /** Owns the same failure-safe lifetime with an explicitly selected diagnostic policy. */
  private static OpenArchive load(
      ArchiveInput input,
      ResourceLimits limits,
      IoContext context,
      DiagnosticPolicy policy,
      IndexLoader loader)
      throws IOException {
    ResourceBudget budget = new ResourceBudget(limits, context);
    try {
      budget.reserve(0, 0, 1, 0);
      IndexSession session = new IndexSession(input, budget, context, limits, policy);
      ArchiveInspection inspection = session.complete(Objects.requireNonNull(loader.load(session)));
      return new OwnedArchive(input, limits, context, budget, inspection, session.entries());
    } catch (IOException | RuntimeException | Error cause) {
      FailureRetention failures = new FailureRetention(limits, context.operation());
      if (cause instanceof ArchiveException failure) {
        failures.accept(failure);
      } else if (cause instanceof IOException failure) {
        failures.accept(context.failure(FailureKind.SOURCE, "operation.source-io", failure));
      }
      try {
        input.close();
      } catch (IOException cleanup) {
        if (failures.failed()) {
          failures.accept(
              cleanup instanceof ArchiveException failure
                  ? failure
                  : new IoContext(
                          context.path(),
                          context.operation(),
                          OperationPhase.CLEANUP,
                          context.ordinal())
                      .failure(FailureKind.SOURCE, "operation.source-io", cleanup));
        } else {
          // Unchecked VM/programmer failures have no structured operation outcome to own cleanup.
          cause.addSuppressed(cleanup);
        }
      }
      budget.close();
      if (failures.failed()) throw failures.finish(List.of());
      throw cause;
    }
  }

  /**
   * An encoded range has no decoded cache; each compressed child owns independent codec state.
   * {@link IndexSession} fills the table during the load.
   */
  record StoredEntry(
      EntryMetadata metadata,
      long offset,
      long streamSize,
      PayloadCodec codec,
      byte[] header,
      List<EntryMetadata.DdsChunk> chunks) {
    /** Retains the existing single-span representation for non-texture entries. */
    StoredEntry(EntryMetadata metadata, long offset, long streamSize, PayloadCodec codec) {
      this(metadata, offset, streamSize, codec, null, List.of());
    }
  }

  /**
   * Charges whole General blocks but only the largest independently decoded DDS chunk.
   *
   * <p>This is the cursor's own decoded copy of a whole-block codec's payload (raw LZ4), which it
   * charges on top of the admitted codec's cost; a streaming codec's cursor holds no copy, so it
   * charges nothing here. {@link PayloadCodec#decodedCopyBytes} decides which codecs hold one.
   */
  private static long decodedCopyHeap(StoredEntry stored) {
    long largestBlock =
        stored.header() == null
            ? stored.metadata().decodedSize()
            : stored.chunks().stream()
                .filter(chunk -> chunk.packedSize() != 0)
                .mapToLong(EntryMetadata.DdsChunk::unpackedSize)
                .max()
                .orElse(0);
    return stored.codec().decodedCopyBytes(largestBlock);
  }

  /** Returns detached structural evidence, including after the owned input has closed. */
  @Override
  public ArchiveInspection inspection() {
    return inspection;
  }

  /** Returns the validated long entry count without requiring a live backing handle. */
  @Override
  public long entryCount() {
    return entries.size();
  }

  /**
   * Retrieves a parent-bound capability while leaving its detached metadata independently usable.
   */
  @Override
  public ArchiveEntry entry(long ordinal) {
    synchronized (lifetime) {
      if (closed) throw new IllegalStateException("Archive is closed");
      if (ordinal < 0 || ordinal >= entries.size())
        throw new IndexOutOfBoundsException("Entry ordinal");
      StoredEntry stored = entries.get(Math.toIntExact(ordinal));
      return new ArchiveEntry() {
        /** Returns detached entry facts without extending the parent handle's lifetime. */
        @Override
        public EntryMetadata metadata() {
          return stored.metadata();
        }

        /** Atomically admits a fresh child against parent close. */
        @Override
        public EntryContent openContent() throws IOException {
          synchronized (lifetime) {
            if (closed) throw new ClosedChannelException();
            if (stored.metadata().decodedSize() > limits.maxDecodedBytes()) {
              FailureRetention retention = new FailureRetention(limits, contentOperation());
              retention.assessment(inspection.assessment());
              retention.accept(
                  contentContext(ordinal)
                      .limit(
                          "maxDecodedBytes",
                          limits.maxDecodedBytes(),
                          Long.toString(stored.metadata().decodedSize())));
              throw retention.finish(List.of());
            }
            // The archive admits each codec the first time a cursor needs it. A BSA frame pins its
            // provider here, before its credit is reserved, so the credit charges the decoder that
            // will run. Raw LZ4 is admitted when read() first decodes a block instead, which keeps
            // its capability failure located at the entry; until then the cursor charges only its
            // decoded copy. Each raw decode's working set is charged separately, by
            // decodeRawLz4 just before that call, never folded into this credit: here its
            // exhaustion would surface from openContent without the entry name, not from read().
            // The credit is the admitted codec's cost plus the cursor's own decoded copy, each
            // zero for the codecs it does not apply to.
            AdmittedCodec codec =
                stored.codec().decodesWholeBlocks()
                    ? null
                    : admitted(stored.codec(), contentContext(stored.metadata().ordinal()));
            AdmittedCodec.Cost cost =
                codec == null
                    ? new AdmittedCodec.Cost(0, 0)
                    : codec.decodeCost(stored.streamSize(), stored.metadata().decodedSize());
            ResourceBudget.Lease credit;
            try {
              credit =
                  budget.reserve(
                      512 + decodedCopyHeap(stored) + cost.heapBytes(), cost.nativeBytes(), 0, 0);
            } catch (ArchiveException capacity) {
              // Query children own READ_CONTENT evidence; mutation children retain their operation.
              FailureRetention retention = new FailureRetention(limits, contentOperation());
              retention.assessment(inspection.assessment());
              retention.accept(
                  contentContext(ordinal)
                      .failure(
                          capacity.kind(),
                          capacity.primaryFailure().diagnosticIdentifier().orElseThrow(),
                          capacity.getCause()));
              throw retention.finish(List.of());
            }
            Content content;
            try {
              content = new Content(stored, credit, codec);
            } catch (ArchiveException failure) {
              // Decoder creation failed before the child could adopt the reserved credits.
              credit.close();
              throw failure;
            }
            children.add(content);
            return content;
          }
        }
      };
    }
  }

  /**
   * Returns {@code codec} admitted for this archive's cursors, admitting it the first time any
   * cursor needs it. A failed admission is not remembered, so every later attempt fails again at
   * its own entry's {@code context}.
   *
   * <p>Takes {@link #lifetime}, which callers may already hold. Admission can load the portable
   * provider's library, but it never reads the input, so the lifetime lock's rule still holds.
   *
   * @throws ArchiveException {@code CAPABILITY codec.unavailable} when no LZ4 provider can be
   *     admitted
   */
  private AdmittedCodec admitted(PayloadCodec codec, IoContext context) throws ArchiveException {
    synchronized (lifetime) {
      return codecs.admit(codec, context);
    }
  }

  /** Returns how many codec admissions this archive has performed; a test seam. */
  int codecAdmissions() {
    synchronized (lifetime) {
      return codecs.admissions();
    }
  }

  /** Creates the consumer-thread operation location for payload and per-channel policy failures. */
  private IoContext contentContext(long ordinal) {
    return new IoContext(
        context.path(), contentOperation(), OperationPhase.PROCESSING, OptionalLong.of(ordinal));
  }

  /**
   * Public query children are separate operations; mutation children belong to their invocation.
   */
  private Operation contentOperation() {
    return context.operation() == Operation.PACK || context.operation() == Operation.EXTRACT
        ? context.operation()
        : Operation.READ_CONTENT;
  }

  /**
   * Invalidates children before releasing the handle; concurrent closers observe completed cleanup.
   */
  @Override
  public void close() throws IOException {
    synchronized (lifetime) {
      if (closed) return;
      closed = true;
      for (Content child : children) {
        child.open = false;
        if (child.decoder != null) child.decoder.close();
        child.decodedRawLz4 = null;
        child.credit.close();
      }
      children.clear();
      try {
        input.close();
      } finally {
        budget.close();
      }
    }
  }

  /** Independent sequential cursor; the lifetime lock is never held across an input read. */
  private final class Content implements EntryContent {
    private final StoredEntry stored;
    private final ResourceBudget.Lease credit;

    /** The entry's admitted codec, or null for raw LZ4, which is admitted at its first decode. */
    private final AdmittedCodec codec;

    /**
     * The live streaming decoder: an ordinary zlib or BSA frame payload's, created with the cursor,
     * or the current DDS chunk's (zlib or stored), created under the lifetime lock and closed at
     * the chunk's end.
     */
    private PayloadDecoder decoder;

    private ByteBuffer decodedRawLz4;
    private int chunkIndex;
    private final Object reads = new Object();
    private boolean open = true;
    private long position;
    private volatile ArchiveAssessment assessment;

    /**
     * Adopts reserved credits and creates at most one parent-owned ordinary-payload decoder.
     *
     * @param codec the codec openContent admitted, or null for raw LZ4
     * @throws ArchiveException if the LZ4 frame decoder cannot be created
     */
    private Content(StoredEntry stored, ResourceBudget.Lease credit, AdmittedCodec codec)
        throws ArchiveException {
      this.stored = stored;
      this.credit = credit;
      this.codec = codec;
      // Ordinary stored content keeps the copy loop in read(), whose completion shares close's
      // linearization point; DDS entries create one decoder per chunk in readDds instead.
      decoder =
          stored.header() == null && codec != null && codec.codec() != PayloadCodec.STORED
              ? codec.decoder(
                  (offset, bytes) -> input.readExact(Math.addExact(stored.offset(), offset), bytes),
                  stored.streamSize(),
                  stored.metadata().decodedSize(),
                  credit,
                  contentContext(stored.metadata().ordinal()))
              : null;
    }

    /** Returns terminal EOF evidence when established, retaining it after any subsequent close. */
    @Override
    public Optional<ArchiveAssessment> assessment() {
      return Optional.ofNullable(assessment);
    }

    /** Observes child validity under the same lock that linearizes parent and child close. */
    @Override
    public boolean isOpen() {
      synchronized (lifetime) {
        return open;
      }
    }

    /**
     * Serializes reads of this cursor, while allowing independent sibling reads and parent close.
     */
    @Override
    public int read(ByteBuffer destination) throws IOException {
      Objects.requireNonNull(destination);
      synchronized (reads) {
        synchronized (lifetime) {
          if (!open || closed) throw new ClosedChannelException();
        }
        try {
          if (Thread.currentThread().isInterrupted()) {
            // Even an empty stored entry must not hide a caller-interrupted shared-input lifetime.
            throw new ClosedByInterruptException();
          }
          if (destination.isReadOnly()) throw new java.nio.ReadOnlyBufferException();
          if (stored.header() != null) {
            return publishRead(readDds(destination));
          }
          if (decoder != null) {
            return publishRead(decoder.read(destination));
          }
          if (stored.codec().decodesWholeBlocks()) {
            return publishRead(readRawLz4(destination));
          }
          int count =
              (int)
                  Math.min(
                      Math.min(destination.remaining(), ExactIo.WINDOW_BYTES),
                      stored.metadata().decodedSize() - position);
          ByteBuffer window = destination.slice();
          window.limit(count);
          if (count > 0) input.readExact(Math.addExact(stored.offset(), position), window);
          synchronized (lifetime) {
            // Completion and close share a linearization point even if the native read succeeded.
            if (!open || closed) throw new AsynchronousCloseException();
            if (!destination.hasRemaining()) return 0;
            if (count == 0) {
              if (assessment == null)
                assessment =
                    new ArchiveAssessment(
                        inspection.assessment().disposition(),
                        new ValidationExtent.Payloads(Set.of(stored.metadata().ordinal())),
                        inspection.assessment().diagnostics());
              return -1;
            }
            position += count;
            destination.position(destination.position() + count);
            return count;
          }
        } catch (ClosedByInterruptException cause) {
          try {
            OwnedArchive.this.close();
          } catch (IOException cleanup) {
            cause.addSuppressed(cleanup);
          }
          throw cause;
        } catch (ClosedChannelException cause) {
          synchronized (lifetime) {
            if (!input.isOpen()) OwnedArchive.this.close();
          }
          throw new AsynchronousCloseException();
        } catch (ArchiveException cause) {
          synchronized (lifetime) {
            // Failed provider completions must honor the same close decision as successful reads.
            if (!open || closed) throw new AsynchronousCloseException();
            close();
            throw payloadFailure(cause);
          }
        }
      }
    }

    /**
     * Linearizes decoder completion, EOF evidence, and parent close at one publication boundary.
     */
    private int publishRead(int count) throws AsynchronousCloseException {
      synchronized (lifetime) {
        if (!open || closed) throw new AsynchronousCloseException();
        if (count < 0 && assessment == null)
          assessment =
              new ArchiveAssessment(
                  inspection.assessment().disposition(),
                  new ValidationExtent.Payloads(Set.of(stored.metadata().ordinal())),
                  inspection.assessment().diagnostics());
        return count;
      }
    }

    /**
     * Decodes a raw block once, then serves bounded sequential windows from the owned heap copy.
     */
    private int readRawLz4(ByteBuffer destination) throws IOException {
      if (!destination.hasRemaining()) return 0;
      if (decodedRawLz4 == null) {
        decodedRawLz4 =
            decodeRawLz4(stored.offset(), stored.streamSize(), stored.metadata().decodedSize());
      }
      return copyDecoded(destination);
    }

    /**
     * Serves the next bounded window of the decoded raw block into {@code destination}, which has
     * room. Window copying is cursor logic, shared by General entries and DDS chunks.
     *
     * @return the bytes copied, or -1 once the decoded block is exhausted
     */
    private int copyDecoded(ByteBuffer destination) {
      if (!decodedRawLz4.hasRemaining()) return -1;
      int count = Math.min(destination.remaining(), decodedRawLz4.remaining());
      ByteBuffer window = decodedRawLz4.slice();
      window.limit(count);
      destination.put(window);
      decodedRawLz4.position(decodedRawLz4.position() + count);
      return count;
    }

    /**
     * Decodes one exact raw-LZ4 span into the heap credit owned by this content cursor. The raw
     * codec is admitted at the archive's first raw decode and reused after; its whole-block working
     * set is reserved for this call only and released on return, so a capability or capacity
     * failure surfaces from {@code read()} at the entry.
     */
    private ByteBuffer decodeRawLz4(long offset, long streamSize, long decodedSize)
        throws IOException {
      IoContext payloadContext = contentContext(stored.metadata().ordinal());
      AdmittedCodec.checkDecodeSize(stored.codec(), streamSize, decodedSize, payloadContext);
      ByteBuffer decoded = ByteBuffer.allocate(Math.toIntExact(decodedSize));
      Checkpoint interruption =
          () -> {
            if (Thread.currentThread().isInterrupted()) throw new ClosedByInterruptException();
          };
      // Admission, then interruption, then the charge: the order the self-reserving decoder kept,
      // so a capability failure or an interrupt still wins over capacity exhaustion.
      AdmittedCodec raw = admitted(stored.codec(), payloadContext);
      interruption.check();
      AdmittedCodec.Cost cost = raw.decodeCost(streamSize, decodedSize);
      try (var lease = budget.reserve(cost.heapBytes(), cost.nativeBytes(), 0, 0)) {
        raw.decode(
            (relative, bytes) -> input.readExact(Math.addExact(offset, relative), bytes),
            streamSize,
            decodedSize,
            (relative, bytes) -> {
              if (relative != decoded.position())
                throw payloadContext.failure(
                    FailureKind.INTERNAL, "codec.nonsequential-output", null);
              decoded.put(bytes);
            },
            interruption,
            lease,
            payloadContext);
      }
      return decoded.flip();
    }

    /**
     * Streams the generated envelope, then each chunk through the module's codec: a packed raw-LZ4
     * chunk decodes whole into the cursor's copy, and a packed zlib or a stored chunk streams
     * through one live decoder on the cursor's credit. Only one chunk's state is live at a time.
     */
    private int readDds(ByteBuffer destination) throws IOException {
      if (!destination.hasRemaining()) return 0;
      if (position < stored.header().length) {
        int count = (int) Math.min(destination.remaining(), stored.header().length - position);
        destination.put(stored.header(), (int) position, count);
        position += count;
        return count;
      }
      while (chunkIndex < stored.chunks().size()) {
        EntryMetadata.DdsChunk chunk = stored.chunks().get(chunkIndex);
        if (chunk.packedSize() > 0 && stored.codec().decodesWholeBlocks()) {
          if (decodedRawLz4 == null)
            decodedRawLz4 =
                decodeRawLz4(chunk.payloadOffset(), chunk.packedSize(), chunk.unpackedSize());
          int count = copyDecoded(destination);
          if (count >= 0) return count;
          decodedRawLz4 = null;
        } else {
          IoContext payloadContext = contentContext(stored.metadata().ordinal());
          // A stored chunk holds its unpacked bytes verbatim, whatever the entry's codec.
          AdmittedCodec chunkCodec =
              chunk.packedSize() > 0 ? codec : admitted(PayloadCodec.STORED, payloadContext);
          synchronized (lifetime) {
            // Decoder creation shares close's lock so parent close cannot miss new native state.
            if (!open || closed) throw new AsynchronousCloseException();
            if (decoder == null)
              decoder =
                  chunkCodec.decoder(
                      (offset, bytes) ->
                          input.readExact(Math.addExact(chunk.payloadOffset(), offset), bytes),
                      chunk.packedSize(),
                      chunk.unpackedSize(),
                      credit,
                      payloadContext);
          }
          int count = decoder.read(destination);
          if (count >= 0) return count;
          synchronized (lifetime) {
            decoder.close();
            decoder = null;
          }
        }
        chunkIndex++;
      }
      return -1;
    }

    /**
     * Retains earlier evidence and records a corrupt selected payload without reclassifying the
     * detached structural assessment. Provider I/O failures make no new content-validity claim.
     */
    private ArchiveException payloadFailure(ArchiveException cause) {
      FailureRetention retention = new FailureRetention(limits, contentOperation());
      retention.assessment(assessment == null ? inspection.assessment() : assessment);
      List<Diagnostic> diagnostics =
          cause.diagnostics().stream()
              .map(
                  diagnostic ->
                      new Diagnostic(
                          diagnostic.identifier(),
                          diagnostic.severity(),
                          contentOperation(),
                          OperationPhase.PROCESSING,
                          payloadLocation(diagnostic.location()),
                          diagnostic.values(),
                          diagnostic.explanation()))
              .toList();
      Failure original = cause.primaryFailure();
      Failure primary =
          new Failure(
              original.kind(),
              OperationPhase.PROCESSING,
              OptionalLong.of(stored.metadata().ordinal()),
              original.diagnosticIdentifier(),
              Optional.of(
                  payloadLocation(original.location().orElse(DiagnosticLocation.operation()))),
              original.cause());
      retention.accept(
          new ArchiveException(
              cause.getMessage(),
              primary,
              diagnostics,
              cause.artifacts(),
              Optional.empty(),
              cause.secondaryFailures()));
      if (cause.kind() == FailureKind.FORMAT) {
        retention.latestAssessment(
            new ArchiveAssessment(
                ArchiveDisposition.REJECTED,
                new ValidationExtent.Payloads(Set.of(stored.metadata().ordinal())),
                retention.diagnostics()));
      }
      return retention.finish(List.of());
    }

    /** Adds the selected entry while preserving the encoded field, span, and artifact evidence. */
    private DiagnosticLocation payloadLocation(DiagnosticLocation original) {
      return new DiagnosticLocation(
          Optional.of(context.path()),
          OptionalLong.of(stored.metadata().ordinal()),
          Optional.of(stored.metadata().displayName()),
          original.field(),
          original.byteSpan(),
          original.artifact());
    }

    /**
     * Releases this cursor alone; it never closes the shared input or creates an EOF assessment.
     */
    @Override
    public void close() {
      synchronized (lifetime) {
        open = false;
        children.remove(this);
        if (decoder != null) decoder.close();
        decodedRawLz4 = null;
        credit.close();
      }
    }
  }
}
