package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Parent-owned input and eager payload index; no storage seam is exported through JPMS. */
public final class OwnedArchive implements OpenArchive {
  /** Complete internal codec identity for one ordinary payload span. */
  public enum PayloadCodec {
    STORED,
    ZLIB,
    LZ4_FRAME
  }

  private final Object lifetime = new Object();
  private final ArchiveInput input;
  private final ResourceLimits limits;
  private final IoContext context;
  private final ResourceBudget budget;
  private final ArchiveInspection inspection;
  private final List<StoredEntry> entries;
  private final Set<Content> children = Collections.newSetFromMap(new IdentityHashMap<>());
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
    /** Returns detached structural evidence after populating the complete bounded index. */
    ArchiveInspection load(IndexBuilder builder) throws IOException;
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
      IndexBuilder builder = new IndexBuilder(input, budget, context);
      ArchiveInspection inspection = Objects.requireNonNull(loader.load(builder));
      if (builder.expected != builder.entries.size()
          || inspection.metadata().entryCount() != builder.expected) {
        throw context.failure(FailureKind.FORMAT, "io.index-count-mismatch", null);
      }
      inspection =
          new ArchiveInspection(
              inspection.detection(),
              inspection.metadata(),
              inspection.assessment(),
              builder.entries.stream().map(StoredEntry::metadata).toList());
      inspection =
          ArchiveValidation.structure(
              inspection,
              builder.entries.stream().map(StoredEntry::metadata).toList(),
              context,
              new FailureRetention(limits, context.operation(), policy));
      return new OwnedArchive(input, limits, context, budget, inspection, builder.entries);
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
   * Eager index admission; format loaders remain internal and cannot publish an incomplete index.
   */
  public static final class IndexBuilder {
    private final ArchiveInput input;
    private final ResourceBudget budget;
    private final IoContext context;
    private final List<StoredEntry> entries = new ArrayList<>();
    private long expected = -1;

    /** Keeps the handle private so a loader cannot transfer or close parent ownership. */
    private IndexBuilder(ArchiveInput input, ResourceBudget budget, IoContext context) {
      this.input = input;
      this.budget = budget;
      this.context = context;
    }

    /** Returns the stable source extent without transferring handle ownership to the loader. */
    public long size() {
      return input.size();
    }

    /** Reads the fixed recognition prefix without charging it again as parsed format metadata. */
    public ByteBuffer readSelectors(int length) throws IOException {
      if (length < 0 || length > 36) throw new IllegalArgumentException("Selector extent");
      ByteBuffer result = ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      input.readExact(0, result);
      return result.flip();
    }

    /** Checks the encoded count before a format loader allocates or constructs entry metadata. */
    public void declareEntries(long count) throws ArchiveException {
      if (expected != -1) throw new IllegalStateException("Entry count already declared");
      ExactIo.multiply(count, 1, context);
      budget.entryIndex(count);
      if (count > Integer.MAX_VALUE)
        throw context.failure(FailureKind.POLICY, "io.index-capacity", null);
      expected = count;
    }

    /** Reads one encoded header/table window, charging metadata and heap before allocation. */
    public ByteBuffer readMetadata(long offset, int length) throws IOException {
      ExactIo.end(offset, length, input.size(), context);
      if (length > ExactIo.WINDOW_BYTES)
        throw context.failure(FailureKind.POLICY, "io.resource-capacity", null);
      budget.metadata(length);
      ByteBuffer result = ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      input.readExact(offset, result);
      return result.flip();
    }

    /** Retains an exact encoded name only after validating its full span and allocation credit. */
    public WireName readName(long offset, long length) throws IOException {
      ExactIo.end(offset, length, input.size(), context);
      budget.metadata(length);
      if (length > Integer.MAX_VALUE)
        throw context.failure(FailureKind.POLICY, "io.resource-capacity", null);
      byte[] bytes = new byte[Math.toIntExact(length)];
      input.readExact(offset, ByteBuffer.wrap(bytes));
      return new WireName(bytes);
    }

    /**
     * Borrows at most 128 decoded bytes for format warnings without claiming complete payload
     * validation. Codec and buffer credits are reserved before allocation and released on return.
     *
     * @param offset absolute stored stream offset after archive-specific framing
     * @param streamSize exact stored stream size
     * @param decodedSize declared decoded payload size
     * @param codec codec required to expose the decoded prefix
     * @param length maximum requested decoded prefix, from zero through 128 bytes
     * @throws IOException if input, capability, codec, or resource admission fails
     */
    public ByteBuffer readPayloadPrefix(
        long offset, long streamSize, long decodedSize, PayloadCodec codec, int length)
        throws IOException {
      if (length < 0 || length > 128) throw new IllegalArgumentException("Payload prefix extent");
      ExactIo.end(offset, streamSize, input.size(), context);
      try (var credit =
          budget.reserve(
              length
                  + switch (codec) {
                    case STORED -> 0;
                    case ZLIB -> JdkZlib.DECODE_HEAP_BYTES;
                    case LZ4_FRAME -> Lz4Frame.HEAP_BYTES;
                  },
              switch (codec) {
                case STORED -> 0;
                case ZLIB -> JdkZlib.DECODE_NATIVE_BYTES;
                case LZ4_FRAME -> Lz4Frame.DECODE_NATIVE_BYTES;
              },
              0,
              0)) {
        ByteBuffer prefix =
            ByteBuffer.allocate((int) Math.min(length, decodedSize))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        switch (codec) {
          case LZ4_FRAME -> {
            try (var decoder =
                BsaLz4Frame.decoder(
                    (relative, bytes) -> input.readExact(offset + relative, bytes),
                    streamSize,
                    decodedSize,
                    context)) {
              while (prefix.hasRemaining() && decoder.read(prefix) >= 0) {
                // Only the bounded prefix is requested; complete validation belongs to content EOF.
              }
            }
          }
          case ZLIB -> {
            try (var decoder =
                JdkZlib.decoder(
                    (relative, bytes) -> input.readExact(offset + relative, bytes),
                    streamSize,
                    decodedSize,
                    context)) {
              while (prefix.hasRemaining() && decoder.read(prefix) >= 0) {
                // Only the bounded prefix is requested; complete validation belongs to content EOF.
              }
            }
          }
          case STORED -> input.readExact(offset, prefix);
        }
        return prefix.flip();
      }
    }

    /** Validates a stored span without touching payload bytes, retaining archive-order metadata. */
    public void addStored(EntryMetadata metadata, long offset) throws ArchiveException {
      addStored(metadata, offset, metadata.storedSize());
    }

    /** Admits stored content whose wire record may also include bounded framing bytes. */
    public void addStored(EntryMetadata metadata, long offset, long contentSize)
        throws ArchiveException {
      if (expected < 0
          || entries.size() >= expected
          || metadata.ordinal() != entries.size()
          || contentSize != metadata.decodedSize()
          || contentSize > metadata.storedSize()) {
        throw context.failure(FailureKind.FORMAT, "io.index-record-mismatch", null);
      }
      ExactIo.end(offset, contentSize, input.size(), context);
      entries.add(new StoredEntry(metadata, offset, contentSize, PayloadCodec.STORED));
    }

    /**
     * Admits zlib framing separately from its decoded size without eagerly decoding the payload.
     */
    public void addZlib(EntryMetadata metadata, long streamOffset, long streamSize)
        throws ArchiveException {
      if (expected < 0 || entries.size() >= expected || metadata.ordinal() != entries.size())
        throw context.failure(FailureKind.FORMAT, "io.index-record-mismatch", null);
      ExactIo.end(streamOffset, streamSize, input.size(), context);
      entries.add(new StoredEntry(metadata, streamOffset, streamSize, PayloadCodec.ZLIB));
    }

    /** Admits one complete LZ4 frame for exact validation by its lazy content cursor. */
    public void addLz4Frame(EntryMetadata metadata, long streamOffset, long streamSize)
        throws ArchiveException {
      if (expected < 0 || entries.size() >= expected || metadata.ordinal() != entries.size())
        throw context.failure(FailureKind.FORMAT, "io.index-record-mismatch", null);
      ExactIo.end(streamOffset, streamSize, input.size(), context);
      entries.add(new StoredEntry(metadata, streamOffset, streamSize, PayloadCodec.LZ4_FRAME));
    }

    /** Admits a generated DDS envelope followed by independently bounded texture chunks. */
    public void addDds(EntryMetadata metadata, byte[] header, List<EntryMetadata.DdsChunk> chunks)
        throws ArchiveException {
      if (expected < 0 || entries.size() >= expected || metadata.ordinal() != entries.size())
        throw context.failure(FailureKind.FORMAT, "io.index-record-mismatch", null);
      long decoded = header.length;
      for (EntryMetadata.DdsChunk chunk : chunks) {
        ExactIo.end(
            chunk.payloadOffset(),
            chunk.packedSize() == 0 ? chunk.unpackedSize() : chunk.packedSize(),
            input.size(),
            context);
        decoded = ExactIo.end(decoded, chunk.unpackedSize(), Long.MAX_VALUE, context);
      }
      if (decoded != metadata.decodedSize())
        throw context.failure(FailureKind.FORMAT, "io.index-record-mismatch", null);
      entries.add(
          new StoredEntry(
              metadata,
              0,
              0,
              chunks.stream().anyMatch(c -> c.packedSize() != 0)
                  ? PayloadCodec.ZLIB
                  : PayloadCodec.STORED,
              header.clone(),
              List.copyOf(chunks)));
    }
  }

  /** An encoded range has no decoded cache; each compressed child owns independent codec state. */
  private record StoredEntry(
      EntryMetadata metadata,
      long offset,
      long streamSize,
      PayloadCodec codec,
      byte[] header,
      List<EntryMetadata.DdsChunk> chunks) {
    /** Retains the existing single-span representation for non-texture entries. */
    private StoredEntry(EntryMetadata metadata, long offset, long streamSize, PayloadCodec codec) {
      this(metadata, offset, streamSize, codec, null, List.of());
    }
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
            ResourceBudget.Lease credit;
            try {
              credit =
                  budget.reserve(
                      512
                          + switch (stored.codec()) {
                            case STORED -> 0;
                            case ZLIB -> JdkZlib.DECODE_HEAP_BYTES;
                            case LZ4_FRAME -> Lz4Frame.HEAP_BYTES;
                          },
                      switch (stored.codec()) {
                        case STORED -> 0;
                        case ZLIB -> JdkZlib.DECODE_NATIVE_BYTES;
                        case LZ4_FRAME -> Lz4Frame.DECODE_NATIVE_BYTES;
                      },
                      0,
                      0);
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
              content = new Content(stored, credit);
            } catch (ArchiveException failure) {
              // Capability preflight failed before the child could adopt the reserved credits.
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
        if (child.lz4Decoder != null) child.lz4Decoder.close();
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
    private JdkZlib.Decoder decoder;
    private BsaLz4Frame.Decoder lz4Decoder;
    private int chunkIndex;
    private long chunkPosition;
    private final Object reads = new Object();
    private boolean open = true;
    private long position;
    private volatile ArchiveAssessment assessment;

    /**
     * Adopts reserved credits and creates at most one parent-owned ordinary-payload decoder.
     *
     * @throws ArchiveException if native LZ4 capability cannot be established
     */
    private Content(StoredEntry stored, ResourceBudget.Lease credit) throws ArchiveException {
      this.stored = stored;
      this.credit = credit;
      decoder =
          stored.codec() == PayloadCodec.ZLIB && stored.header() == null
              ? JdkZlib.decoder(
                  (offset, bytes) -> input.readExact(Math.addExact(stored.offset(), offset), bytes),
                  stored.streamSize(),
                  stored.metadata().decodedSize(),
                  contentContext(stored.metadata().ordinal()))
              : null;
      lz4Decoder =
          stored.codec() == PayloadCodec.LZ4_FRAME
              ? BsaLz4Frame.decoder(
                  (offset, bytes) -> input.readExact(Math.addExact(stored.offset(), offset), bytes),
                  stored.streamSize(),
                  stored.metadata().decodedSize(),
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
            int count = readDds(destination);
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
          if (decoder != null) {
            int count = decoder.read(destination);
            synchronized (lifetime) {
              // Codec completion and parent close use the same publication boundary as stored
              // reads.
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
          if (lz4Decoder != null) {
            int count = lz4Decoder.read(destination);
            synchronized (lifetime) {
              // Native completion and parent close share the same publication boundary.
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

    /** Streams the generated envelope and serialized chunks with at most one live zlib decoder. */
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
        if (chunk.packedSize() > 0) {
          synchronized (lifetime) {
            // Decoder creation shares close's lock so parent close cannot miss new native state.
            if (!open || closed) throw new AsynchronousCloseException();
            if (decoder == null)
              decoder =
                  JdkZlib.decoder(
                      (offset, bytes) ->
                          input.readExact(Math.addExact(chunk.payloadOffset(), offset), bytes),
                      chunk.packedSize(),
                      chunk.unpackedSize(),
                      contentContext(stored.metadata().ordinal()));
          }
          int count = decoder.read(destination);
          if (count >= 0) return count;
          synchronized (lifetime) {
            decoder.close();
            decoder = null;
          }
        } else if (chunkPosition < chunk.unpackedSize()) {
          int count =
              (int)
                  Math.min(
                      Math.min(destination.remaining(), ExactIo.WINDOW_BYTES),
                      chunk.unpackedSize() - chunkPosition);
          ByteBuffer window = destination.slice();
          window.limit(count);
          input.readExact(Math.addExact(chunk.payloadOffset(), chunkPosition), window);
          destination.position(destination.position() + count);
          chunkPosition += count;
          return count;
        }
        chunkIndex++;
        chunkPosition = 0;
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
        if (lz4Decoder != null) lz4Decoder.close();
        credit.close();
      }
    }
  }
}
