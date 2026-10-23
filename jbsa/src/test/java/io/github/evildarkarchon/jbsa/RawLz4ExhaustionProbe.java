package io.github.evildarkarchon.jbsa;

import io.github.evildarkarchon.jbsa.internal.io.Lz4Runtime;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

/**
 * Fresh-process probe for {@code RawLz4ExhaustionTest}: runs one raw-LZ4 Resource Limits or
 * resource-capacity scenario through the public archive facade and prints the outcome as canonical
 * lines. A fresh JVM lets the test pin either LZ4 provider and fix the heap, which sets the
 * internal heap ceiling (a quarter of the heap, capped at 256 MiB); never linked from production.
 */
public final class RawLz4ExhaustionProbe {
  private RawLz4ExhaustionProbe() {}

  /**
   * Runs scenario {@code args[0]} under the directory {@code args[1]} and prints {@code PROVIDER
   * <name>}, then either {@code OK} or the failure's lines. Paths under the directory print
   * relative to it.
   *
   * @throws AssertionError if the JVM's heap does not give the internal heap ceiling {@code
   *     args[2]}, because every capacity outcome depends on it
   */
  public static void main(String[] args) throws Exception {
    long ceiling = Math.min(256L << 20, Runtime.getRuntime().maxMemory() / 4);
    if (ceiling != Long.parseLong(args[2])) throw new AssertionError("heap ceiling " + ceiling);
    Path root = Path.of(args[1]).toAbsolutePath();
    List<String> lines = new ArrayList<>();
    try {
      run(args[0], root);
      lines.add("OK");
    } catch (ArchiveException failure) {
      lines.addAll(render(failure, root));
    }
    // Resolved last: the scenario itself must decide when the provider is pinned.
    System.out.println("PROVIDER " + Lz4Runtime.selected().name());
    lines.forEach(System.out::println);
  }

  /** Dispatches one named scenario; each writes only under {@code root}. */
  private static void run(String scenario, Path root) throws Exception {
    switch (scenario) {
      case "pack-scratch" -> {
        // The raw source stages first; the encoded block then breaches the scratch ceiling.
        byte[] data = compressible(1000);
        pack(generalRequest(root.resolve("scratch.ba2"), data, limits(-1, 1000)));
      }
      // A block of exactly the 16 MiB dispatch limit. The native encoder charges native memory,
      // so it fits and the readback, which declares the whole source on the heap, breaches a 16 MiB
      // heap ceiling; the portable encoder already needs about twice the block on the heap.
      case "pack-dispatch-block" ->
          pack(generalRequest(root.resolve("large.ba2"), random(16 << 20)));
      // A 1 MiB block fits either encoder. Portable readback declares raw LZ4's largest decode
      // working set on the heap, which alone exceeds a 16 MiB heap ceiling.
      case "pack-small-block" -> pack(generalRequest(root.resolve("small.ba2"), random(1 << 20)));
      case "read-general-limit" -> {
        Path archive = root.resolve("general.ba2");
        pack(generalRequest(archive, compressible(1000)));
        openFirst(archive, limits(999, -1));
      }
      case "read-dds-limit" -> {
        Path archive = root.resolve("dds.ba2");
        byte[] dds = DdsBa2PackTest.bc1(64, 64, 1, 2048);
        pack(ddsRequest(archive, dds));
        openFirst(archive, limits(dds.length - 1, -1));
      }
      case "read-general-capacity" -> {
        Path archive = root.resolve("declared.ba2");
        pack(generalRequest(archive, compressible(1000)));
        // A v3 General record starts at 36; its unpacked size lies 28 bytes in. Declaring 300 MiB
        // exceeds any internal heap ceiling, which never exceeds 256 MiB.
        try (var channel = FileChannel.open(archive, StandardOpenOption.WRITE)) {
          channel.write(
              ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 300 << 20), 64);
        }
        openFirst(archive, ResourceLimits.standard());
      }
      default -> throw new IllegalArgumentException(scenario);
    }
  }

  /** Packs with the standard control. */
  private static void pack(PackRequest request) throws ArchiveException {
    BethesdaArchives.standard().pack(request, OperationControl.standard());
  }

  /** Opens the first entry's content under the given limits and drains it. */
  private static void openFirst(Path archive, ResourceLimits limits) throws Exception {
    try (var opened =
            BethesdaArchives.standard()
                .open(archive, new OpenOptions(Optional.empty(), limits, Optional.empty()));
        var content = opened.entry(0).openContent()) {
      ByteBuffer window = ByteBuffer.allocate(65536);
      while (content.read(window.clear()) >= 0) {
        // Drains to EOF.
      }
    }
  }

  /** Standard limits with a replaced decoded or scratch ceiling; -1 keeps the standard value. */
  private static ResourceLimits limits(long maxDecodedBytes, long maxScratchBytes) {
    ResourceLimits standard = ResourceLimits.standard();
    return new ResourceLimits(
        standard.maxEntries(),
        standard.maxMetadataBytes(),
        maxDecodedBytes < 0 ? standard.maxDecodedBytes() : maxDecodedBytes,
        maxScratchBytes < 0 ? standard.maxScratchBytes() : maxScratchBytes,
        standard.maxOutputs(),
        standard.maxDiagnostics(),
        standard.maxSecondaryFailures());
  }

  /** A Starfield General v3 request with one raw-LZ4 entry under standard limits. */
  private static PackRequest generalRequest(Path target, byte[] data) {
    return generalRequest(target, data, ResourceLimits.standard());
  }

  /** A Starfield General v3 request with one raw-LZ4 entry under the given limits. */
  private static PackRequest generalRequest(Path target, byte[] data, ResourceLimits limits) {
    var standard =
        PackRequest.standard(
            target,
            ArchiveFamily.STARFIELD_GENERAL_BA2,
            new ArchiveEncoding(
                Optional.of(new WireVersion(3)), Optional.of(Ba2Subtype.GNRL), OptionalLong.of(3)),
            List.of(generated("Data/Entry.bin", data)),
            Optional.empty());
    return new PackRequest(
        standard.destination(),
        standard.family(),
        standard.encoding(),
        standard.compatibilityProfile(),
        standard.sources(),
        standard.targetPolicy(),
        standard.diagnosticPolicy(),
        limits,
        standard.workerSelection(),
        options(),
        standard.ddsTarget());
  }

  /** A Starfield DDS v3 request whose one texture is packed as raw-LZ4 chunks. */
  private static PackRequest ddsRequest(Path target, byte[] dds) {
    var standard =
        PackRequest.standard(
            target,
            ArchiveFamily.STARFIELD_DDS_BA2,
            new ArchiveEncoding(
                Optional.of(new WireVersion(3)), Optional.of(Ba2Subtype.DX10), OptionalLong.of(3)),
            List.of(generated("Textures/Example.dds", dds)),
            Optional.of(DdsTarget.PC));
    return new PackRequest(
        standard.destination(),
        standard.family(),
        standard.encoding(),
        standard.compatibilityProfile(),
        standard.sources(),
        standard.targetPolicy(),
        standard.diagnosticPolicy(),
        standard.resourceLimits(),
        standard.workerSelection(),
        options(),
        standard.ddsTarget());
  }

  /** Raw-LZ4 compression without sharing or splitting. */
  private static PackOptions options() {
    return new PackOptions(
        List.of(),
        PackOptions.Compression.LZ4_RAW,
        false,
        new PackOptions.Splitting.UpToBytes(0),
        FlagSelection.AUTOMATIC,
        FlagSelection.AUTOMATIC);
  }

  /** Supplies a fresh channel with the declared exact source length. */
  private static PackSource generated(String name, byte[] bytes) {
    return new PackSource.GeneratedEntry(
        name, bytes.length, () -> Channels.newChannel(new ByteArrayInputStream(bytes)));
  }

  /** Deterministic bytes that raw LZ4 compresses, so the archive keeps a packed record. */
  private static byte[] compressible(int size) {
    byte[] data = new byte[size];
    for (int index = 0; index < size; index++) data[index] = (byte) ("raw-lz4".charAt(index % 7));
    return data;
  }

  /** Deterministic incompressible bytes, so the encoded block stays near its worst case. */
  private static byte[] random(int size) {
    byte[] data = new byte[size];
    new SplittableRandom(93).nextBytes(data);
    return data;
  }

  /**
   * Renders the kind, Primary Failure, each Conformance Diagnostic and each Secondary Failure in
   * order. Locations print every field, so a moved failure changes the output.
   */
  static List<String> render(ArchiveException failure, Path root) {
    List<String> lines = new ArrayList<>();
    lines.add("FAILURE " + failure.kind());
    lines.add("PRIMARY " + failure(failure.primaryFailure(), root));
    for (Diagnostic diagnostic : failure.diagnostics())
      lines.add(
          "DIAGNOSTIC "
              + diagnostic.identifier()
              + " "
              + diagnostic.severity()
              + " "
              + diagnostic.operation()
              + " "
              + diagnostic.phase()
              + " "
              + location(diagnostic.location(), root)
              + " "
              + values(diagnostic.values()));
    for (Failure secondary : failure.secondaryFailures())
      lines.add("SECONDARY " + failure(secondary, root));
    return lines;
  }

  /** Renders one failure record without its provider-specific cause. */
  private static String failure(Failure failure, Path root) {
    return failure.kind()
        + " "
        + failure.phase()
        + " ordinal="
        + (failure.ordinal().isPresent() ? failure.ordinal().getAsLong() : "-")
        + " id="
        + failure.diagnosticIdentifier().orElse("-")
        + " "
        + failure.location().map(location -> location(location, root)).orElse("location=-");
  }

  /** Renders every location field, with {@code -} for an absent one. */
  private static String location(DiagnosticLocation location, Path root) {
    return "[archive="
        + location.archive().map(path -> path(path, root)).orElse("-")
        + " entry="
        + (location.entryOrdinal().isPresent() ? location.entryOrdinal().getAsLong() : "-")
        + " name="
        + location.entryName().orElse("-")
        + " field="
        + location.field().orElse("-")
        + " span="
        + location.byteSpan().map(span -> span.offset() + "+" + span.length()).orElse("-")
        + " artifact="
        + location.artifact().map(path -> path(path, root)).orElse("-")
        + "]";
  }

  /** Prints a path relative to the scenario directory, or marks it as outside it. */
  private static String path(Path path, Path root) {
    Path absolute = path.toAbsolutePath();
    return absolute.startsWith(root)
        ? root.relativize(absolute).toString().replace('\\', '/')
        : "<outside>/" + absolute.getFileName();
  }

  /** Renders values in their canonical sorted order. */
  private static String values(Map<String, String> values) {
    return values.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining(",", "{", "}"));
  }
}
