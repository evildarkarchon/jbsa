package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.Failure;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.NormalizedNameIdentity;
import io.github.evildarkarchon.jbsa.OperationPhase;
import io.github.evildarkarchon.jbsa.TargetPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Complete extraction-name preflight and a pinned destination lifetime on the qualified provider.
 *
 * <p>The coordinator owns this object and serializes directory creation and publication. Detached
 * descendant identities detect unexpected mutation; the root handle denies deletion until close.
 * This is the non-hostile destination-tree contract, not protection against a privileged racer.
 */
public final class ExtractionPaths implements AutoCloseable {
  private final Path root;
  private final boolean existingRoot;
  private final List<Path> targets;
  private final Set<Path> targetSet;
  private final Map<Path, WindowsPathIdentity.Snapshot> expected = new HashMap<>();
  private final Map<Path, List<Path>> directoryAliases = new HashMap<>();
  private final Path pinnedPath;
  private final WindowsPathIdentity.Pin pin;
  private final IoContext context;
  private final SnapshotReader snapshotReader;
  private boolean closed;

  /** Takes ownership of the root pin only after all caller names have passed lexical validation. */
  private ExtractionPaths(
      Path root,
      boolean existingRoot,
      List<Path> targets,
      Path pinnedPath,
      WindowsPathIdentity.Pin pin,
      IoContext context,
      SnapshotReader snapshotReader) {
    this.root = root;
    this.existingRoot = existingRoot;
    this.targets = List.copyOf(targets);
    targetSet = Set.copyOf(targets);
    this.pinnedPath = pinnedPath;
    this.pin = pin;
    this.context = context;
    this.snapshotReader = snapshotReader;
    expected.put(pinnedPath, pin.snapshot());
  }

  /**
   * Validates every selected name before host conversion, then pins and inspects the output tree. A
   * missing root requires an existing immediate parent so its publication is one atomic move.
   *
   * @throws ArchiveException for unsafe names/collisions, unavailable identity, or destination I/O
   */
  public static ExtractionPaths preflight(
      Path root, List<String> names, TargetPolicy policy, IoContext context)
      throws ArchiveException {
    return preflight(root, names, policy, context, WindowsPathIdentity.Pin::close);
  }

  /**
   * Supplies deterministic fault injection at failed-preflight pin cleanup without leaking handles.
   */
  static ExtractionPaths preflight(
      Path root, List<String> names, TargetPolicy policy, IoContext context, PinCloser cleanupPin)
      throws ArchiveException {
    return preflight(root, names, policy, context, cleanupPin, WindowsPathIdentity::inspect);
  }

  /**
   * Supplies detached identity observations for deterministic alias and namespace mutation tests.
   */
  static ExtractionPaths preflight(
      Path root,
      List<String> names,
      TargetPolicy policy,
      IoContext context,
      PinCloser cleanupPin,
      SnapshotReader snapshotReader)
      throws ArchiveException {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(names, "names");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(snapshotReader, "snapshotReader");
    validateNames(names, context);

    // Do not move host conversion above validateNames: a later malformed entry invalidates the
    // complete selection before any name reaches a filesystem provider.
    Path absolute = root.toAbsolutePath().normalize();
    List<Path> targets = new ArrayList<>(names.size());
    Set<Path> uniqueTargets = new HashSet<>();
    for (String name : names) {
      Path target = absolute.resolve(name.replace('\\', '/')).normalize();
      if (!target.startsWith(absolute) || target.equals(absolute) || !uniqueTargets.add(target)) {
        throw context.failure(FailureKind.POLICY, "extraction.name-collision", null);
      }
      targets.add(target);
    }
    for (Path target : targets) {
      for (Path parent = target.getParent();
          !parent.equals(absolute);
          parent = parent.getParent()) {
        if (uniqueTargets.contains(parent)) {
          throw context.failure(FailureKind.POLICY, "extraction.name-collision", null);
        }
      }
    }

    WindowsPathIdentity.Pin pin = null;
    try {
      WindowsPathIdentity.Snapshot rootState = snapshotReader.read(absolute);
      boolean exists = rootState != null;
      Path pinnedPath = exists ? absolute : absolute.getParent();
      if (pinnedPath == null) {
        throw context.failure(FailureKind.DESTINATION, "extraction.parent-unavailable", null);
      }
      pin = WindowsPathIdentity.pin(pinnedPath);
      if (pin.snapshot().indirection() || !pin.snapshot().directory()) {
        throw context.failure(FailureKind.POLICY, "extraction.unsafe-directory", null);
      }
      if (exists && !rootState.equals(pin.snapshot())) {
        throw context.failure(FailureKind.DESTINATION, "extraction.destination-changed", null);
      }
      ExtractionPaths plan =
          new ExtractionPaths(absolute, exists, targets, pinnedPath, pin, context, snapshotReader);
      if (!exists) {
        // A root that appeared between inspection and pinning is an unexpected mutation.
        if (snapshotReader.read(absolute) != null) {
          throw context.failure(FailureKind.DESTINATION, "extraction.destination-changed", null);
        }
        plan.expected.put(absolute, null);
      }
      Set<Object> targetIdentities = new HashSet<>();
      for (Path target : targets) {
        plan.inspectTarget(target, policy);
        WindowsPathIdentity.Snapshot state = plan.expected.get(target);
        // Different path spellings, including short-name aliases, may still name one file.
        if (state != null && !targetIdentities.add(state.identity())) {
          throw context.failure(FailureKind.POLICY, "extraction.name-collision", null);
        }
      }
      plan.checkPhysicalPaths();
      plan.recheckRoot();
      return plan;
    } catch (IOException | RuntimeException | Error failure) {
      IOException cleanupFailure = null;
      if (pin != null) {
        try {
          cleanupPin.close(pin);
        } catch (IOException cleanup) {
          cleanupFailure = cleanup;
        }
      }
      ArchiveException primary = null;
      if (failure instanceof ArchiveException archive) {
        primary = archive;
      } else if (failure instanceof UnsupportedOperationException capability) {
        primary =
            context.failure(FailureKind.CAPABILITY, "extraction.identity-unavailable", capability);
      } else if (failure instanceof IOException || failure instanceof SecurityException) {
        primary = context.failure(FailureKind.DESTINATION, "extraction.destination-io", failure);
      }
      if (primary != null) {
        if (cleanupFailure == null) throw primary;
        List<Failure> secondary = new ArrayList<>(primary.secondaryFailures());
        secondary.add(
            new IoContext(
                    context.path(), context.operation(), OperationPhase.CLEANUP, context.ordinal())
                .failure(FailureKind.DESTINATION, "extraction.pin-cleanup", cleanupFailure)
                .primaryFailure());
        // The coordinator applies ResourceLimits to this single additional cleanup observation.
        throw new ArchiveException(
            primary.getMessage(),
            primary.primaryFailure(),
            primary.diagnostics(),
            primary.artifacts(),
            primary.assessment(),
            secondary);
      }
      // VM/programming faults have no structured operation outcome; preserve their cleanup detail
      // only on the raw throwable. Operational failures above never duplicate secondary causes.
      if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
      if (failure instanceof Error error) {
        throw error;
      }
      throw (RuntimeException) failure;
    }
  }

  /**
   * The sole failed-preflight cleanup boundary; production closes the owned native pin directly.
   */
  @FunctionalInterface
  interface PinCloser {
    /** Releases the supplied pin, or reports its cleanup failure. */
    void close(WindowsPathIdentity.Pin pin) throws IOException;
  }

  /** A no-handle classification seam; real Windows observations remain the production default. */
  @FunctionalInterface
  interface SnapshotReader {
    /** Returns the named entry's identity and no-follow type, or null for an absent entry. */
    WindowsPathIdentity.Snapshot read(Path path) throws IOException;
  }

  /** Returns the absolute destination root, preserving caller and archive display spelling. */
  public Path root() {
    return root;
  }

  /** Returns whether publication must settle each file in an existing destination tree. */
  public boolean existingRoot() {
    return existingRoot;
  }

  /** Returns immutable output paths in the caller's established Logical Plan Order. */
  public List<Path> targets() {
    return targets;
  }

  /** Revalidates the root, each target parent, and the target immediately before its first move. */
  public void recheck(Path target) throws ArchiveException {
    recheckParents(target);
    verify(target);
  }

  /**
   * Revalidates the containment chain immediately before install, including after an owned backup
   * removed the predecessor. Deliberately does not compare the target's previous identity.
   */
  public void recheckParents(Path target) throws ArchiveException {
    requireTarget(target);
    recheckRoot();
    for (Path parent : descendants(target.getParent())) {
      verify(parent);
    }
  }

  /**
   * Revalidates the pinned root, or its pinned parent and continued absence for a new-root move.
   */
  public void recheckRoot() throws ArchiveException {
    if (closed) throw new IllegalStateException("Extraction containment is closed");
    verify(pinnedPath);
    if (!existingRoot) verify(root);
  }

  /**
   * Records a planned missing directory immediately after the coordinator creates it. Parents must
   * already match the plan, and unexpected existing paths cannot be adopted as operation-owned.
   */
  public void createdDirectory(Path path) throws ArchiveException {
    if (!path.startsWith(root)
        || path.equals(root)
        || targetSet.contains(path)
        || !expected.containsKey(path)
        || expected.get(path) != null) {
      throw new IllegalArgumentException("Directory is not a planned missing parent");
    }
    recheckRoot();
    for (Path parent : descendants(path.getParent())) {
      verify(parent);
    }
    WindowsPathIdentity.Snapshot state = inspect(path);
    if (state == null || !state.directory() || state.indirection()) {
      throw context.failure(FailureKind.DESTINATION, "extraction.destination-changed", null);
    }
    // Known short-name aliases share operation-owned growth, while unrelated paths retain their
    // original expected state and must still fail revalidation if another actor creates them.
    for (Path alias : directoryAliases.getOrDefault(path, List.of(path))) {
      expected.put(alias, state);
    }
  }

  /**
   * Compares future targets by existing-directory identity and Windows-relative suffix before any
   * publication. This catches short-directory aliases even when the leaves do not exist yet.
   */
  private void checkPhysicalPaths() throws ArchiveException {
    Map<Object, Set<Path>> byParent = new HashMap<>();
    for (Path target : targets) {
      PhysicalPath physical = physicalPath(target);
      Set<Path> suffixes =
          byParent.computeIfAbsent(physical.parentIdentity(), unused -> new HashSet<>());
      if (!suffixes.add(physical.suffix())) {
        throw context.failure(FailureKind.POLICY, "extraction.name-collision", null);
      }
    }
    for (Set<Path> suffixes : byParent.values()) {
      for (Path suffix : suffixes) {
        for (Path parent = suffix.getParent(); parent != null; parent = parent.getParent()) {
          if (suffixes.contains(parent)) {
            throw context.failure(FailureKind.POLICY, "extraction.name-collision", null);
          }
        }
      }
    }
    Map<PhysicalPath, List<Path>> aliases = new HashMap<>();
    for (var entry : expected.entrySet()) {
      Path path = entry.getKey();
      if (entry.getValue() == null && !targetSet.contains(path) && !path.equals(root)) {
        aliases.computeIfAbsent(physicalPath(path), unused -> new ArrayList<>()).add(path);
      }
    }
    for (List<Path> paths : aliases.values()) {
      List<Path> spellings = List.copyOf(paths);
      for (Path path : paths) directoryAliases.put(path, spellings);
    }
  }

  /**
   * Anchors a planned path at its nearest existing parent; Path equality supplies Windows casing.
   */
  private PhysicalPath physicalPath(Path path) {
    Path parent = path.getParent();
    while (expected.get(parent) == null) {
      parent = parent.getParent();
    }
    return new PhysicalPath(expected.get(parent).identity(), parent.relativize(path));
  }

  /**
   * A physical namespace location whose suffix has not yet become a separate directory identity.
   */
  private record PhysicalPath(Object parentIdentity, Path suffix) {}

  /** Releases the owned root pin; after close no path can be admitted for publication. */
  @Override
  public void close() throws ArchiveException {
    if (!closed) {
      try {
        pin.close();
        closed = true;
      } catch (IOException failure) {
        throw new IoContext(
                context.path(), context.operation(), OperationPhase.CLEANUP, context.ordinal())
            .failure(FailureKind.DESTINATION, "extraction.pin-cleanup", failure);
      }
    }
  }

  /** Checks archive identities and Windows lexical restrictions without constructing any Path. */
  private static void validateNames(List<String> names, IoContext context) throws ArchiveException {
    Set<NormalizedNameIdentity> identities = new HashSet<>();
    for (String name : names) {
      var identity =
          name == null
              ? java.util.Optional.<NormalizedNameIdentity>empty()
              : NormalizedNameIdentity.from(name, StandardCharsets.UTF_8);
      if (identity.isEmpty()) {
        throw context.failure(FailureKind.POLICY, "extraction.unsafe-name", null);
      }
      if (!identities.add(identity.orElseThrow())) {
        throw context.failure(FailureKind.POLICY, "extraction.name-collision", null);
      }
      for (String segment : identity.orElseThrow().value().split("\\\\")) {
        for (int i = 0; i < segment.length(); i++) {
          char c = segment.charAt(i);
          if ((c >= 1 && c <= 31) || "\"*<>?|".indexOf(c) >= 0) {
            throw context.failure(FailureKind.POLICY, "extraction.unsafe-name", null);
          }
        }
        int dot = segment.indexOf('.');
        String stem = dot < 0 ? segment : segment.substring(0, dot);
        if (Set.of("con", "prn", "aux", "nul").contains(stem)
            || (stem.length() == 4
                && (stem.startsWith("com") || stem.startsWith("lpt"))
                && "123456789\u00b9\u00b2\u00b3".indexOf(stem.charAt(3)) >= 0)) {
          throw context.failure(FailureKind.POLICY, "extraction.unsafe-name", null);
        }
      }
    }
  }

  /** Captures parent and target states only after each preceding component is known to be safe. */
  private void inspectTarget(Path target, TargetPolicy policy) throws ArchiveException {
    boolean absentParent = !existingRoot;
    for (Path parent : descendants(target.getParent())) {
      WindowsPathIdentity.Snapshot state;
      if (expected.containsKey(parent)) {
        state = expected.get(parent);
      } else {
        state = absentParent ? null : inspect(parent);
        expected.put(parent, state);
      }
      if (state != null && (state.indirection() || !state.directory())) {
        throw context.failure(FailureKind.POLICY, "extraction.unsafe-directory", null);
      }
      absentParent = state == null;
    }
    WindowsPathIdentity.Snapshot state = absentParent ? null : inspect(target);
    if (state != null && (state.indirection() || !state.regular())) {
      throw context.failure(FailureKind.POLICY, "extraction.unsafe-target", null);
    }
    if (state != null && policy == TargetPolicy.FAIL) {
      throw context.failure(FailureKind.POLICY, "extraction.target-exists", null);
    }
    expected.put(target, state);
  }

  /**
   * Returns a root-to-leaf chain excluding the root, so no descendant is inspected through a link.
   */
  private List<Path> descendants(Path leaf) {
    List<Path> reversed = new ArrayList<>();
    for (Path current = leaf; !current.equals(root); current = current.getParent()) {
      reversed.add(current);
    }
    return reversed.reversed();
  }

  /** Compares detached no-follow identity and classification with the expected operation state. */
  private void verify(Path path) throws ArchiveException {
    if (!expected.containsKey(path) || !Objects.equals(expected.get(path), inspect(path))) {
      throw context.failure(FailureKind.DESTINATION, "extraction.destination-changed", null);
    }
  }

  /** Converts provider errors into semantic containment failures without copying their messages. */
  private WindowsPathIdentity.Snapshot inspect(Path path) throws ArchiveException {
    try {
      return snapshotReader.read(path);
    } catch (UnsupportedOperationException failure) {
      throw context.failure(FailureKind.CAPABILITY, "extraction.identity-unavailable", failure);
    } catch (IOException | SecurityException failure) {
      throw context.failure(FailureKind.DESTINATION, "extraction.destination-io", failure);
    }
  }

  /**
   * Prevents a coordinator from accidentally publishing a path outside the complete planned set.
   */
  private void requireTarget(Path target) {
    if (!targetSet.contains(target)) {
      throw new IllegalArgumentException("Target is not in the extraction plan");
    }
  }
}
