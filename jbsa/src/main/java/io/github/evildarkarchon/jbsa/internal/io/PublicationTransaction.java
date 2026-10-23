package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongConsumer;

/**
 * Synchronous, operation-owned staging and atomic publication beneath the archive format writers.
 * Writers borrow one bounded positional output at a time and must not retain it after returning.
 *
 * <p>Publication exposes three operations: {@link #publish} runs a sealed {@link Plan}, {@link
 * #preflightArchiveTargets} checks a known archive-part target set before any source is read, and
 * {@link #splitPath} is the split-name rule. The plan's type selects the strategy (ADR-0005).
 */
public final class PublicationTransaction {
  private PublicationTransaction() {}

  /**
   * Stages, commits, and cleans one publication plan inside the caller's operation template.
   * Publication settles its own staging but not the operation: failures accumulate in the session,
   * and the template finishes it. The plan's source-close action runs exactly once, after every
   * admitted stage settles and before the final Publication Commit; if a failure ends publication
   * first, the action does not run and the caller's registered resource closes the source.
   *
   * @param operation the operation session, already in PROCESSING when an archive-set plan says so
   * @param budget the operation's mutation budget, shared with any planner; the caller owns and
   *     closes it, and its limits are the publication's Resource Limits
   * @param plan what to publish; its type selects the strategy
   * @return the artifacts publication settled, whether or not it accepted a failure; their count is
   *     the operation's CLEANUP total
   */
  public static List<Artifact> publish(
      OperationSession operation, ResourceBudget budget, Plan plan) {
    return publish(operation, budget, plan, new FileActions());
  }

  /** Supplies the filesystem mutation boundary for deterministic publication and cleanup faults. */
  static List<Artifact> publish(
      OperationSession operation, ResourceBudget budget, Plan plan, FileActions files) {
    return new Session(operation, budget, plan, files).run();
  }

  /**
   * Checks a known archive-part target set before pack work admission, without creating staging.
   * The temporary containment pin is closed before this method returns.
   */
  public static void preflightArchiveTargets(
      Path destination,
      int partCount,
      TargetPolicy policy,
      ResourceLimits limits,
      IoContext context)
      throws IOException {
    Path target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(limits, "limits");
    Objects.requireNonNull(context, "context");
    if (partCount < 1) throw new IllegalArgumentException("An archive set needs a first part");
    if (target.getFileName() == null)
      throw context.failure(FailureKind.POLICY, "destination.not-file", null);
    if (partCount > limits.maxOutputs())
      throw context.limit("maxOutputs", limits.maxOutputs(), Integer.toString(partCount));
    List<String> names = new ArrayList<>(partCount);
    for (int index = 0; index < partCount; index++)
      names.add(splitPath(target, index + 1).getFileName().toString());
    // Production filesystem actions: publication reads identities and pins only through
    // FileActions.
    try (TargetPaths checked =
        TargetPaths.preflight(target.getParent(), names, policy, context, new FileActions())) {
      // The output set is validated now; the publishing transaction rechecks it before staging.
    }
  }

  /**
   * What one publication publishes. The case selects the strategy: an archive set stages every part
   * before one atomic set commit, and an extraction publishes either one new root or ordered
   * per-file commits into an existing tree, sequentially or on private workers.
   *
   * <p>Both cases stage through the one {@link StagedFile} type, so entry counting is a runtime
   * rule documented on each case rather than a type distinction.
   */
  public sealed interface Plan permits Plan.ArchiveSet, Plan.Extraction {
    /**
     * The plan's single source-close action. Publication runs it exactly once, after every admitted
     * stage settles and before the final Publication Commit, including for an empty plan.
     */
    SourceClose closeSource();

    /**
     * The ordinal of plan-level evidence that precedes or spans every output (JBSA-OPS-006): target
     * preflight, private staging and its probes, and a new extraction root's Artifact. It is the
     * first planned output's ordinal, or 0 for an empty plan, so a sparse extraction never reports
     * an unselected entry's ordinal. This is its one definition; the extractor locates its budget
     * here too.
     *
     * @return the first part's index for an archive set (always 0), or the first selected entry's
     *     archive entry ordinal for an extraction, or 0 when it selects nothing
     */
    long planOrdinal();

    /**
     * A finalized archive-part plan, published as one atomic set. Every part is staged before any
     * move. Stabilization that determines this plan must finish in operation-owned scratch first.
     * The {@link #streamed} and {@link #stabilized} factories name the two shapes the Pack Pipeline
     * builds.
     *
     * <p>Entry counting: each writer reports every final logical entry once through {@link
     * StagedFile#completedEntry}. Publication counts nothing itself.
     *
     * @param destination the first part's path; later parts follow {@link #splitPath}
     * @param writers one writer per part, in part order
     * @param policy the Target Policy for every part
     * @param processingEntered whether the operation already entered PROCESSING to stabilize its
     *     payloads before the part plan existed (Q10). Progress then continues in that phase
     *     instead of entering it again; target checks keep their PREFLIGHT location because they
     *     still precede staging.
     * @param closeSource closes the stabilization spool, or {@link SourceClose#NONE} when parts
     *     stream their sources themselves
     */
    record ArchiveSet(
        Path destination,
        List<Writer> writers,
        TargetPolicy policy,
        boolean processingEntered,
        SourceClose closeSource)
        implements Plan {
      /** Copies the writers and rejects missing components before destination effects. */
      public ArchiveSet {
        Objects.requireNonNull(destination, "destination");
        writers = List.copyOf(writers);
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(closeSource, "closeSource");
      }

      /**
       * A set whose parts read and close their own sources while they stage. Publication enters
       * PROCESSING itself, and the plan holds no source to close.
       */
      public static ArchiveSet streamed(
          Path destination, List<Writer> writers, TargetPolicy policy) {
        return new ArchiveSet(destination, writers, policy, false, SourceClose.NONE);
      }

      /**
       * A set whose payloads the operation already stabilized in PROCESSING (Q10), replayed from a
       * spool that closes as the plan's source before the commit.
       *
       * @param closeSpool closes the stabilization spool
       */
      public static ArchiveSet stabilized(
          Path destination, List<Writer> writers, TargetPolicy policy, SourceClose closeSpool) {
        return new ArchiveSet(destination, writers, policy, true, closeSpool);
      }

      /** Every archive set starts at part index 0, even an empty one. */
      @Override
      public long planOrdinal() {
        return 0;
      }
    }

    /**
     * Validated selected entries, published as one new root or as ordered per-file commits into an
     * existing tree. Publication decides both the root shape (from the preflighted root) and the
     * strategy: one worker or an empty selection stages on the caller thread; otherwise private
     * workers stage and the caller thread consumes in Logical Plan Order.
     *
     * <p>Entry counting: writers must not call {@link StagedFile#completedEntry}; publication
     * counts each entry once, with its staged extent, after its writer completes.
     *
     * @param root the extraction root
     * @param entries the selection, in strictly increasing archive entry ordinal order
     * @param policy the Target Policy for every selected file
     * @param workers the operation's worker snapshot
     * @param onStaged receives each staged entry's archive entry ordinal on the caller thread, in
     *     Logical Plan Order, in both strategies; the extractor advances its Validation Extent here
     * @param closeSource closes the extraction source archive
     */
    record Extraction(
        Path root,
        List<Entry> entries,
        TargetPolicy policy,
        WorkerSelection.UpTo workers,
        LongConsumer onStaged,
        SourceClose closeSource)
        implements Plan {
      /**
       * Copies the entries and rejects missing components before destination effects.
       *
       * @throws IllegalArgumentException if the archive ordinals do not strictly increase, because
       *     a selection always keeps archive order and Logical Plan Order depends on it
       */
      public Extraction {
        Objects.requireNonNull(root, "root");
        entries = List.copyOf(entries);
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(workers, "workers");
        Objects.requireNonNull(onStaged, "onStaged");
        Objects.requireNonNull(closeSource, "closeSource");
        for (int index = 1; index < entries.size(); index++)
          if (entries.get(index).archiveOrdinal() <= entries.get(index - 1).archiveOrdinal())
            throw new IllegalArgumentException("Extraction entries must keep archive order");
      }

      /** The first selected entry's archive entry ordinal, or 0 when nothing is selected. */
      @Override
      public long planOrdinal() {
        return entries.isEmpty() ? 0 : entries.getFirst().archiveOrdinal();
      }

      /**
       * Whether private workers stage this extraction: publication decides from the plan. One
       * worker, or nothing to stage, keeps every stage on the caller thread.
       */
      boolean parallel() {
        return !entries.isEmpty() && workers.workers() > 1;
      }
    }
  }

  /**
   * One selected extraction entry, in the same shape for both strategies.
   *
   * @param name the decoded entry name, already validated
   * @param writer copies the entry's payload into its staged file
   * @param stagedExtent the entry's decoded size: the most its staged file may hold
   * @param archiveOrdinal the archive entry ordinal that locates every checkpoint, failure, and
   *     Artifact of this entry (JBSA-OPS-006)
   */
  public record Entry(String name, Writer writer, long stagedExtent, long archiveOrdinal) {
    /** Requires complete spelling, a writer, and a known extent and archive ordinal. */
    public Entry {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(writer, "writer");
      if (stagedExtent < 0 || archiveOrdinal < 0)
        throw new IllegalArgumentException("An entry needs its staged extent and archive ordinal");
    }
  }

  /** Settles a plan's source before its final commit, so no published output outlives it. */
  @FunctionalInterface
  public interface SourceClose {
    /** The action for a plan that holds no source of its own. */
    SourceClose NONE = () -> {};

    /**
     * Completes source ownership. A structured failure keeps its own location; an unstructured
     * {@link IOException} is located at publication's current phase and ordinal.
     */
    void close() throws IOException;
  }

  /** A format writer that fills or backpatches one borrowed, bounded staged file. */
  @FunctionalInterface
  public interface Writer {
    /** Writes the complete artifact; checked semantic failures retain their original evidence. */
    void write(StagedFile output) throws IOException;

    /** Checks a closed staged artifact before any member of the output set can be published. */
    default void validate(Path staged) throws IOException {
      // Formats without a readback hook retain their existing staging behavior.
    }
  }

  /**
   * A positional output whose retained extent is admitted before any write or sparse reservation.
   */
  public static final class StagedFile {
    private final FileChannel channel;
    private final ResourceBudget.Lease scratch;
    private final IoContext context;
    private final Session owner;
    private final OrderedWorkerRunner.Checkpoint workerCheckpoint;
    private final long maximumExtent;
    private long size;

    /** Borrows a single channel and its operation-owned extent reservation. */
    private StagedFile(
        FileChannel channel, ResourceBudget.Lease scratch, IoContext context, Session owner) {
      this(channel, scratch, context, owner, null, -1);
    }

    /** Borrows the worker's preadmitted scratch lease without charging each write twice. */
    private StagedFile(
        FileChannel channel,
        ResourceBudget.Lease scratch,
        IoContext context,
        Session owner,
        OrderedWorkerRunner.Checkpoint workerCheckpoint,
        long maximumExtent) {
      this.channel = channel;
      this.scratch = scratch;
      this.context = context;
      this.owner = owner;
      this.workerCheckpoint = workerCheckpoint;
      this.maximumExtent = maximumExtent;
    }

    /** Reserves a checked extent without allocating a header-sized heap buffer. */
    public void reserve(long extent) throws IOException {
      if (extent < 0) throw new IllegalArgumentException("Negative output extent");
      if (!channel.isOpen()) throw new IllegalStateException("Staged output lifetime has ended");
      owner.checkpoint();
      if (extent > size) {
        scratch.growScratch(extent - size);
        size = extent;
      }
    }

    /**
     * Writes every remaining byte positionally, reserving sparse gaps and backpatch extent first.
     */
    public void write(long position, ByteBuffer bytes) throws IOException {
      Objects.requireNonNull(bytes, "bytes");
      long end;
      try {
        if (position < 0) throw new ArithmeticException("Negative position");
        end = Math.addExact(position, bytes.remaining());
      } catch (ArithmeticException cause) {
        throw context.roleFailure("io.invalid-output-span", cause);
      }
      reserveForWrite(end);
      while (bytes.hasRemaining()) {
        checkpointWork();
        ByteBuffer window = bytes.slice();
        int count = Math.min(window.remaining(), ExactIo.WINDOW_BYTES);
        window.limit(count);
        ExactIo.write(channel, position, window, context);
        bytes.position(bytes.position() + count);
        position += count;
      }
    }

    /** Returns the admitted final extent, including reserved header regions. */
    public long size() {
      return size;
    }

    /**
     * Borrows an exact staged range for byte-confirmed sharing; never exposes the backing handle.
     * The range is the operation's own staged output, so a read fault is DESTINATION (ADR-0006).
     */
    public void read(long position, ByteBuffer bytes) throws IOException {
      owner.checkpoint();
      ExactIo.read(channel, size, position, bytes, context);
    }

    /**
     * Opens caller-closed stabilization scratch charged to the same peak budget as staged output.
     * Writers must close it before returning, so publication never precedes source settlement.
     */
    public SpillBuffer scratch() throws IOException {
      return SpillBuffer.open(owner.staging, owner.budget, context);
    }

    /**
     * Reports one final pack entry's uncompressed logical bytes after processing it exactly once.
     * Archive framing, replay, backpatching, and shared-payload copies must not call this method.
     * Extraction entries are counted by the publication adapter after their writer completes.
     */
    public void completedEntry(long decodedBytes) throws OperationStopped {
      if (owner.plan instanceof Plan.Extraction)
        throw new IllegalStateException("Extraction counts each writer once");
      owner.operationSession.processedEntry(decodedBytes);
    }

    /**
     * Admits a warning before retention and stops staging immediately if request policy rejects it.
     */
    public void diagnostic(Diagnostic diagnostic) throws OperationStopped {
      owner.operationSession.diagnostic(diagnostic);
    }

    /** Observes explicit stop state around codec calls and other bounded non-I/O work. */
    public void checkpoint() throws OperationStopped {
      owner.checkpoint();
    }

    /** Observes worker stop without calling the coordinator's progress or cancellation session. */
    public void checkpointWork() throws IOException {
      if (workerCheckpoint == null) owner.checkpoint();
      else workerCheckpoint.check();
    }

    /** Uses preadmitted worker scratch or the sequential writer's growing extent lease. */
    private void reserveForWrite(long extent) throws IOException {
      if (workerCheckpoint == null) {
        reserve(extent);
        return;
      }
      if (!channel.isOpen()) throw new IllegalStateException("Staged output lifetime has ended");
      workerCheckpoint.check();
      if (extent > maximumExtent)
        throw context.failure(FailureKind.FORMAT, "io.decoded-size-mismatch", null);
      size = Math.max(size, extent);
    }
  }

  /**
   * Narrow mutation adapter; production never weakens an atomic move to copy or ordinary rename.
   */
  static class FileActions {
    /** Probes the same-volume atomic primitive privately before any publication surface begins. */
    void probeAtomic(Path staging, boolean directory) throws IOException {
      Path source = staging.resolve("atomic-probe");
      Path target = staging.resolve("atomic-ready");
      if (directory) Files.createDirectory(source);
      else Files.createFile(source);
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Moves only to an absent name; replacement always backs up its predecessor separately. */
    void move(Path source, Path target) throws IOException {
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        throw new FileAlreadyExistsException(target.toString());
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Removes one owned file or an empty owned directory without traversing links. */
    void delete(Path path) throws IOException {
      Files.deleteIfExists(path);
    }

    /**
     * Reads a path's detached no-follow identity, as {@link PathIdentity#inspect} does. This is
     * publication's only identity read, including its target containment's, so identity changes are
     * injectable through the same seam as moves and deletions.
     *
     * <p>Parallel extraction calls this from private worker threads for their staged files, so an
     * override must be thread-safe.
     *
     * @return the entry's snapshot, or null when it or a parent does not exist
     * @throws IOException if the entry cannot be inspected
     * @throws UnsupportedOperationException if the host cannot establish a stable identity
     */
    PathIdentity.Snapshot inspect(Path path) throws IOException {
      return PathIdentity.inspect(path);
    }

    /**
     * Pins a destination directory, as {@link PathIdentity#pin} does. Target containment takes its
     * root (or new root's parent) pin here, so the pin and its release are injectable through the
     * same seam as every other identity read.
     *
     * @return the owned pin; target containment closes it
     * @throws IOException if the entry is absent or cannot be pinned
     * @throws UnsupportedOperationException if the host cannot establish a stable identity
     */
    PathIdentity.Pin pin(Path path) throws IOException {
      return PathIdentity.pin(path);
    }
  }

  /**
   * Mutable state belongs to one synchronous invocation; no callback may retain this owner. The
   * protocol is selected by dispatching on the sealed plan's case; the root shape is learned from
   * target preflight and stays private to this invocation.
   */
  private static final class Session {
    private final Path destination;
    private final TargetPolicy policy;
    private final ResourceLimits limits;
    private final FileActions files;
    private final Plan plan;

    /** The operation the plan publishes for: PACK for an archive set, EXTRACT for an extraction. */
    private final Operation operation;

    private final ResourceBudget budget;
    private final List<Part> parts = new ArrayList<>();
    private final LinkedHashMap<Path, Long> owned = new LinkedHashMap<>();
    private final List<ResourceBudget.Lease> credits = new ArrayList<>();
    private final OperationSession operationSession;
    private final Set<Path> retainedBackups = new HashSet<>();
    private Path staging;
    private TargetPaths containment;
    private Path stagedRoot;
    private boolean rootPublished;
    private boolean existingTree;

    /** Whether target preflight found no extraction root, so the whole tree commits in one move. */
    private boolean newRoot;

    private Part committing;
    private boolean commitInProgress;
    private final LinkedHashMap<Path, Long> createdDirectories = new LinkedHashMap<>();
    private final Set<Path> removedDirectories = new HashSet<>();
    private final LinkedHashMap<Path, Long> privateDirectories = new LinkedHashMap<>();
    private OperationPhase phase = OperationPhase.PREFLIGHT;

    /**
     * The location ordinal of each planned output, in plan order: the part index for an archive
     * set, the archive entry ordinal for extraction.
     */
    private final List<Long> plannedOrdinals;

    /** The ordinal of plan-level evidence, as {@link Plan#planOrdinal} defines it. */
    private final long planOrdinal;

    private long ordinal;

    /** Whether the caller already moved the operation's progress into PROCESSING (Q10). */
    private final boolean processingEntered;

    /** Whether the plan's source-close action has been attempted; it never runs twice. */
    private boolean sourceClosed;

    /**
     * Derives the strategy from the plan and validates programmer-owned arguments before observing
     * cancellation or touching the disk. The borrowed budget stays alive until the caller closes
     * it, after publication has settled cleanup.
     */
    Session(OperationSession session, ResourceBudget budget, Plan plan, FileActions files) {
      this.plan = Objects.requireNonNull(plan, "plan");
      this.files = Objects.requireNonNull(files, "files");
      this.budget = Objects.requireNonNull(budget, "budget");
      operationSession = Objects.requireNonNull(session, "operation");
      limits = budget.limits();
      Path target;
      switch (plan) {
        case Plan.ArchiveSet set -> {
          operation = Operation.PACK;
          target = set.destination();
          policy = set.policy();
          plannedOrdinals = partIndexes(set.writers().size());
          processingEntered = set.processingEntered();
        }
        case Plan.Extraction extraction -> {
          operation = Operation.EXTRACT;
          target = extraction.root();
          policy = extraction.policy();
          plannedOrdinals = extraction.entries().stream().map(Entry::archiveOrdinal).toList();
          processingEntered = false;
        }
      }
      destination = target.toAbsolutePath().normalize();
      planOrdinal = plan.planOrdinal();
      ordinal = planOrdinal;
    }

    /** The plan's writers, in plan order. */
    private List<Writer> writers() {
      return switch (plan) {
        case Plan.ArchiveSet set -> set.writers();
        case Plan.Extraction extraction ->
            extraction.entries().stream().map(Entry::writer).toList();
      };
    }

    /**
     * Runs the plan's source-close action once, the first time publication reaches the point before
     * its final commit. A second call is a no-op, and a failed close is not retried.
     */
    private void closeSource() throws IOException {
      if (sourceClosed) return;
      sourceClosed = true;
      plan.closeSource().close();
    }

    /**
     * Stages, commits, and cleans this publication's own staging, accepting every failure into the
     * operation session instead of throwing it. Only an {@link Error} other than {@link
     * AssertionError} escapes.
     *
     * @return every settled artifact, including residuals; publication cleaned exactly these, so
     *     their count is the operation's CLEANUP total
     */
    List<Artifact> run() {
      try {
        List<Writer> writers = writers();
        if (processingEntered)
          operationSession.checkpoint(OperationPhase.PROCESSING, OptionalLong.empty());
        else operationSession.ensurePreflight();
        if (writers.size() > limits.maxOutputs())
          throw context()
              .limit("maxOutputs", limits.maxOutputs(), Integer.toString(writers.size()));
        switch (plan) {
          case Plan.ArchiveSet set -> preflight(set);
          case Plan.Extraction extraction -> preflight(extraction);
        }
        checkpoint();
        if (!parts.isEmpty() || newRoot) {
          containment.recheckRoot(context());
          // A drive/share root has no parent; staging beside its planned files stays on that
          // volume.
          Path stagingParent =
              destination.getParent() == null ? destination : destination.getParent();
          staging = Files.createTempDirectory(stagingParent, ".jbsa-");
          own(staging, planOrdinal);
          own(staging.resolve("atomic-probe"), planOrdinal);
          own(staging.resolve("atomic-ready"), planOrdinal);
          files.probeAtomic(staging, newRoot);
          if (newRoot) {
            stagedRoot = Files.createDirectory(staging.resolve("tree"));
            own(stagedRoot, planOrdinal);
          }
          if (!processingEntered) {
            operationSession.completePhase();
            operationSession.processing(existingTree);
          }
          phase = OperationPhase.PROCESSING;
          if (plan instanceof Plan.Extraction extraction && extraction.parallel()) {
            stageParallel(extraction);
            // A worker failure must leave PROCESSING interrupted, without a synthetic terminal.
            checkpoint();
          } else {
            for (Part part : parts) {
              ordinal = part.ordinal;
              checkpoint();
              long logicalBytes = stage(part);
              if (plan instanceof Plan.Extraction extraction) {
                extraction.onStaged().accept(part.ordinal);
                operationSession.processedEntry(logicalBytes);
              }
              if (existingTree) {
                // The last per-file commit is the final Publication Commit.
                if (part.index == parts.size() - 1) closeSource();
                publishExisting(part);
              }
            }
          }
          if (!existingTree) {
            // Every stage has settled and nothing is visible yet; the set or root commit is final.
            closeSource();
            operationSession.completePhase();
            operationSession.publishing();
            phase = OperationPhase.PUBLISHING;
            for (Part part : parts) {
              ordinal = part.ordinal;
              recheck(part, false);
            }
            operationSession.beginCommit(phase, OptionalLong.of(ordinal));
            commitInProgress = true;
            if (stagedRoot != null) {
              // Located at the root commit: the last part's ordinal, in PUBLISHING.
              containment.recheckRoot(context());
              files.move(stagedRoot, destination);
              rootPublished = true;
              privateDirectories.forEach(
                  (path, index) ->
                      createdDirectories.put(
                          destination.resolve(stagedRoot.relativize(path)), index));
              owned.keySet().removeIf(path -> path.startsWith(stagedRoot));
              for (Part part : parts) {
                part.state = ArtifactState.PUBLISHED;
                releaseStage(part);
              }
            } else {
              for (Part part : parts) {
                ordinal = part.ordinal;
                publish(part);
              }
            }
            commitInProgress = false;
            operationSession.endCommit(true);
            operationSession.advance(ProgressMetric.ARTIFACTS, parts.size());
          }
        }
        if (parts.isEmpty() && !newRoot) {
          if (!processingEntered) {
            operationSession.completePhase();
            operationSession.processing(existingTree);
          }
          phase = OperationPhase.PROCESSING;
          // An empty plan still settles its source before its (empty) final commit.
          closeSource();
          operationSession.completePhase();
          if (!existingTree) {
            operationSession.publishing();
            phase = OperationPhase.PUBLISHING;
          }
          operationSession.beginCommit(phase, OptionalLong.empty());
          operationSession.endCommit(true);
        }
        operationSession.completePhase();
      } catch (OperationStopped stopped) {
        // A signal, not a failure: the session already holds what stopped publication.
        if (commitInProgress && stagedRoot == null) rollback();
      } catch (IOException | RuntimeException | AssertionError cause) {
        accept(cause);
        if (commitInProgress && stagedRoot == null) rollback();
      } finally {
        // Failures located from here on are CLEANUP failures. The session itself enters CLEANUP
        // and reports the count returned below once its operation template settles.
        phase = OperationPhase.CLEANUP;
        cleanup();
        settleTargetStates();
        if (containment != null) {
          try {
            containment.close();
          } catch (ArchiveException cause) {
            accept(cause);
          }
        }
        closeParallelOutcomes();
        credits.forEach(ResourceBudget.Lease::close);
      }
      return artifacts();
    }

    /**
     * Establishes an archive set's complete target plan before creating adjacent staging: one part
     * per writer at its split path, contained beside the destination.
     */
    private void preflight(Plan.ArchiveSet set) throws IOException {
      if (destination.getFileName() == null) {
        throw context().failure(FailureKind.POLICY, "destination.not-file", null);
      }
      reservePlan(set.writers().size(), List.of());
      for (int index = 0; index < set.writers().size(); index++)
        addPart(index, splitPath(destination, index + 1), set.writers().get(index));
      // Containment reads identities and pins through the fault seam too, so every publication
      // identity read and pin goes through FileActions.
      containment =
          TargetPaths.preflight(
              destination.getParent(),
              parts.stream().map(part -> part.target.getFileName().toString()).toList(),
              policy,
              context(),
              files);
    }

    /**
     * Establishes an extraction's complete target plan beneath its root before creating staging,
     * learns whether the root already exists (an existing tree) or is new, then counts the whole
     * selection as PREFLIGHT entries once the output count is known.
     */
    private void preflight(Plan.Extraction extraction) throws IOException {
      List<String> names = extraction.entries().stream().map(Entry::name).toList();
      reservePlan(extraction.entries().size(), names);
      // Containment reads identities and pins through the fault seam too, so every publication
      // identity read and pin goes through FileActions.
      containment = TargetPaths.preflight(destination, names, policy, context(), files);
      existingTree = containment.existingRoot();
      newRoot = !existingTree;
      for (int index = 0; index < extraction.entries().size(); index++)
        addPart(index, containment.targets().get(index), extraction.entries().get(index).writer());
      operationSession.advance(ProgressMetric.ENTRIES, names.size());
    }

    /**
     * Reserves the plan's bookkeeping: per-output state and name copies, plus pin handles, one
     * temporary inspection and its bounded UTF-16/native structures.
     */
    private void reservePlan(int outputs, List<String> names) throws ArchiveException {
      long planHeap = Math.multiplyExact((long) outputs, 512);
      for (String name : names) planHeap = Math.addExact(planHeap, (long) name.length() * 12);
      // Pin handles plus one temporary inspection and its bounded UTF-16/native structures.
      credits.add(budget.reserve(planHeap, 128 * 1024, 2, 0));
    }

    /** Plans one output at its plan position, recording whether a predecessor already exists. */
    private void addPart(int index, Path target, Writer writer) {
      boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
      parts.add(new Part(index, plannedOrdinals.get(index), target, writer, exists));
    }

    /**
     * Opens and closes one staged channel, returning its output extent as the extraction entry's
     * uncompressed byte contribution. Pack writers account for logical entries independently.
     */
    private long stage(Part part) throws IOException {
      part.staged =
          stagedRoot == null
              ? staging.resolve("part-" + part.ordinal)
              : stagedRoot.resolve(destination.relativize(part.target));
      if (stagedRoot != null) createPrivateParents(part.staged.getParent(), part.ordinal);
      ResourceBudget.Lease scratch = budget.reserve(0, 0, 0, 0);
      part.scratch = scratch;
      credits.add(scratch);
      long size;
      try (var handle = budget.reserve(0, 0, 1, 0);
          FileChannel channel =
              FileChannel.open(
                  part.staged,
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.READ)) {
        own(part.staged, part.ordinal);
        StagedFile output = new StagedFile(channel, scratch, context(), this);
        part.writer.write(output);
        if (channel.size() < output.size())
          ExactIo.write(channel, output.size() - 1, ByteBuffer.wrap(new byte[1]), context());
        size = output.size();
      }
      // Readback needs a closed write handle under Windows sharing rules, but must precede commit.
      part.writer.validate(part.staged);
      part.stagedIdentity = files.inspect(part.staged);
      return size;
    }

    /**
     * Admits only bounded private stages of an extraction and consumes their outcomes in plan
     * order.
     */
    private void stageParallel(Plan.Extraction extraction) throws IOException {
      // The runner numbers work by plan position; its own failures are located by archive ordinal.
      try (var runner =
          new OrderedWorkerRunner<StagedResult>(
              operationSession,
              extraction.workers(),
              parts.size(),
              budget,
              context(),
              index -> processing(parts.get(index)))) {
        int nextToSubmit = 0;
        int nextToConsume = 0;
        while (nextToSubmit < parts.size() || runner.hasPending()) {
          while (nextToSubmit < parts.size() && !runner.stopped()) {
            Part candidate = parts.get(nextToSubmit);
            ordinal = candidate.ordinal;
            checkpoint();
            prepareParallelStage(candidate);
            long extent = extraction.entries().get(nextToSubmit).stagedExtent();
            var cost = new OrderedWorkerRunner.Cost(65536, 0, 1, extent);
            if (!runner.trySubmit(
                nextToSubmit, cost, (stop, lease) -> stageWorker(candidate, stop, lease, extent)))
              break;
            nextToSubmit++;
          }
          if (!runner.hasPending()) break;
          long waitingFor = parts.get(nextToConsume).ordinal;
          var outcome =
              runner.takeNext(
                  () ->
                      operationSession.checkpoint(
                          OperationPhase.PROCESSING, OptionalLong.of(waitingFor)));
          nextToConsume++;
          Part part = parts.get(outcome.ordinal());
          ordinal = part.ordinal;
          if (outcome.failure() != null) {
            // Located at the part, where publication now is: PROCESSING at its archive ordinal.
            accept(outcome.failure());
            outcome.close();
            runner.stop();
            continue;
          }
          if (outcome.skipped() || operationSession.failed()) {
            outcome.close();
            continue;
          }
          part.parallelOutcome = outcome;
          part.stagedIdentity = outcome.result().identity();
          extraction.onStaged().accept(part.ordinal);
          operationSession.processedEntry(outcome.result().size());
          if (existingTree) {
            if (part.index == parts.size() - 1) {
              // No source handle may close while an admitted sibling is still using it.
              runner.close();
              closeSource();
            }
            publishExisting(part);
          }
        }
        // A new root closes its source after this method returns, once closing the runner has
        // joined every worker that could still be reading it.
      }
    }

    /** Assigns a private staged path and cleanup owner before its worker may create the file. */
    private void prepareParallelStage(Part part) throws IOException {
      if (part.staged != null) return;
      part.staged =
          stagedRoot == null
              ? staging.resolve("part-" + part.ordinal)
              : stagedRoot.resolve(destination.relativize(part.target));
      if (stagedRoot != null) createPrivateParents(part.staged.getParent(), part.ordinal);
      own(part.staged, part.ordinal);
    }

    /**
     * Writes and validates one staged file while keeping all transaction maps on the coordinator.
     */
    private StagedResult stageWorker(
        Part part, OrderedWorkerRunner.Checkpoint stop, ResourceBudget.Lease lease, long extent)
        throws IOException {
      IoContext location = processing(part);
      boolean handleAndValidationClosed = false;
      try {
        long size;
        try (FileChannel channel =
            FileChannel.open(
                part.staged,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ)) {
          StagedFile output = new StagedFile(channel, lease, location, this, stop, extent);
          part.writer.write(output);
          if (channel.size() < output.size())
            ExactIo.write(channel, output.size() - 1, ByteBuffer.wrap(new byte[1]), location);
          size = output.size();
        }
        // Readback and identity inspection happen after the write handle closes on Windows.
        part.writer.validate(part.staged);
        // On this worker thread too, identity reads go through the (thread-safe) fault seam.
        StagedResult result = new StagedResult(size, files.inspect(part.staged));
        handleAndValidationClosed = true;
        return result;
      } catch (IOException failure) {
        // A worker stop, including one from the copy loop's own checkpoint, is rethrown unchanged.
        throw location.structure(failure);
      } finally {
        // Keep the credit on an uncertain close or validation failure until worker settlement.
        if (handleAndValidationClosed) lease.releaseHandles(1);
      }
    }

    /** Returns the PROCESSING location of one part's stage, which is a destination. */
    private IoContext processing(Part part) {
      return IoContext.destination(destination, operation)
          .at(OperationPhase.PROCESSING, OptionalLong.of(part.ordinal));
    }

    /** Commits one existing-tree leaf after its ordered stage and progress have settled. */
    private void publishExisting(Part part) throws IOException {
      checkpoint();
      prepareParents(part);
      operationSession.beginCommit(phase, OptionalLong.of(ordinal));
      phase = OperationPhase.PUBLISHING;
      committing = part;
      commitInProgress = true;
      publish(part);
      commitInProgress = false;
      operationSession.endCommit(part.index == parts.size() - 1);
      committing = null;
      phase = OperationPhase.PROCESSING;
      operationSession.advance(ProgressMetric.ARTIFACTS, 1);
    }

    /** Releases a staged result only after publication no longer needs its scratch credit. */
    private void releaseStage(Part part) throws IOException {
      if (part.scratch != null) part.scratch.close();
      if (part.parallelOutcome != null) {
        part.parallelOutcome.close();
        part.parallelOutcome = null;
      }
    }

    /** Returns uncommitted worker credits after private paths are deleted during cleanup. */
    private void closeParallelOutcomes() {
      for (Part part : parts) {
        if (part.parallelOutcome == null) continue;
        ordinal = part.ordinal;
        try {
          part.parallelOutcome.close();
          part.parallelOutcome = null;
        } catch (IOException failure) {
          accept(failure);
        }
      }
    }

    /** Installs a fully staged artifact with no implicit replacement semantics. */
    private void publish(Part part) throws IOException {
      recheck(part, false);
      if (part.predecessor) {
        part.backupIdentity = files.inspect(part.target);
        part.backup = staging.resolve("backup-" + part.ordinal);
        ResourceBudget.Lease backupCredit = budget.reserve(0, 0, 0, Files.size(part.target));
        credits.add(backupCredit);
        files.move(part.target, part.backup);
        own(part.backup, part.ordinal);
        part.backedUp = true;
        part.state = ArtifactState.MISSING;
      }
      recheck(part, true);
      files.move(part.staged, part.target);
      owned.remove(part.staged);
      part.state = ArtifactState.PUBLISHED;
      releaseStage(part);
    }

    /**
     * Rechecks one part's containment at the current phase and ordinal, so a failure is located at
     * the actual publication boundary rather than at preflight.
     *
     * @param parentsOnly whether to skip the target's own identity, as install after a backup does
     */
    private void recheck(Part part, boolean parentsOnly) throws IOException {
      if (parentsOnly) containment.recheckParents(part.target, context());
      else containment.recheck(part.target, context());
    }

    /**
     * Stops the set and attempts every predecessor restoration, even after another rollback fails.
     */
    private void rollback() {
      for (Part part : parts) {
        if (existingTree && part != committing) continue;
        ordinal = part.ordinal;
        try {
          recheck(part, true);
          if (part.state == ArtifactState.PUBLISHED) {
            var current = files.inspect(part.target);
            if (!Objects.equals(current, part.stagedIdentity)) {
              part.state = current == null ? ArtifactState.MISSING : ArtifactState.UNCHANGED;
              throw context().roleFailure("destination.changed-during-rollback", null);
            }
            files.delete(part.target);
            part.state = ArtifactState.MISSING;
          }
          if (part.backedUp) {
            if (!Objects.equals(part.backupIdentity, files.inspect(part.backup))) {
              throw context().roleFailure("destination.changed-backup", null);
            }
            files.move(part.backup, part.target);
            owned.remove(part.backup);
            part.backedUp = false;
            part.state = ArtifactState.RESTORED;
          }
        } catch (IOException | UnsupportedOperationException | SecurityException cause) {
          // Failed restoration leaves the caller's only predecessor copy under private ownership.
          if (part.backedUp) retainedBackups.add(part.backup);
          accept(cause);
        }
      }
    }

    /** Records an exact path as soon as its ownership transfers to the operation. */
    private void own(Path path, long artifactOrdinal) {
      owned.put(path, artifactOrdinal);
    }

    /** Deletes children before parents and retains every exact unremoved path for the caller. */
    private void cleanup() {
      for (Path path : new ArrayList<>(owned.keySet()).reversed()) {
        if (retainedBackups.contains(path)) continue;
        try {
          files.delete(path);
          owned.remove(path);
        } catch (IOException | SecurityException cause) {
          accept(cause);
        }
      }
      if (operationSession.failed()) {
        for (Path path : new ArrayList<>(createdDirectories.keySet()).reversed()) {
          try {
            files.delete(path);
            removedDirectories.add(path);
          } catch (DirectoryNotEmptyException retained) {
            // Committed siblings keep their parent directories in the caller's destination tree.
          } catch (IOException | SecurityException cause) {
            accept(cause);
          }
        }
      }
    }

    /** Creates only preflighted missing parents, recording ownership before identity admission. */
    private void prepareParents(Part part) throws IOException {
      recheck(part, false);
      List<Path> missing = new ArrayList<>();
      for (Path path = part.target.getParent();
          !path.equals(destination);
          path = path.getParent()) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) missing.add(path);
      }
      for (Path path : missing.reversed()) {
        Files.createDirectory(path);
        createdDirectories.put(path, part.ordinal);
        containment.createdDirectory(path, context());
      }
    }

    /**
     * Reports observed existence after rollback without attributing an external replacement to us.
     */
    private void settleTargetStates() {
      if (containment == null || (stagedRoot != null && !rootPublished)) return;
      for (Part part : parts) {
        ordinal = part.ordinal;
        if (removedDirectories.stream().anyMatch(part.target::startsWith)) continue;
        try {
          if (stagedRoot == null) recheck(part, true);
          var current = files.inspect(part.target);
          if (current == null) part.state = ArtifactState.MISSING;
          else if (part.state == ArtifactState.MISSING
              || (part.state == ArtifactState.PUBLISHED && !current.equals(part.stagedIdentity))
              || (part.state == ArtifactState.RESTORED && !current.equals(part.backupIdentity))) {
            part.state = ArtifactState.UNCHANGED;
          }
        } catch (IOException | UnsupportedOperationException | SecurityException cause) {
          accept(cause);
        }
      }
    }

    /**
     * Builds a private tree from already validated relative names, without recursive link walks.
     */
    private void createPrivateParents(Path parent, long index) throws IOException {
      if (parent.equals(stagedRoot) || owned.containsKey(parent)) return;
      createPrivateParents(parent.getParent(), index);
      Files.createDirectory(parent);
      own(parent, index);
      privateDirectories.put(parent, index);
    }

    /** Samples explicit cancellation only before a publication surface begins. */
    private void checkpoint() throws OperationStopped {
      operationSession.checkpoint(phase, OptionalLong.of(ordinal));
    }

    /**
     * Accepts a newly observed publication failure into the session. Cleanup never displaces an
     * accepted primary failure or exceeds secondary retention. A stop signal admits nothing, and an
     * error other than an assertion propagates.
     */
    private void accept(Throwable cause) {
      try {
        operationSession.accept(structured(cause));
      } catch (StopSignal stopped) {
        // A signal, not a failure: whatever stopped this work already holds its evidence.
      }
    }

    /**
     * Structures a publication failure at the current location through the entry. A missing
     * filesystem capability is CAPABILITY and a denied access is the destination's I/O failure. A
     * resource-limit failure the shared budget raised at its own phase is moved to the current
     * phase, ordinal and location.
     *
     * @throws StopSignal the cause itself, when it is a stop
     */
    private ArchiveException structured(Throwable cause) throws StopSignal {
      ArchiveException failure;
      if (cause instanceof UnsupportedOperationException
          || cause instanceof AtomicMoveNotSupportedException)
        failure = context().failure(FailureKind.CAPABILITY, "operation.destination-io", cause);
      // The entry would read a denied access as INTERNAL; it is the destination's I/O failure.
      else if (cause instanceof SecurityException denied) failure = context().ioFailure(denied);
      else failure = context().structure(cause);
      String identifier = failure.primaryFailure().diagnosticIdentifier().orElse("");
      if ((identifier.equals("operation.resource-limit")
              || identifier.equals("io.resource-capacity"))
          && failure.primaryFailure().phase() != phase) {
        // The shared budget owns credits, while the admission site owns the current phase/ordinal.
        failure = context().relocated(failure);
      }
      return failure;
    }

    /** Reports planned artifacts first and residual paths next within their logical ordinal. */
    private List<Artifact> artifacts() {
      List<Artifact> result = new ArrayList<>();
      for (Part part : parts) result.add(new Artifact(part.target, part.ordinal, part.state));
      owned.forEach(
          (path, index) -> result.add(new Artifact(path, index, ArtifactState.RESIDUAL_STAGING)));
      createdDirectories.forEach(
          (path, index) ->
              result.add(
                  new Artifact(
                      path,
                      index,
                      removedDirectories.contains(path)
                          ? ArtifactState.MISSING
                          : ArtifactState.PUBLISHED)));
      if (stagedRoot != null)
        result.add(
            new Artifact(
                destination,
                planOrdinal,
                rootPublished ? ArtifactState.PUBLISHED : ArtifactState.MISSING));
      result.sort(Comparator.comparingLong(Artifact::ordinal));
      return List.copyOf(result);
    }

    /**
     * Supplies stable phase and ordinal evidence without provider exception text. Everything
     * publication touches, including its staging and readback, is a destination.
     */
    private IoContext context() {
      return IoContext.destination(destination, operation).at(phase, OptionalLong.of(ordinal));
    }
  }

  /** Computes the permanent split-name rule, including leading and trailing full stops. */
  public static Path splitPath(Path destination, int number) {
    if (number < 1) throw new IllegalArgumentException("Part numbers start at one");
    if (number == 1) return destination;
    String name = destination.getFileName().toString();
    int dot = name.lastIndexOf('.');
    if (dot < 0) dot = name.length();
    return destination.resolveSibling(name.substring(0, dot) + number + name.substring(dot));
  }

  /**
   * Numbers an archive set's outputs by part index, which is both their plan position and their
   * reported location and Artifact ordinal.
   */
  private static List<Long> partIndexes(int count) {
    List<Long> indexes = new ArrayList<>(count);
    for (long index = 0; index < count; index++) indexes.add(index);
    return indexes;
  }

  /** Immutable worker evidence; its staged-file credits belong to the containing Outcome. */
  private record StagedResult(long size, PathIdentity.Snapshot identity) implements AutoCloseable {
    /** The coordinator owns the staged path and the Outcome owns every live resource credit. */
    @Override
    public void close() {
      // Immutable evidence has no separate resource to release.
    }
  }

  /** One target's retained in-process publication history. */
  private static final class Part {
    /** Position in the plan; it orders work and identifies the last output. */
    final int index;

    /**
     * Location and Artifact ordinal: the part index for an archive set, the archive entry ordinal
     * for extraction. The two differ for a sparse extraction selection.
     */
    final long ordinal;

    final Path target;
    final Writer writer;
    final boolean predecessor;
    Path staged;
    Path backup;
    PathIdentity.Snapshot stagedIdentity;
    PathIdentity.Snapshot backupIdentity;
    boolean backedUp;
    ResourceBudget.Lease scratch;
    OrderedWorkerRunner.Outcome<StagedResult> parallelOutcome;
    ArtifactState state;

    /** Starts with the observed target state, before destination effects. */
    Part(int index, long ordinal, Path target, Writer writer, boolean predecessor) {
      this.index = index;
      this.ordinal = ordinal;
      this.target = target;
      this.writer = writer;
      this.predecessor = predecessor;
      state = predecessor ? ArtifactState.UNCHANGED : ArtifactState.MISSING;
    }
  }
}
