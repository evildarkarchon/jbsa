package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.ba2.Ba2Layout;
import io.github.evildarkarchon.jbsa.internal.tes3.Tes3Names;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Complete source discovery and ordered overlays, before any payload factory is invoked. */
public final class PackSources {
  private PackSources() {}

  /**
   * Attributes lazy source revalidation and read failures to their final logical processing slot.
   *
   * @param context the entry's PROCESSING location, at its source (ADR-0006)
   */
  public static void consume(Entry entry, Reader reader, IoContext context) throws IOException {
    try {
      entry.payload().consume(reader, context);
    } catch (ArchiveException failure) {
      if (failure.primaryFailure().phase() != OperationPhase.PREFLIGHT) throw failure;
      var diagnostics =
          failure.diagnostics().stream()
              .map(
                  d ->
                      new Diagnostic(
                          d.identifier(),
                          d.severity(),
                          Operation.PACK,
                          OperationPhase.PROCESSING,
                          d.location(),
                          d.values(),
                          d.explanation()))
              .toList();
      Failure previous = failure.primaryFailure();
      Failure primary =
          new Failure(
              previous.kind(),
              OperationPhase.PROCESSING,
              context.ordinal(),
              previous.diagnosticIdentifier(),
              previous.location(),
              previous.cause());
      throw new ArchiveException(
          failure.getMessage(),
          primary,
          diagnostics,
          failure.artifacts(),
          failure.assessment(),
          failure.secondaryFailures());
    } catch (IOException failure) {
      // A reader's checkpoint stop comes back unchanged from the entry.
      throw context.structure(failure);
    }
  }

  /** Expands each supplied root independently and preserves later-source replacement semantics. */
  public static List<Entry> plan(
      PackRequest request, OperationSession operation, ResourceBudget budget) throws IOException {
    return plan(request, operation, budget, StandardCharsets.US_ASCII);
  }

  /**
   * Plans names under a format-selected encoding while retaining Unicode overlay identity. The
   * format writer must still validate encoded-byte qualification and wire-name aliases.
   */
  public static List<Entry> plan(
      PackRequest request, OperationSession operation, ResourceBudget budget, Charset encoding)
      throws IOException {
    Map<String, Entry> overlay = new LinkedHashMap<>();
    for (PackSource source : request.sources()) {
      operation.checkpoint(OperationPhase.PREFLIGHT, OptionalLong.empty());
      if (source instanceof PackSource.GeneratedEntry generated) {
        admit(
            entry(
                generated.name(),
                generated.length(),
                (reader, processing) -> consumeGenerated(generated, reader, processing, operation),
                IoContext.generated(generated.name(), Operation.PACK),
                encoding),
            overlay,
            request,
            operation,
            budget);
      } else if (source instanceof PackSource.NamedFile named) {
        Path path = named.path().toAbsolutePath().normalize();
        rejectOutput(path, request.destination(), IoContext.source(path, Operation.PACK));
        admit(loose(named.name(), path, encoding), overlay, request, operation, budget);
      } else if (source instanceof PackSource.DetectedPath detected) {
        Path path = detected.path().toAbsolutePath().normalize();
        // Each check is located at the path it examined, here the detected root itself.
        IoContext context = IoContext.source(path, Operation.PACK);
        rejectOutput(path, request.destination(), context);
        PathIdentity.Snapshot shape = shape(path);
        if (shape == null || shape.indirection())
          throw context.roleFailure("source.not-regular", null);
        if (shape.directory()) {
          List<Entry> discovered = new ArrayList<>();
          try {
            walk(path, discovered, request, operation, budget, encoding);
          } catch (IOException failure) {
            // A raw tree-walk fault, such as an unreadable directory, is SOURCE at the walked root.
            throw context.structure(failure);
          }
          discovered.sort(Comparator.comparing(Entry::identity).thenComparing(Entry::displayName));
          for (Entry entry : discovered)
            if (included(entry.displayName, request.options())) overlay.put(entry.identity, entry);
        } else if (shape.regular()) {
          SourceFile stable = SourceFile.plan(path);
          ArchiveDetection detection = BethesdaArchives.standard().detect(path);
          if (detection.status() == DetectionStatus.UNRECOGNIZED) {
            admit(
                loose(path.getFileName().toString(), path, encoding),
                overlay,
                request,
                operation,
                budget);
          } else {
            try (OpenArchive archive =
                ArchiveReaders.open(
                    path,
                    new OpenOptions(
                        request.compatibilityProfile(), request.resourceLimits(), Optional.empty()),
                    Operation.PACK,
                    request.diagnosticPolicy())) {
              operation.assessment(archive.inspection().assessment());
              // Each closed source index releases its own memory, while parsed metadata remains
              // charged to this pack invocation even when all its entries are later overlaid.
              budget.consumedMetadata(metadataExtent(archive.inspection()));
              for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
                EntryMetadata metadata = archive.entry(ordinal).metadata();
                if (metadata.normalizedNameIdentity().isEmpty())
                  throw context.failure(FailureKind.POLICY, "pack.invalid-encode-name", null);
                long selected = ordinal;
                admit(
                    entry(
                        metadata.displayName(),
                        metadata.decodedSize(),
                        (reader, processing) ->
                            stable.consume(
                                ignored -> {
                                  try (OpenArchive reopened =
                                          ArchiveReaders.open(
                                              path,
                                              new OpenOptions(
                                                  request.compatibilityProfile(),
                                                  request.resourceLimits(),
                                                  Optional.empty()),
                                              Operation.PACK,
                                              request.diagnosticPolicy());
                                      EntryContent content =
                                          reopened.entry(selected).openContent()) {
                                    reader.read(content);
                                  }
                                  return null;
                                }),
                        context,
                        encoding),
                    overlay,
                    request,
                    operation,
                    budget);
              }
            }
          }
        } else throw context.roleFailure("source.not-regular", null);
      }
    }
    return new ArrayList<>(overlay.values());
  }

  /**
   * Discovers one directory root's regular files. Each file is planned as a loose source at its own
   * path; structured failures and stop signals pass through, and a raw walk fault reaches the
   * caller, which locates it at the root.
   */
  private static void walk(
      Path root,
      List<Entry> discovered,
      PackRequest request,
      OperationSession operation,
      ResourceBudget budget,
      Charset encoding)
      throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          /** Omits directory indirections before any descendants can enter the source plan. */
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
              throws IOException {
            operation.checkpoint(OperationPhase.PREFLIGHT, OptionalLong.empty());
            var current = shape(directory);
            return current != null && current.directory() && !current.indirection()
                ? FileVisitResult.CONTINUE
                : FileVisitResult.SKIP_SUBTREE;
          }

          /** Admits only no-follow regular files after output exclusion and resource accounting. */
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            if (outputPath(file, request.destination())) return FileVisitResult.CONTINUE;
            var current = shape(file);
            if (current != null && current.regular() && !current.indirection()) {
              Entry entry = loose(root.relativize(file).toString(), file, encoding);
              // Count candidates before retaining them, even if a later overlay replaces
              // them.
              account(entry, operation, budget);
              discovered.add(entry);
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** Counts parsed wire sections without charging payload bytes to a repack invocation. */
  private static long metadataExtent(ArchiveInspection inspection) {
    return switch (inspection.metadata()) {
      case ArchiveMetadata.Tes3 value -> value.dataBaseOffset();
      case ArchiveMetadata.VersionedBsa value ->
          36
              // SSE folder records include eight padding bytes absent from earlier versions.
              + value.folderCount()
                  * (value.encoding().wireVersion().orElseThrow().value() == 0x69 ? 24L : 16L)
              + value.entryCount() * 16
              + value.folderNamesLength()
              + value.fileNamesLength()
              + ((value.archiveFlags() & 1) != 0 ? value.folderCount() : 0)
              + inspection.entries().stream()
                  .mapToLong(
                      entry ->
                          entry.wireNames().containsKey("embedded")
                              ? 1 + entry.wireNames().get("embedded").length()
                              : 0)
                  .sum()
              + inspection.entries().stream()
                      .filter(e -> ((EntryMetadata.VersionedBsa) e.facts()).compressed())
                      .count()
                  * 4;
      case ArchiveMetadata.GeneralBa2 value ->
          Ba2Layout.headerSize(value.encoding().wireVersion().orElseThrow().value())
              + value.entryCount() * 36
              + inspection.entries().stream()
                  .mapToLong(
                      entry ->
                          entry.wireNames().containsKey("complete")
                              ? 2 + entry.wireNames().get("complete").length()
                              : 0)
                  .sum();
      case ArchiveMetadata.DdsBa2 value ->
          Ba2Layout.headerSize(value.encoding().wireVersion().orElseThrow().value())
              + inspection.entries().stream()
                  .mapToLong(
                      entry ->
                          24
                              + 24L * ((EntryMetadata.DdsBa2) entry.facts()).chunks().size()
                              + (entry.wireNames().containsKey("complete")
                                  ? 2 + entry.wireNames().get("complete").length()
                                  : 0))
                  .sum();
    };
  }

  /**
   * Settles transferred generated channels explicitly so close errors remain structured evidence. A
   * read and a close failure fold into one failure for the caller to throw.
   *
   * @param processing the entry's PROCESSING location: a generated source has no path, so it is
   *     located by its entry ordinal and name
   * @param operation the pack's session, which takes a close failure directly when the read was
   *     stopped by the session, because the stop signal carries no evidence to fold it into
   */
  private static void consumeGenerated(
      PackSource.GeneratedEntry generated,
      Reader reader,
      IoContext processing,
      OperationSession operation)
      throws IOException {
    ReadableByteChannel channel = null;
    Throwable read = null;
    try {
      channel = generated.contentFactory().open();
      if (channel == null) throw processing.roleFailure("source.invalid-channel", null);
      reader.read(channel);
    } catch (IOException | RuntimeException | Error failure) {
      read = failure;
    }
    List<ArchiveException> cleanup = new ArrayList<>(1);
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException | RuntimeException | AssertionError failure) {
        cleanup.add(processing.at(OperationPhase.CLEANUP).structure(failure));
      }
    }
    if (read == null) {
      if (!cleanup.isEmpty()) throw cleanup.getFirst();
      return;
    }
    if (read instanceof OperationStopped stopped) {
      // The session's stop has no evidence to fold the close failure into, so it goes straight in.
      cleanup.forEach(operation::accept);
      throw stopped;
    }
    // A worker's stop carries the close failure to its runner; anything else folds with it.
    throw processing.settle(read, cleanup);
  }

  /** Rejects explicit output aliases before source detection or archive expansion. */
  private static void rejectOutput(Path source, Path target, IoContext context)
      throws ArchiveException {
    if (outputPath(source, target))
      throw context.failure(FailureKind.POLICY, "source.output-overlap", null);
  }

  /** Recognizes the requested output and every numbered sibling using the shared filename rule. */
  private static boolean outputPath(Path source, Path target) {
    Path absolute = source.toAbsolutePath().normalize();
    Path destination = target.toAbsolutePath().normalize();
    if (!Objects.equals(absolute.getParent(), destination.getParent())) return false;
    String actual = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
    String requested = destination.getFileName().toString().toLowerCase(Locale.ROOT);
    if (actual.equals(requested)) return true;
    int dot = requested.lastIndexOf('.');
    if (dot < 0) dot = requested.length();
    String prefix = requested.substring(0, dot);
    String suffix = requested.substring(dot);
    if (!actual.startsWith(prefix) || !actual.endsWith(suffix)) return false;
    int end = actual.length() - suffix.length();
    if (end <= prefix.length()) return false;
    String number = actual.substring(prefix.length(), end);
    return number.charAt(0) != '0'
        && number.chars().allMatch(c -> c >= '0' && c <= '9')
        && (number.length() > 1 || number.charAt(0) >= '2');
  }

  /**
   * Obtains no-follow Windows classification, treating absent native support as a capability. A
   * failure is located at the path examined.
   */
  private static PathIdentity.Snapshot shape(Path path) throws IOException {
    IoContext context = IoContext.source(path, Operation.PACK);
    try {
      return PathIdentity.inspect(path);
    } catch (UnsupportedOperationException failure) {
      throw context.failure(FailureKind.CAPABILITY, "source.identity-unavailable", failure);
    } catch (SecurityException failure) {
      throw context.roleFailure("source.attributes", failure);
    } catch (IOException failure) {
      throw context.structure(failure, "source.attributes");
    }
  }

  /** Plans one loose source and revalidates its identity around bounded, deny-write consumption. */
  private static Entry loose(String name, Path path, Charset encoding) throws IOException {
    SourceFile source = SourceFile.plan(path);
    return entry(
        name,
        source.size(),
        (reader, processing) ->
            source.consume(
                input -> {
                  reader.read(
                      new ReadableByteChannel() {
                        private long position;

                        /**
                         * Advances within the one borrowed source without transferring
                         * backing-handle ownership.
                         */
                        @Override
                        public int read(ByteBuffer destination) throws IOException {
                          if (!destination.hasRemaining()) return 0;
                          if (position == input.size()) return -1;
                          int count =
                              (int) Math.min(destination.remaining(), input.size() - position);
                          ByteBuffer window = destination.slice().limit(count);
                          input.readExact(position, window);
                          destination.position(destination.position() + count);
                          position += count;
                          return count;
                        }

                        @Override
                        public boolean isOpen() {
                          return true;
                        }

                        @Override
                        public void close() {
                          // SourceFile owns the borrowed backing handle through final identity
                          // revalidation.
                        }
                      });
                  return null;
                }),
        IoContext.source(path.toAbsolutePath().normalize(), Operation.PACK),
        encoding);
  }

  /**
   * Strictly encodes an eligible Unicode name before it can participate in an overlay.
   *
   * @param location the entry's source location, which also locates a name it cannot encode
   */
  private static Entry entry(
      String name, long size, Payload payload, IoContext location, Charset encoding)
      throws ArchiveException {
    var identity = NormalizedNameIdentity.from(name, encoding);
    if (identity.isEmpty())
      throw location.failure(FailureKind.POLICY, "pack.invalid-encode-name", null);
    byte[] bytes;
    try {
      ByteBuffer encoded =
          encoding.newEncoder().encode(CharBuffer.wrap(identity.orElseThrow().value()));
      bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
    } catch (CharacterCodingException failure) {
      throw location.failure(FailureKind.POLICY, "pack.invalid-encode-name", failure);
    }
    return new Entry(
        name,
        identity.orElseThrow().value(),
        bytes,
        Tes3Names.hash(bytes),
        size,
        payload,
        location);
  }

  /** Counts source candidates before filtering or replacement, then admits a matching entry. */
  private static void admit(
      Entry entry,
      Map<String, Entry> overlay,
      PackRequest request,
      OperationSession operation,
      ResourceBudget budget)
      throws IOException {
    account(entry, operation, budget);
    if (included(entry.displayName, request.options())) overlay.put(entry.identity, entry);
  }

  /** Reserves candidate objects and encoded metadata before growing the source plan. */
  private static void account(Entry entry, OperationSession operation, ResourceBudget budget)
      throws IOException {
    operation.advance(ProgressMetric.ENTRIES, 1);
    budget.reserve(512L + entry.name.length * 8L, 0, 0, 0);
  }

  /** Applies union masks to the complete basename using locale-independent ASCII folding. */
  private static boolean included(String name, PackOptions options) {
    if (options.inclusionMasks().isEmpty()) return true;
    String mapped = name.replace('/', '\\');
    String basename = mapped.substring(mapped.lastIndexOf('\\') + 1);
    return options.inclusionMasks().stream().anyMatch(mask -> matches(mask, basename));
  }

  /** Uses a backtracking wildcard scan; TES3 admitted names contain only ASCII scalar values. */
  private static boolean matches(String mask, String name) {
    int[] pattern = mask.codePoints().map(PackSources::fold).toArray();
    int[] value = name.codePoints().map(PackSources::fold).toArray();
    int p = 0, n = 0, star = -1, retry = 0;
    while (n < value.length) {
      if (p < pattern.length && (pattern[p] == '?' || pattern[p] == value[n])) {
        p++;
        n++;
      } else if (p < pattern.length && pattern[p] == '*') {
        star = p++;
        retry = n;
      } else if (star >= 0) {
        p = star + 1;
        n = ++retry;
      } else return false;
    }
    while (p < pattern.length && pattern[p] == '*') p++;
    return p == pattern.length;
  }

  private static int fold(int scalar) {
    return scalar >= 'A' && scalar <= 'Z' ? scalar + 32 : scalar;
  }

  /**
   * Detached, canonical input with a repeatable bounded consumption contract.
   *
   * @param location the Operation Source the entry is read from, in PREFLIGHT: a loose file or a
   *     repacked archive at its path, or a generated source, which has no path and is located by
   *     its name. {@link #processing} locates the entry's processing failures there (ADR-0006).
   */
  public record Entry(
      String displayName,
      String identity,
      byte[] name,
      long hash,
      long size,
      Payload payload,
      IoContext location) {
    /** Requires the source location every processing failure of the entry reports. */
    public Entry {
      java.util.Objects.requireNonNull(location, "location");
    }

    /** Returns the entry's PROCESSING location at its source, for one operation-wide ordinal. */
    public IoContext processing(long ordinal) {
      return location.at(OperationPhase.PROCESSING, OptionalLong.of(ordinal));
    }
  }

  /** Owns source handles only for a synchronous consumption, including final source validation. */
  @FunctionalInterface
  public interface Payload {
    /**
     * Consumes the stable source and closes all source-owned resources before returning.
     *
     * @param processing the entry's PROCESSING location, where the payload locates failures it
     *     structures itself
     */
    void consume(Reader reader, IoContext processing) throws IOException;
  }

  /** Borrows a source channel until returning; callers must not retain or close that channel. */
  @FunctionalInterface
  public interface Reader {
    /** Reads a borrowed sequential source without retaining it after this call. */
    void read(ReadableByteChannel channel) throws IOException;
  }
}
