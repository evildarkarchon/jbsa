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
      throws ArchiveException {
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
    // Production filesystem actions: publication reads identities only through FileActions.
    try (TargetPaths checked =
        TargetPaths.preflightWith(
            target.getParent(), names, policy, context, new FileActions()::inspect)) {
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
     * A finalized archive-part plan, published as one atomic set. Every part is staged before any
     * move. Stabilization that determines this plan must finish in operation-owned scratch first.
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
    public void reserve(long extent) throws ArchiveException {
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
        throw context.failure(FailureKind.DESTINATION, "io.invalid-output-span", cause);
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
    public void completedEntry(long decodedBytes) throws ArchiveException {
      if (owner.extraction != null)
        throw new IllegalStateException("Extraction counts each writer once");
      owner.operationSession.processedEntry(decodedBytes);
    }

    /**
     * Admits a warning before retention and stops staging immediately if request policy rejects it.
     */
    public void diagnostic(Diagnostic diagnostic) throws ArchiveException {
      owner.operationSession.diagnostic(diagnostic);
    }

    /** Observes explicit stop state around codec calls and other bounded non-I/O work. */
    public void checkpoint() throws ArchiveException {
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
  }

  /**
   * Mutable state belongs to one synchronous invocation; no callback may retain this owner. The
   * strategy flags below are derived from the plan once and stay private to this invocation.
   */
  private static final class Session {
    private final Path destination;
    private final TargetPolicy policy;
    private final ResourceLimits limits;
    private final FileActions files;
    private final Plan plan;

    /** The extraction plan, or null for an archive set. */
    private final Plan.Extraction extraction;

    /** Whether private workers stage the extraction; publication decides from the plan. */
    private final boolean parallel;

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

    /**
     * The ordinal of plan-level evidence that precedes or spans every output: target preflight,
     * private staging, and a new extraction root. It is the first planned output's ordinal, or 0
     * for an empty plan, so a sparse extraction never reports an unselected entry's ordinal.
     */
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
    Session(OperationSession operation, ResourceBudget budget, Plan plan, FileActions files) {
      this.plan = Objects.requireNonNull(plan, "plan");
      this.files = Objects.requireNonNull(files, "files");
      this.budget = Objects.requireNonNull(budget, "budget");
      operationSession = Objects.requireNonNull(operation, "operation");
      limits = budget.limits();
      extraction = plan instanceof Plan.Extraction selected ? selected : null;
      Path target;
      if (plan instanceof Plan.ArchiveSet set) {
        target = set.destination();
        policy = set.policy();
        plannedOrdinals = partIndexes(set.writers().size());
        processingEntered = set.processingEntered();
        parallel = false;
      } else {
        target = extraction.root();
        policy = extraction.policy();
        plannedOrdinals = extraction.entries().stream().map(Entry::archiveOrdinal).toList();
        processingEntered = false;
        // One worker, or nothing to stage, keeps every stage on the caller thread.
        parallel = !extraction.entries().isEmpty() && extraction.workers().workers() > 1;
      }
      destination = target.toAbsolutePath().normalize();
      planOrdinal = plannedOrdinals.isEmpty() ? 0 : plannedOrdinals.getFirst();
      ordinal = planOrdinal;
    }

    /** The plan's writers, in plan order. */
    private List<Writer> writers() {
      return extraction == null
          ? ((Plan.ArchiveSet) plan).writers()
          : extraction.entries().stream().map(Entry::writer).toList();
    }

    /** The extraction's validated names, in plan order; an archive set derives its own. */
    private List<String> names() {
      return extraction == null
          ? List.of()
          : extraction.entries().stream().map(Entry::name).toList();
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
        List<String> names = names();
        if (processingEntered)
          operationSession.checkpoint(OperationPhase.PROCESSING, OptionalLong.empty());
        else operationSession.ensurePreflight();
        if (writers.size() > limits.maxOutputs())
          throw context()
              .limit("maxOutputs", limits.maxOutputs(), Integer.toString(writers.size()));
        preflight(writers, names);
        boolean newRoot = extraction != null && !existingTree;
        if (extraction != null) operationSession.advance(ProgressMetric.ENTRIES, names.size());
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
          if (!parallel) {
            for (Part part : parts) {
              ordinal = part.ordinal;
              checkpoint();
              long logicalBytes = stage(part);
              if (extraction != null) {
                extraction.onStaged().accept(part.ordinal);
                operationSession.processedEntry(logicalBytes);
              }
              if (existingTree) {
                // The last per-file commit is the final Publication Commit.
                if (part.index == parts.size() - 1) closeSource();
                publishExisting(part);
              }
            }
          } else {
            stageParallel();
            // A worker failure must leave PROCESSING interrupted, without a synthetic terminal.
            checkpoint();
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
      } catch (IOException | UnsupportedOperationException | SecurityException cause) {
        unwound(cause);
        if (commitInProgress && stagedRoot == null) rollback();
      } catch (RuntimeException | AssertionError cause) {
        accept(context().failure(FailureKind.INTERNAL, "operation.internal-failure", cause));
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

    /** Establishes the complete target plan before creating adjacent staging. */
    private void preflight(List<Writer> writers, List<String> names) throws IOException {
      if (extraction == null && destination.getFileName() == null) {
        throw context().failure(FailureKind.POLICY, "destination.not-file", null);
      }
      long planHeap = Math.multiplyExact((long) writers.size(), 512);
      for (String name : names) planHeap = Math.addExact(planHeap, (long) name.length() * 12);
      // Pin handles plus one temporary inspection and its bounded UTF-16/native structures.
      credits.add(budget.reserve(planHeap, 128 * 1024, 2, 0));
      // Containment reads identities through the fault seam too, so every publication identity
      // read goes through FileActions.
      if (extraction != null) {
        containment =
            TargetPaths.preflightWith(destination, names, policy, context(), files::inspect);
        existingTree = containment.existingRoot();
      }
      for (int index = 0; index < writers.size(); index++) {
        Path target =
            extraction != null
                ? containment.targets().get(index)
                : splitPath(destination, index + 1);
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        parts.add(new Part(index, plannedOrdinals.get(index), target, writers.get(index), exists));
      }
      if (extraction == null)
        containment =
            TargetPaths.preflightWith(
                destination.getParent(),
                parts.stream().map(part -> part.target.getFileName().toString()).toList(),
                policy,
                context(),
                files::inspect);
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

    /** Admits only bounded private stages and consumes their outcomes in plan order. */
    private void stageParallel() throws IOException {
      // The runner numbers work by plan position; its own failures are located by archive ordinal.
      try (var runner =
          new OrderedWorkerRunner<StagedResult>(
              extraction.workers(),
              parts.size(),
              budget,
              context(),
              index -> parts.get(index).ordinal)) {
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
            acceptWorkerFailure(part, outcome.failure());
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
      IoContext location =
          new IoContext(
              destination,
              Operation.EXTRACT,
              OperationPhase.PROCESSING,
              OptionalLong.of(part.ordinal));
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
      } catch (ArchiveException failure) {
        throw failure;
      } catch (IOException failure) {
        throw location.failure(FailureKind.DESTINATION, "operation.destination-io", failure);
      } finally {
        // Keep the credit on an uncertain close or validation failure until worker settlement.
        if (handleAndValidationClosed) lease.releaseHandles(1);
      }
    }

    /**
     * Preserves worker failure semantics while locating unexpected faults at their plan ordinal.
     */
    private void acceptWorkerFailure(Part part, Throwable cause) {
      if (cause instanceof VirtualMachineError fatal) throw fatal;
      if (cause instanceof ThreadDeath fatal) throw fatal;
      IoContext location =
          new IoContext(
              destination,
              Operation.EXTRACT,
              OperationPhase.PROCESSING,
              OptionalLong.of(part.ordinal));
      accept(
          cause instanceof ArchiveException failure
              ? failure
              : location.failure(
                  cause instanceof IOException ? FailureKind.DESTINATION : FailureKind.INTERNAL,
                  cause instanceof IOException
                      ? "operation.destination-io"
                      : "operation.internal-failure",
                  cause));
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
    private void recheck(Part part, boolean parentsOnly) throws ArchiveException {
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
              throw context()
                  .failure(FailureKind.DESTINATION, "destination.changed-during-rollback", null);
            }
            files.delete(part.target);
            part.state = ArtifactState.MISSING;
          }
          if (part.backedUp) {
            if (!Objects.equals(part.backupIdentity, files.inspect(part.backup))) {
              throw context().failure(FailureKind.DESTINATION, "destination.changed-backup", null);
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
    private void checkpoint() throws ArchiveException {
      operationSession.checkpoint(phase, OptionalLong.of(ordinal));
    }

    /** Cleanup never displaces an accepted primary failure or exceeds secondary retention. */
    private void accept(Throwable cause) {
      operationSession.accept(structured(cause));
    }

    /**
     * Settles the failure that ended staging or commit. It may be the session's own raised failure,
     * which the session already retains, so it goes through {@link OperationSession#unwound}.
     */
    private void unwound(Throwable cause) {
      operationSession.unwound(structured(cause));
    }

    /**
     * Structures a publication failure: a non-archive throwable becomes {@code
     * operation.destination-io} at the current location, and a resource-limit failure raised in
     * another phase is relocated to the current phase and ordinal.
     */
    private ArchiveException structured(Throwable cause) {
      ArchiveException failure =
          cause instanceof ArchiveException archive
              ? archive
              : context()
                  .failure(
                      cause instanceof UnsupportedOperationException
                              || cause instanceof AtomicMoveNotSupportedException
                          ? FailureKind.CAPABILITY
                          : FailureKind.DESTINATION,
                      "operation.destination-io",
                      cause);
      String identifier = failure.primaryFailure().diagnosticIdentifier().orElse("");
      if ((identifier.equals("operation.resource-limit")
              || identifier.equals("io.resource-capacity"))
          && failure.primaryFailure().phase() != phase) {
        // The shared budget owns credits, while the admission site owns the current phase/ordinal.
        var location =
            new DiagnosticLocation(
                Optional.empty(),
                OptionalLong.of(ordinal),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(context().path()));
        var previous = failure.primaryFailure();
        var primary =
            new Failure(
                previous.kind(),
                phase,
                OptionalLong.of(ordinal),
                previous.diagnosticIdentifier(),
                Optional.of(location),
                previous.cause());
        List<Diagnostic> diagnostics =
            failure.diagnostics().stream()
                .map(
                    diagnostic ->
                        new Diagnostic(
                            diagnostic.identifier(),
                            diagnostic.severity(),
                            diagnostic.operation(),
                            phase,
                            location,
                            diagnostic.values(),
                            diagnostic.explanation()))
                .toList();
        failure =
            new ArchiveException(
                failure.getMessage(),
                primary,
                diagnostics,
                failure.artifacts(),
                failure.assessment(),
                failure.secondaryFailures());
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

    /** Supplies stable phase and ordinal evidence without provider exception text. */
    private IoContext context() {
      return new IoContext(
          destination,
          extraction != null ? Operation.EXTRACT : Operation.PACK,
          phase,
          OptionalLong.of(ordinal));
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
