package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
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
   */
  public static void consume(Entry entry, Reader reader, IoContext context) throws IOException {
    try {
      entry.payload().consume(reader);
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
      throw context.failure(FailureKind.SOURCE, "operation.source-io", failure);
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
    IoContext context = IoContext.of(request.destination(), Operation.PACK);
    Map<String, Entry> overlay = new LinkedHashMap<>();
    for (PackSource source : request.sources()) {
      operation.checkpoint(OperationPhase.PREFLIGHT, OptionalLong.empty());
      if (source instanceof PackSource.GeneratedEntry generated) {
        admit(
            entry(
                generated.name(),
                generated.length(),
                reader -> consumeGenerated(generated, reader, context, request.resourceLimits()),
                context,
                encoding),
            overlay,
            request,
            operation,
            budget);
      } else if (source instanceof PackSource.NamedFile named) {
        rejectOutput(named.path(), request.destination(), context);
        admit(
            loose(named.name(), named.path(), context, encoding),
            overlay,
            request,
            operation,
            budget);
      } else if (source instanceof PackSource.DetectedPath detected) {
        Path path = detected.path().toAbsolutePath().normalize();
        rejectOutput(path, request.destination(), context);
        WindowsPathIdentity.Snapshot shape = shape(path, context);
        if (shape == null || shape.indirection())
          throw context.failure(FailureKind.SOURCE, "source.not-regular", null);
        if (shape.directory()) {
          List<Entry> discovered = new ArrayList<>();
          Files.walkFileTree(
              path,
              new SimpleFileVisitor<>() {
                /**
                 * Omits directory indirections before any descendants can enter the source plan.
                 */
                @Override
                public FileVisitResult preVisitDirectory(
                    Path directory, BasicFileAttributes attributes) throws IOException {
                  operation.checkpoint(OperationPhase.PREFLIGHT, OptionalLong.empty());
                  var current = shape(directory, context);
                  return current != null && current.directory() && !current.indirection()
                      ? FileVisitResult.CONTINUE
                      : FileVisitResult.SKIP_SUBTREE;
                }

                /**
                 * Admits only no-follow regular files after output exclusion and resource
                 * accounting.
                 */
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                  if (outputPath(file, request.destination())) return FileVisitResult.CONTINUE;
                  var current = shape(file, context);
                  if (current != null && current.regular() && !current.indirection()) {
                    Entry entry = loose(path.relativize(file).toString(), file, context, encoding);
                    // Count candidates before retaining them, even if a later overlay replaces
                    // them.
                    account(entry, operation, budget);
                    discovered.add(entry);
                  }
                  return FileVisitResult.CONTINUE;
                }
              });
          discovered.sort(Comparator.comparing(Entry::identity).thenComparing(Entry::displayName));
          for (Entry entry : discovered)
            if (included(entry.displayName, request.options())) overlay.put(entry.identity, entry);
        } else if (shape.regular()) {
          SourceFile stable = SourceFile.plan(path);
          ArchiveDetection detection = BethesdaArchives.standard().detect(path);
          if (detection.status() == DetectionStatus.UNRECOGNIZED) {
            admit(
                loose(path.getFileName().toString(), path, context, encoding),
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
                  throw context.failure(FailureKind.POLICY, "tes3.invalid-encode-name", null);
                long selected = ordinal;
                admit(
                    entry(
                        metadata.displayName(),
                        metadata.decodedSize(),
                        reader ->
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
        } else throw context.failure(FailureKind.SOURCE, "source.not-regular", null);
      }
    }
    return new ArrayList<>(overlay.values());
  }

  /** Counts parsed wire sections without charging payload bytes to a repack invocation. */
  private static long metadataExtent(ArchiveInspection inspection) {
    return switch (inspection.metadata()) {
      case ArchiveMetadata.Tes3 value -> value.dataBaseOffset();
      case ArchiveMetadata.VersionedBsa value ->
          36
              + value.folderCount() * 16
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
          24
              + value.entryCount() * 36
              + inspection.entries().stream()
                  .mapToLong(
                      entry ->
                          entry.wireNames().containsKey("complete")
                              ? 2 + entry.wireNames().get("complete").length()
                              : 0)
                  .sum();
      case ArchiveMetadata.DdsBa2 ignored ->
          24
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
   * Settles transferred generated channels explicitly so close errors remain structured evidence.
   */
  private static void consumeGenerated(
      PackSource.GeneratedEntry generated, Reader reader, IoContext context, ResourceLimits limits)
      throws IOException {
    var failures = new FailureRetention(limits, Operation.PACK);
    ReadableByteChannel channel = null;
    try {
      channel = generated.contentFactory().open();
      if (channel == null)
        throw context.failure(FailureKind.SOURCE, "source.invalid-channel", null);
      reader.read(channel);
    } catch (ArchiveException failure) {
      failures.accept(failure);
    } catch (IOException failure) {
      failures.accept(context.failure(FailureKind.SOURCE, "operation.source-io", failure));
    } catch (RuntimeException | AssertionError failure) {
      failures.accept(context.failure(FailureKind.INTERNAL, "operation.internal-failure", failure));
    } finally {
      if (channel != null) {
        try {
          channel.close();
        } catch (IOException failure) {
          failures.accept(
              new IoContext(
                      context.path(), Operation.PACK, OperationPhase.CLEANUP, context.ordinal())
                  .failure(FailureKind.SOURCE, "operation.source-io", failure));
        }
      }
    }
    if (failures.failed()) throw failures.finish(List.of());
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

  /** Obtains no-follow Windows classification, treating absent native support as a capability. */
  private static WindowsPathIdentity.Snapshot shape(Path path, IoContext context)
      throws IOException {
    try {
      return WindowsPathIdentity.inspect(path);
    } catch (UnsupportedOperationException failure) {
      throw context.failure(FailureKind.CAPABILITY, "source.identity-unavailable", failure);
    } catch (IOException | SecurityException failure) {
      throw context.failure(FailureKind.SOURCE, "source.attributes", failure);
    }
  }

  /** Plans one loose source and revalidates its identity around bounded, deny-write consumption. */
  private static Entry loose(String name, Path path, IoContext context, Charset encoding)
      throws ArchiveException {
    SourceFile source = SourceFile.plan(path);
    return entry(
        name,
        source.size(),
        reader ->
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
        context,
        encoding);
  }

  /** Strictly encodes an eligible Unicode name before it can participate in an overlay. */
  private static Entry entry(
      String name, long size, Payload source, IoContext context, Charset encoding)
      throws ArchiveException {
    var identity = NormalizedNameIdentity.from(name, encoding);
    if (identity.isEmpty())
      throw context.failure(FailureKind.POLICY, "tes3.invalid-encode-name", null);
    byte[] bytes;
    try {
      ByteBuffer encoded =
          encoding.newEncoder().encode(CharBuffer.wrap(identity.orElseThrow().value()));
      bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
    } catch (CharacterCodingException failure) {
      throw context.failure(FailureKind.POLICY, "tes3.invalid-encode-name", failure);
    }
    return new Entry(
        name, identity.orElseThrow().value(), bytes, Tes3Names.hash(bytes), size, source);
  }

  /** Counts source candidates before filtering or replacement, then admits a matching entry. */
  private static void admit(
      Entry entry,
      Map<String, Entry> overlay,
      PackRequest request,
      OperationSession operation,
      ResourceBudget budget)
      throws ArchiveException {
    account(entry, operation, budget);
    if (included(entry.displayName, request.options())) overlay.put(entry.identity, entry);
  }

  /** Reserves candidate objects and encoded metadata before growing the source plan. */
  private static void account(Entry entry, OperationSession operation, ResourceBudget budget)
      throws ArchiveException {
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

  /** Detached, canonical input with a repeatable bounded consumption contract. */
  public record Entry(
      String displayName, String identity, byte[] name, long hash, long size, Payload payload) {}

  /** Owns source handles only for a synchronous consumption, including final source validation. */
  @FunctionalInterface
  public interface Payload {
    /** Consumes the stable source and closes all source-owned resources before returning. */
    void consume(Reader reader) throws IOException;
  }

  /** Borrows a source channel until returning; callers must not retain or close that channel. */
  @FunctionalInterface
  public interface Reader {
    /** Reads a borrowed sequential source without retaining it after this call. */
    void read(ReadableByteChannel channel) throws IOException;
  }
}
