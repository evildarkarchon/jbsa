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
 * Target containment: complete target-name preflight and a pinned destination lifetime on the
 * qualified provider. It contains both publication target sets, an archive set's part files beside
 * the destination and an extraction's files beneath its root, so it is named for targets rather
 * than for either operation. Its diagnostic identifiers keep their published {@code extraction.*}
 * spelling.
 *
 * <p>The coordinator owns this object and serializes directory creation and publication. Detached
 * descendant identities detect unexpected mutation; the root handle denies deletion until close.
 * This is the non-hostile destination-tree contract, not protection against a privileged racer. Off
 * Windows the {@link PathIdentity} pin holds no handle, so root replacement is detected by the same
 * revalidation rather than denied.
 *
 * <p>Preflight failures are located by the preflight context. Every later check takes the caller's
 * current location, so a recheck failure reports the phase and ordinal where publication observed
 * it; only the pin's own cleanup, which is plan-level, keeps the preflight ordinal.
 */
public final class TargetPaths implements AutoCloseable {
  private final Path root;
  private final boolean existingRoot;
  private final List<Path> targets;
  private final Set<Path> targetSet;
  private final Map<Path, PathIdentity.Snapshot> expected = new HashMap<>();
  private final Map<Path, List<Path>> directoryAliases = new HashMap<>();
  private final Path pinnedPath;
  private final PathIdentity.Pin pin;
  private final IoContext context;

  /** Publication's filesystem fault seam; every identity read and the pin go through it. */
  private final PublicationTransaction.FileActions files;

  private boolean closed;

  /** Takes ownership of the root pin only after all caller names have passed lexical validation. */
  private TargetPaths(
      Path root,
      boolean existingRoot,
      List<Path> targets,
      Path pinnedPath,
      PathIdentity.Pin pin,
      IoContext context,
      PublicationTransaction.FileActions files) {
    this.root = root;
    this.existingRoot = existingRoot;
    this.targets = List.copyOf(targets);
    targetSet = Set.copyOf(targets);
    this.pinnedPath = pinnedPath;
    this.pin = pin;
    this.context = context;
    this.files = files;
    expected.put(pinnedPath, pin.snapshot());
  }

  /**
   * Validates every selected name before host conversion, then pins and inspects the output tree. A
   * missing root requires an existing immediate parent so its publication is one atomic move.
   *
   * <p>Every identity read and the root pin go through {@code files}, publication's one filesystem
   * fault seam, so containment faults are injectable on the same path production takes. The
   * returned containment owns the pin and releases it on {@link #close}; a failed preflight
   * releases it before throwing.
   *
   * @param files the filesystem actions whose identity reads and pin this containment uses
   * @throws ArchiveException for unsafe names/collisions, unavailable identity, or destination I/O
   */
  static TargetPaths preflight(
      Path root,
      List<String> names,
      TargetPolicy policy,
      IoContext context,
      PublicationTransaction.FileActions files)
      throws ArchiveException {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(names, "names");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(files, "files");
    validateNames(names, context);

    // Do not move host conversion above validateNames: a later malformed entry invalidates the
    // complete selection before any name reaches a filesystem provider.
    Path absolute = root.toAbsolutePath().normalize();
    List<Path> targets = new ArrayList<>(names.size());
    // Case collisions are rejected on every host, so they are keyed explicitly rather than by
    // Path equality, which is case-insensitive only on Windows.
    Set<String> uniqueTargets = new HashSet<>();
    for (String name : names) {
      Path target = absolute.resolve(name.replace('\\', '/')).normalize();
      if (!target.startsWith(absolute)
          || target.equals(absolute)
          || !uniqueTargets.add(caseKey(absolute.relativize(target)))) {
        throw context.failure(FailureKind.POLICY, "extraction.name-collision", null);
      }
      targets.add(target);
    }
    for (Path target : targets) {
      for (Path parent = target.getParent();
          !parent.equals(absolute);
          parent = parent.getParent()) {
        if (uniqueTargets.contains(caseKey(absolute.relativize(parent)))) {
          throw context.failure(FailureKind.POLICY, "extraction.name-collision", null);
        }
      }
    }

    PathIdentity.Pin pin = null;
    try {
      PathIdentity.Snapshot rootState = files.inspect(absolute);
      boolean exists = rootState != null;
      Path pinnedPath = exists ? absolute : absolute.getParent();
      if (pinnedPath == null) {
        throw context.failure(FailureKind.DESTINATION, "extraction.parent-unavailable", null);
      }
      pin = files.pin(pinnedPath);
      if (pin.snapshot().indirection() || !pin.snapshot().directory()) {
        throw context.failure(FailureKind.POLICY, "extraction.unsafe-directory", null);
      }
      if (exists && !rootState.equals(pin.snapshot())) {
        throw context.failure(FailureKind.DESTINATION, "extraction.destination-changed", null);
      }
      TargetPaths plan =
          new TargetPaths(absolute, exists, targets, pinnedPath, pin, context, files);
      if (!exists) {
        // A root that appeared between inspection and pinning is an unexpected mutation.
        if (files.inspect(absolute) != null) {
          throw context.failure(FailureKind.DESTINATION, "extraction.destination-changed", null);
        }
        plan.expected.put(absolute, null);
      }
      Set<Object> targetIdentities = new HashSet<>();
      for (Path target : targets) {
        plan.inspectTarget(target, policy);
        PathIdentity.Snapshot state = plan.expected.get(target);
        // Different path spellings, including short-name aliases, may still name one file.
        if (state != null && !targetIdentities.add(state.identity())) {
          throw context.failure(FailureKind.POLICY, "extraction.name-collision", null);
        }
      }
      plan.checkPhysicalPaths();
      plan.recheckRoot(context);
      return plan;
    } catch (IOException | RuntimeException | Error failure) {
      IOException cleanupFailure = null;
      if (pin != null) {
        try {
          pin.close();
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

  /**
   * Revalidates the root, each target parent, and the target immediately before its first move.
   *
   * @param location the caller's current phase and ordinal, which locate any failure
   * @throws ArchiveException if the containment chain or the target no longer matches the plan
   */
  public void recheck(Path target, IoContext location) throws ArchiveException {
    recheckParents(target, location);
    verify(target, location);
  }

  /**
   * Revalidates the containment chain immediately before install, including after an owned backup
   * removed the predecessor. Deliberately does not compare the target's previous identity.
   *
   * @param location the caller's current phase and ordinal, which locate any failure
   * @throws ArchiveException if the root or a target parent no longer matches the plan
   */
  public void recheckParents(Path target, IoContext location) throws ArchiveException {
    requireTarget(target);
    recheckRoot(location);
    for (Path parent : descendants(target.getParent())) {
      verify(parent, location);
    }
  }

  /**
   * Revalidates the pinned root, or its pinned parent and continued absence for a new-root move.
   *
   * @param location the caller's current phase and ordinal, which locate any failure
   * @throws ArchiveException if the pinned path, or a new root's absence, no longer holds
   */
  public void recheckRoot(IoContext location) throws ArchiveException {
    if (closed) throw new IllegalStateException("Target containment is closed");
    verify(pinnedPath, location);
    if (!existingRoot) verify(root, location);
  }

  /**
   * Records a planned missing directory immediately after the coordinator creates it. Parents must
   * already match the plan, and unexpected existing paths cannot be adopted as operation-owned.
   *
   * @param location the caller's current phase and ordinal, which locate any failure
   * @throws ArchiveException if a parent changed or the path is not the directory just created
   */
  public void createdDirectory(Path path, IoContext location) throws ArchiveException {
    if (!path.startsWith(root)
        || path.equals(root)
        || targetSet.contains(path)
        || !expected.containsKey(path)
        || expected.get(path) != null) {
      throw new IllegalArgumentException("Directory is not a planned missing parent");
    }
    recheckRoot(location);
    for (Path parent : descendants(path.getParent())) {
      verify(parent, location);
    }
    PathIdentity.Snapshot state = inspect(path, location);
    if (state == null || !state.directory() || state.indirection()) {
      throw location.failure(FailureKind.DESTINATION, "extraction.destination-changed", null);
    }
    // Known short-name aliases share operation-owned growth, while unrelated paths retain their
    // original expected state and must still fail revalidation if another actor creates them.
    for (Path alias : directoryAliases.getOrDefault(path, List.of(path))) {
      expected.put(alias, state);
    }
  }

  /**
   * Compares future targets by existing-directory identity and case-insensitive relative suffix
   * before any publication. This catches short-directory aliases even when the leaves do not exist
   * yet, and case collisions through them on every host.
   */
  private void checkPhysicalPaths() throws ArchiveException {
    Map<Object, Set<String>> keysByParent = new HashMap<>();
    Map<Object, List<Path>> suffixesByParent = new HashMap<>();
    for (Path target : targets) {
      Path anchor = anchor(target);
      Object identity = expected.get(anchor).identity();
      Path suffix = anchor.relativize(target);
      if (!keysByParent.computeIfAbsent(identity, unused -> new HashSet<>()).add(caseKey(suffix))) {
        throw context.failure(FailureKind.POLICY, "extraction.name-collision", null);
      }
      suffixesByParent.computeIfAbsent(identity, unused -> new ArrayList<>()).add(suffix);
    }
    for (var entry : suffixesByParent.entrySet()) {
      Set<String> keys = keysByParent.get(entry.getKey());
      for (Path suffix : entry.getValue()) {
        for (Path parent = suffix.getParent(); parent != null; parent = parent.getParent()) {
          if (keys.contains(caseKey(parent))) {
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
   * Anchors a planned path at its nearest existing parent. Path equality keeps the host's own
   * casing here: these are physical aliases, and on a case-sensitive host two case spellings of a
   * missing directory really are two directories.
   */
  private PhysicalPath physicalPath(Path path) {
    Path anchor = anchor(path);
    return new PhysicalPath(expected.get(anchor).identity(), anchor.relativize(path));
  }

  /** Returns the nearest parent of a planned path that existed during preflight. */
  private Path anchor(Path path) {
    Path parent = path.getParent();
    while (expected.get(parent) == null) {
      parent = parent.getParent();
    }
    return parent;
  }

  /**
   * Returns the Windows case-insensitive comparison key of a relative path: every UTF-16 unit
   * upper-cased on its own, exactly as the Windows NIO provider compares and hashes paths. Using it
   * on every host keeps collision rejection identical whatever the host filesystem's own casing.
   */
  private static String caseKey(Path relative) {
    String spelling = relative.toString();
    var key = new StringBuilder(spelling.length());
    for (int index = 0; index < spelling.length(); index++)
      key.append(Character.toUpperCase(spelling.charAt(index)));
    return key.toString();
  }

  /**
   * A physical namespace location whose suffix has not yet become a separate directory identity.
   */
  private record PhysicalPath(Object parentIdentity, Path suffix) {}

  /**
   * Releases the owned root pin; after close no path can be admitted for publication. A failed
   * release is plan-level evidence, so it is located in CLEANUP at the preflight ordinal.
   */
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
      PathIdentity.Snapshot state;
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
    PathIdentity.Snapshot state = absentParent ? null : inspect(target);
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

  /**
   * Compares detached no-follow identity and classification with the expected operation state,
   * locating a mismatch or read failure at {@code location}.
   */
  private void verify(Path path, IoContext location) throws ArchiveException {
    if (!expected.containsKey(path)
        || !Objects.equals(expected.get(path), inspect(path, location))) {
      throw location.failure(FailureKind.DESTINATION, "extraction.destination-changed", null);
    }
  }

  /** Reads one preflight identity, locating any failure at the preflight context. */
  private PathIdentity.Snapshot inspect(Path path) throws ArchiveException {
    return inspect(path, context);
  }

  /**
   * Converts provider errors into semantic containment failures without copying their messages,
   * located at {@code location}.
   */
  private PathIdentity.Snapshot inspect(Path path, IoContext location) throws ArchiveException {
    try {
      return files.inspect(path);
    } catch (UnsupportedOperationException failure) {
      throw location.failure(FailureKind.CAPABILITY, "extraction.identity-unavailable", failure);
    } catch (IOException | SecurityException failure) {
      throw location.failure(FailureKind.DESTINATION, "extraction.destination-io", failure);
    }
  }

  /**
   * Prevents a coordinator from accidentally publishing a path outside the complete planned set.
   */
  private void requireTarget(Path target) {
    if (!targetSet.contains(target)) {
      throw new IllegalArgumentException("Target is not in the planned target set");
    }
  }
}
