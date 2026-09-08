package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/**
 * Synchronous, operation-owned staging and atomic publication beneath the archive format writers.
 * Writers borrow one bounded positional output at a time and must not retain it after returning.
 */
public final class PublicationTransaction {
  private PublicationTransaction() {}

  /**
   * Publishes a finalized archive-part plan. Stabilization that determines this plan must finish in
   * operation-owned scratch before calling this method. All parts are staged before any move.
   */
  public static List<Artifact> archives(
      Path destination,
      List<Writer> writers,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationControl control)
      throws ArchiveException {
    return archives(destination, writers, policy, limits, control, new FileActions());
  }

  /** Supplies the filesystem mutation boundary for deterministic publication and cleanup faults. */
  static List<Artifact> archives(
      Path destination,
      List<Writer> writers,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationControl control,
      FileActions files)
      throws ArchiveException {
    Session session =
        new Session(
            destination,
            policy,
            limits,
            new OperationSession(Operation.PACK, limits, control),
            files,
            false);
    return session.run(List.copyOf(writers), List.of()).artifacts();
  }

  /** Continues source preflight evidence and returns the complete report for an archive set. */
  public static OperationReport archives(
      Path destination,
      List<Writer> writers,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationSession operation)
      throws ArchiveException {
    return new Session(destination, policy, limits, operation, new FileActions(), false)
        .run(List.copyOf(writers), List.of());
  }

  /**
   * Publishes validated selected names as one new root or ordered per-file existing-tree commits.
   */
  public static List<Artifact> extract(
      Path root,
      List<Entry> entries,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationControl control)
      throws ArchiveException {
    return extract(root, entries, policy, limits, control, new FileActions());
  }

  /** Supplies the same mutation fault boundary for both extraction publication surfaces. */
  static List<Artifact> extract(
      Path root,
      List<Entry> entries,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationControl control,
      FileActions files)
      throws ArchiveException {
    List<Entry> selection = List.copyOf(entries);
    return new Session(
            root,
            policy,
            limits,
            new OperationSession(Operation.EXTRACT, limits, control),
            files,
            true)
        .run(
            selection.stream().map(Entry::writer).toList(),
            selection.stream().map(Entry::name).toList())
        .artifacts();
  }

  /**
   * Continues source assessment and diagnostic policy through staging and publication settlement.
   */
  public static OperationReport extract(
      Path root,
      List<Entry> entries,
      TargetPolicy policy,
      ResourceLimits limits,
      OperationSession operation)
      throws ArchiveException {
    List<Entry> selection = List.copyOf(entries);
    return new Session(root, policy, limits, operation, new FileActions(), true)
        .run(
            selection.stream().map(Entry::writer).toList(),
            selection.stream().map(Entry::name).toList());
  }

  /** A complete decoded entry name and its synchronous content writer in Logical Plan Order. */
  public record Entry(String name, Writer writer) {
    /** Requires real decoded spelling and a writer; absent identities fail extraction preflight. */
    public Entry {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(writer, "writer");
    }
  }

  /** A format writer that fills or backpatches one borrowed, bounded staged file. */
  @FunctionalInterface
  public interface Writer {
    /** Writes the complete artifact; checked semantic failures retain their original evidence. */
    void write(StagedFile output) throws IOException;
  }

  /**
   * A positional output whose retained extent is admitted before any write or sparse reservation.
   */
  public static final class StagedFile {
    private final FileChannel channel;
    private final ResourceBudget.Lease scratch;
    private final IoContext context;
    private final Session owner;
    private long size;

    /** Borrows a single channel and its operation-owned extent reservation. */
    private StagedFile(
        FileChannel channel, ResourceBudget.Lease scratch, IoContext context, Session owner) {
      this.channel = channel;
      this.scratch = scratch;
      this.context = context;
      this.owner = owner;
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
      reserve(end);
      while (bytes.hasRemaining()) {
        owner.checkpoint();
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
      if (owner.extraction) throw new IllegalStateException("Extraction counts each writer once");
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
  }

  /** Mutable state belongs to one synchronous invocation; no callback may retain this owner. */
  private static final class Session {
    private final Path destination;
    private final TargetPolicy policy;
    private final ResourceLimits limits;
    private final FileActions files;
    private final boolean extraction;
    private final ResourceBudget budget;
    private final List<Part> parts = new ArrayList<>();
    private final LinkedHashMap<Path, Long> owned = new LinkedHashMap<>();
    private final List<ResourceBudget.Lease> credits = new ArrayList<>();
    private final OperationSession operationSession;
    private final Set<Path> retainedBackups = new HashSet<>();
    private Path staging;
    private ExtractionPaths containment;
    private Path stagedRoot;
    private boolean rootPublished;
    private boolean existingTree;
    private Part committing;
    private boolean commitInProgress;
    private final LinkedHashMap<Path, Long> createdDirectories = new LinkedHashMap<>();
    private final Set<Path> removedDirectories = new HashSet<>();
    private final LinkedHashMap<Path, Long> privateDirectories = new LinkedHashMap<>();
    private OperationPhase phase = OperationPhase.PREFLIGHT;
    private long ordinal;

    /** Validates programmer-owned arguments before observing cancellation or touching the disk. */
    Session(
        Path destination,
        TargetPolicy policy,
        ResourceLimits limits,
        OperationSession operation,
        FileActions files,
        boolean extraction) {
      this.destination =
          Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
      this.policy = Objects.requireNonNull(policy, "policy");
      this.limits = Objects.requireNonNull(limits, "limits");
      this.files = Objects.requireNonNull(files, "files");
      this.extraction = extraction;
      operationSession = Objects.requireNonNull(operation, "operation");
      budget = ResourceBudget.forMutation(limits, context());
    }

    /** Settles cleanup before returning evidence, preserving the first accepted failure. */
    OperationReport run(List<Writer> writers, List<String> names) throws ArchiveException {
      try {
        operationSession.ensurePreflight();
        if (writers.size() > limits.maxOutputs())
          throw context()
              .limit("maxOutputs", limits.maxOutputs(), Integer.toString(writers.size()));
        preflight(writers, names);
        if (extraction) operationSession.advance(ProgressMetric.ENTRIES, names.size());
        checkpoint();
        if (!parts.isEmpty() || (extraction && !existingTree)) {
          containment.recheckRoot();
          // A drive/share root has no parent; staging beside its planned files stays on that
          // volume.
          Path stagingParent =
              destination.getParent() == null ? destination : destination.getParent();
          staging = Files.createTempDirectory(stagingParent, ".jbsa-");
          own(staging, 0);
          own(staging.resolve("atomic-probe"), 0);
          own(staging.resolve("atomic-ready"), 0);
          files.probeAtomic(staging, extraction && !existingTree);
          if (extraction && !existingTree) {
            stagedRoot = Files.createDirectory(staging.resolve("tree"));
            own(stagedRoot, 0);
          }
          operationSession.completePhase();
          operationSession.processing(existingTree);
          phase = OperationPhase.PROCESSING;
          for (Part part : parts) {
            ordinal = part.ordinal;
            checkpoint();
            long logicalBytes = stage(part);
            if (extraction) {
              operationSession.processedEntry(logicalBytes);
            }
            if (existingTree) {
              checkpoint();
              prepareParents(part);
              operationSession.beginCommit(phase, OptionalLong.of(ordinal));
              phase = OperationPhase.PUBLISHING;
              committing = part;
              commitInProgress = true;
              publish(part);
              commitInProgress = false;
              operationSession.endCommit(part.ordinal == parts.size() - 1);
              committing = null;
              phase = OperationPhase.PROCESSING;
              operationSession.advance(ProgressMetric.ARTIFACTS, 1);
            }
          }
          if (!existingTree) {
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
              containment.recheckRoot();
              files.move(stagedRoot, destination);
              rootPublished = true;
              privateDirectories.forEach(
                  (path, index) ->
                      createdDirectories.put(
                          destination.resolve(stagedRoot.relativize(path)), index));
              owned.keySet().removeIf(path -> path.startsWith(stagedRoot));
              for (Part part : parts) {
                part.state = ArtifactState.PUBLISHED;
                part.scratch.close();
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
        if (parts.isEmpty() && !(extraction && !existingTree)) {
          operationSession.completePhase();
          operationSession.processing(existingTree);
          phase = OperationPhase.PROCESSING;
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
        accept(cause);
        if (commitInProgress && stagedRoot == null) rollback();
      } catch (RuntimeException | AssertionError cause) {
        accept(context().failure(FailureKind.INTERNAL, "operation.internal-failure", cause));
        if (commitInProgress && stagedRoot == null) rollback();
      } finally {
        phase = OperationPhase.CLEANUP;
        operationSession.cleanup();
        cleanup();
        settleTargetStates();
        if (containment != null) {
          try {
            containment.close();
          } catch (ArchiveException cause) {
            accept(cause);
          }
        }
        credits.forEach(ResourceBudget.Lease::close);
        budget.close();
        operationSession.cleaned(artifacts().size());
      }
      List<Artifact> artifacts = artifacts();
      return operationSession.finish(artifacts);
    }

    /** Establishes the complete target plan before creating adjacent staging. */
    private void preflight(List<Writer> writers, List<String> names) throws IOException {
      if (!extraction && destination.getFileName() == null) {
        throw context().failure(FailureKind.POLICY, "destination.not-file", null);
      }
      long planHeap = Math.multiplyExact((long) writers.size(), 512);
      for (String name : names) planHeap = Math.addExact(planHeap, (long) name.length() * 12);
      // Pin handles plus one temporary inspection and its bounded UTF-16/native structures.
      credits.add(budget.reserve(planHeap, 128 * 1024, 2, 0));
      if (extraction) {
        containment = ExtractionPaths.preflight(destination, names, policy, context());
        existingTree = containment.existingRoot();
      }
      for (int index = 0; index < writers.size(); index++) {
        Path target =
            extraction ? containment.targets().get(index) : splitPath(destination, index + 1);
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        parts.add(new Part(index, target, writers.get(index), exists));
      }
      if (!extraction)
        containment =
            ExtractionPaths.preflight(
                destination.getParent(),
                parts.stream().map(part -> part.target.getFileName().toString()).toList(),
                policy,
                context());
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
      part.stagedIdentity = WindowsPathIdentity.inspect(part.staged);
      return size;
    }

    /** Installs a fully staged artifact with no implicit replacement semantics. */
    private void publish(Part part) throws IOException {
      recheck(part, false);
      if (part.predecessor) {
        part.backupIdentity = WindowsPathIdentity.inspect(part.target);
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
      part.scratch.close();
    }

    /** Keeps a containment failure at the actual publication boundary rather than preflight. */
    private void recheck(Part part, boolean parentsOnly) throws ArchiveException {
      try {
        if (parentsOnly) containment.recheckParents(part.target);
        else containment.recheck(part.target);
      } catch (ArchiveException failure) {
        throw context()
            .failure(
                failure.kind(),
                failure.primaryFailure().diagnosticIdentifier().orElse("destination.changed"),
                failure.getCause());
      }
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
            var current = WindowsPathIdentity.inspect(part.target);
            if (!Objects.equals(current, part.stagedIdentity)) {
              part.state = current == null ? ArtifactState.MISSING : ArtifactState.UNCHANGED;
              throw context()
                  .failure(FailureKind.DESTINATION, "destination.changed-during-rollback", null);
            }
            files.delete(part.target);
            part.state = ArtifactState.MISSING;
          }
          if (part.backedUp) {
            if (!Objects.equals(part.backupIdentity, WindowsPathIdentity.inspect(part.backup))) {
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
        containment.createdDirectory(path);
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
          var current = WindowsPathIdentity.inspect(part.target);
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
      operationSession.accept(failure);
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
                destination, 0, rootPublished ? ArtifactState.PUBLISHED : ArtifactState.MISSING));
      result.sort(Comparator.comparingLong(Artifact::ordinal));
      return List.copyOf(result);
    }

    /** Supplies stable phase and ordinal evidence without provider exception text. */
    private IoContext context() {
      return new IoContext(
          destination,
          extraction ? Operation.EXTRACT : Operation.PACK,
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

  /** One target's retained in-process publication history. */
  private static final class Part {
    final long ordinal;
    final Path target;
    final Writer writer;
    final boolean predecessor;
    Path staged;
    Path backup;
    WindowsPathIdentity.Snapshot stagedIdentity;
    WindowsPathIdentity.Snapshot backupIdentity;
    boolean backedUp;
    ResourceBudget.Lease scratch;
    ArtifactState state;

    /** Starts with the observed target state, before destination effects. */
    Part(long ordinal, Path target, Writer writer, boolean predecessor) {
      this.ordinal = ordinal;
      this.target = target;
      this.writer = writer;
      this.predecessor = predecessor;
      state = predecessor ? ArtifactState.UNCHANGED : ArtifactState.MISSING;
    }
  }
}
