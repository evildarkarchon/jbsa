package io.github.evildarkarchon.jbsa.cli;

import io.github.evildarkarchon.jbsa.ArchiveEncoding;
import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.ArchiveFamily;
import io.github.evildarkarchon.jbsa.ArchiveInspection;
import io.github.evildarkarchon.jbsa.ArchiveMetadata;
import io.github.evildarkarchon.jbsa.Artifact;
import io.github.evildarkarchon.jbsa.ArtifactState;
import io.github.evildarkarchon.jbsa.BethesdaArchives;
import io.github.evildarkarchon.jbsa.CompatibilityProfile;
import io.github.evildarkarchon.jbsa.DdsTarget;
import io.github.evildarkarchon.jbsa.DetectionStatus;
import io.github.evildarkarchon.jbsa.Diagnostic;
import io.github.evildarkarchon.jbsa.DiagnosticPolicy;
import io.github.evildarkarchon.jbsa.DiagnosticSeverity;
import io.github.evildarkarchon.jbsa.EntryMetadata;
import io.github.evildarkarchon.jbsa.EntrySelection;
import io.github.evildarkarchon.jbsa.ExtractRequest;
import io.github.evildarkarchon.jbsa.Failure;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.OpenOptions;
import io.github.evildarkarchon.jbsa.OperationControl;
import io.github.evildarkarchon.jbsa.OperationReport;
import io.github.evildarkarchon.jbsa.PackOptions;
import io.github.evildarkarchon.jbsa.PackRequest;
import io.github.evildarkarchon.jbsa.PackSource;
import io.github.evildarkarchon.jbsa.ResourceLimits;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Launches the thin JBSA command-line consumer. */
public final class Main {
  private Main() {}

  /**
   * Starts the CLI and exits with its process-level outcome.
   *
   * @param arguments command-line arguments with path spelling retained
   */
  static void main(String[] arguments) {
    PrintStream output = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    PrintStream error = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    int status;
    try {
      Invocation invocation = Invocation.parse(arguments);
      status = execute(invocation, output, error);
    } catch (IllegalArgumentException invalid) {
      error.println("Error: [invocation] " + invalid.getMessage());
      status = 2;
    }
    System.exit(status);
  }

  /**
   * Invokes only the public archive facade and maps its settled outcome to process observations.
   */
  private static int execute(Invocation invocation, PrintStream output, PrintStream error) {
    BethesdaArchives archives = BethesdaArchives.standard();
    OpenOptions options =
        new OpenOptions(invocation.profile(), ResourceLimits.standard(), Optional.empty());
    PrintStream diagnostics = invocation.profile().isPresent() ? output : error;
    try {
      switch (invocation.operation()) {
        case "help" -> help(output);
        case "version" -> {
          String version =
              Main.class.getModule().getDescriptor().rawVersion().orElse("development");
          output.println("JBSA " + version);
          for (CompatibilityProfile profile : CompatibilityProfile.values()) {
            output.println(profile.identifier() + " SHA-256 " + profile.contentDigest());
          }
        }
        case "inspect" -> {
          ArchiveInspection inspection = archives.inspect(invocation.archive(), options);
          renderInspection(invocation, inspection, options, output);
          renderDiagnostics(inspection.assessment().diagnostics(), diagnostics);
        }
        case "pack", "unpack" -> {
          if (invocation.operation().equals("unpack")
              && !Files.isDirectory(invocation.destination())) {
            diagnostics.println(
                "Error: [destination] destination="
                    + invocation.destination()
                    + " expected=existing-directory");
            return 1;
          }
          OperationReport report;
          try (MutationControl mutation = new MutationControl()) {
            report =
                invocation.operation().equals("pack")
                    ? archives.pack(
                        new PackRequest(
                            invocation.archive(),
                            invocation.family(),
                            packEncoding(invocation),
                            invocation.profile(),
                            packSources(invocation, archives, options),
                            invocation.targetPolicy(),
                            DiagnosticPolicy.standard(),
                            ResourceLimits.standard(),
                            invocation.workers(),
                            invocation.packOptions(),
                            (invocation.family() == ArchiveFamily.FO4_DDS_BA2
                                    || invocation.family() == ArchiveFamily.STARFIELD_DDS_BA2)
                                ? Optional.of(io.github.evildarkarchon.jbsa.DdsTarget.PC)
                                : Optional.empty()),
                        mutation.control())
                    : archives.extract(
                        new ExtractRequest(
                            invocation.archive(),
                            invocation.destination(),
                            EntrySelection.ALL,
                            invocation.targetPolicy(),
                            DiagnosticPolicy.standard(),
                            invocation.workers(),
                            options),
                        mutation.control());
          }
          if (invocation.operation().equals("unpack")) {
            output.println("Destination: " + invocation.destination().toAbsolutePath().normalize());
            output.println(
                "Published entries: "
                    + report.artifacts().stream()
                        .filter(artifact -> artifact.state() == ArtifactState.PUBLISHED)
                        .count());
          } else {
            if (invocation.family() == ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA
                || invocation.family() == ArchiveFamily.SSE_BSA) {
              output.println("Selector: " + invocation.familySelector());
            }
            for (OperationReport.ArchivePart part : report.archiveParts()) {
              output.println(
                  "Archive part: "
                      + part.path().toAbsolutePath().normalize()
                      + " bytes="
                      + part.byteSize()
                      + " entries="
                      + part.entryCount());
            }
          }
          for (Artifact artifact : report.artifacts()) {
            output.println(
                "Artifact: ordinal="
                    + artifact.ordinal()
                    + " state="
                    + artifact.state()
                    + " path="
                    + artifact.path().toAbsolutePath().normalize());
          }
          renderDiagnostics(report.diagnostics(), diagnostics);
        }
        default -> throw new IllegalArgumentException("Unsupported operation");
      }
      return 0;
    } catch (ArchiveException failure) {
      renderFailure(failure.primaryFailure(), "primary", diagnostics);
      for (Failure secondary : failure.secondaryFailures()) {
        renderFailure(secondary, "secondary", diagnostics);
      }
      renderDiagnostics(failure.diagnostics(), diagnostics);
      for (Artifact artifact : failure.artifacts()) {
        diagnostics.println(
            "Artifact: ordinal="
                + artifact.ordinal()
                + " state="
                + artifact.state()
                + " path="
                + artifact.path());
      }
      // The qualified archive-information quirk changes only the process status, not failure
      // records.
      if (invocation.operation().equals("inspect") && invocation.profile().isPresent()) return 0;
      return failure.kind() == FailureKind.CANCELLED ? 130 : 1;
    }
  }

  /**
   * Omits unusable declared paths only for the CLI compatibility profile. Output aliases remain so
   * library preflight can reject them, including aliases that do not exist yet. The library still
   * expands directories, resolves overlays, and rejects an empty final entry set.
   *
   * @return surviving sources in their original operand order
   */
  private static List<PackSource> packSources(
      Invocation invocation, BethesdaArchives archives, OpenOptions options) {
    if (invocation.profile().isEmpty()) return invocation.sources();
    List<PackSource> usable = new ArrayList<>();
    for (PackSource source : invocation.sources()) {
      if (!(source instanceof PackSource.DetectedPath detected)) {
        usable.add(source);
        continue;
      }
      Path path = detected.path();
      // An output alias must reach library preflight even if it does not exist yet.
      if (outputPath(path, invocation.archive())) {
        usable.add(source);
      } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(path)) {
        usable.add(source);
      } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(path)) {
        try {
          DetectionStatus status = archives.detect(path).status();
          if (status == DetectionStatus.UNRECOGNIZED
              || (status == DetectionStatus.SUPPORTED_FAMILY
                  && !archives.inspect(path, options).entries().isEmpty())) {
            usable.add(source);
          }
        } catch (ArchiveException unusableSource) {
          // This profile omits a source whose bounded archive preflight cannot succeed.
        }
      }
    }
    return List.copyOf(usable);
  }

  /**
   * Recognizes the requested target and numbered siblings so absent aliases are never omitted
   * before the library's output-overlap preflight.
   */
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

  /** Maps one validated CLI family/codec choice to its independent wire selectors. */
  private static ArchiveEncoding packEncoding(Invocation invocation) {
    return switch (invocation.family()) {
      case TES3_BSA -> ArchiveEncoding.tes3();
      case TES4_BSA -> versionedEncoding(0x67);
      case FO3_FNV_SKYRIM_LE_BSA -> versionedEncoding(0x68);
      case SSE_BSA -> versionedEncoding(0x69);
      case FO4_GENERAL_BA2 ->
          new ArchiveEncoding(
              Optional.of(new io.github.evildarkarchon.jbsa.WireVersion(1)),
              Optional.of(io.github.evildarkarchon.jbsa.Ba2Subtype.GNRL),
              java.util.OptionalLong.empty());
      case FO4_DDS_BA2 ->
          new ArchiveEncoding(
              Optional.of(new io.github.evildarkarchon.jbsa.WireVersion(1)),
              Optional.of(io.github.evildarkarchon.jbsa.Ba2Subtype.DX10),
              java.util.OptionalLong.empty());
      case STARFIELD_GENERAL_BA2 -> {
        boolean raw = invocation.packOptions().compression() == PackOptions.Compression.LZ4_RAW;
        yield new ArchiveEncoding(
            Optional.of(new io.github.evildarkarchon.jbsa.WireVersion(raw ? 3 : 2)),
            Optional.of(io.github.evildarkarchon.jbsa.Ba2Subtype.GNRL),
            raw ? java.util.OptionalLong.of(3) : java.util.OptionalLong.empty());
      }
      case STARFIELD_DDS_BA2 -> {
        boolean raw = invocation.packOptions().compression() == PackOptions.Compression.LZ4_RAW;
        yield new ArchiveEncoding(
            Optional.of(new io.github.evildarkarchon.jbsa.WireVersion(raw ? 3 : 2)),
            Optional.of(io.github.evildarkarchon.jbsa.Ba2Subtype.DX10),
            raw ? java.util.OptionalLong.of(3) : java.util.OptionalLong.empty());
      }
    };
  }

  /** Constructs selector-only Versioned BSA encoding for a validated family choice. */
  private static ArchiveEncoding versionedEncoding(long version) {
    return new ArchiveEncoding(
        Optional.of(new io.github.evildarkarchon.jbsa.WireVersion(version)),
        Optional.empty(),
        java.util.OptionalLong.empty());
  }

  /** Renders stable archive headers and serialized entry facts from detached library metadata. */
  private static void renderInspection(
      Invocation invocation,
      ArchiveInspection inspection,
      OpenOptions options,
      PrintStream output) {
    output.println("Archive: " + invocation.archive());
    output.println("Family: " + inspection.metadata().family());
    output.println("Entries: " + inspection.metadata().entryCount());
    long compressed =
        inspection.entries().stream()
            .filter(
                entry ->
                    (entry.facts() instanceof EntryMetadata.VersionedBsa facts
                            && facts.compressed())
                        || (entry.facts() instanceof EntryMetadata.GeneralBa2 general
                            && general.packedSize() != 0)
                        || (entry.facts() instanceof EntryMetadata.DdsBa2 dds
                            && dds.chunks().stream().anyMatch(chunk -> chunk.packedSize() != 0)))
            .count();
    output.println("Compressed entries: " + compressed);
    output.println(
        "Codec: "
            + (compressed == 0
                ? "STORED"
                : inspection.metadata().encoding().compressionMethod().orElse(-1) == 3
                    ? "LZ4_RAW"
                    : inspection.metadata().family() == ArchiveFamily.SSE_BSA
                        ? "LZ4_FRAME"
                        : "ZLIB"));
    if (inspection.metadata() instanceof ArchiveMetadata.DdsBa2 metadata) {
      output.println("Version: " + metadata.encoding().wireVersion().orElseThrow().value());
      output.println("Subtype: DX10");
      output.println("Target: " + reconstructionTarget(invocation.archive(), options));
      output.println("Filename table offset: " + metadata.fileNameTableOffset());
    }
    if (inspection.metadata() instanceof ArchiveMetadata.GeneralBa2 metadata) {
      output.println("Version: " + metadata.encoding().wireVersion().orElseThrow().value());
      output.println("Subtype: " + metadata.encoding().ba2Subtype().orElseThrow().value());
      output.println("Filename table offset: " + metadata.fileNameTableOffset());
    }
    if (inspection.metadata() instanceof ArchiveMetadata.Tes3 metadata) {
      output.println("Hash offset: " + metadata.hashOffset());
      output.println("Data base offset: " + metadata.dataBaseOffset());
    }
    if (inspection.metadata() instanceof ArchiveMetadata.VersionedBsa metadata) {
      output.println("Version: " + metadata.encoding().wireVersion().orElseThrow().value());
      output.println("Folders: " + metadata.folderCount());
      output.println("Folder records offset: " + metadata.folderRecordsOffset());
      output.println(
          "Archive flags: "
              + Long.toHexString(metadata.archiveFlags()).toUpperCase(java.util.Locale.ROOT));
      output.println(
          "File flags: "
              + Long.toHexString(metadata.fileFlags()).toUpperCase(java.util.Locale.ROOT));
      output.println("Folder names length: " + metadata.folderNamesLength());
      output.println("File names length: " + metadata.fileNamesLength());
    }
    if (invocation.list()) {
      for (EntryMetadata entry : inspection.entries()) {
        output.println(entry.displayName());
        if (invocation.dump() && entry.facts() instanceof EntryMetadata.DdsBa2 facts) {
          output.println("  Ordinal: " + entry.ordinal());
          output.println("  Basename hash: " + Long.toHexString(facts.identity().baseNameHash()));
          output.println("  Directory hash: " + Long.toHexString(facts.identity().directoryHash()));
          output.println(
              "  Extension bytes: "
                  + java.util.HexFormat.of().formatHex(facts.identity().extension().bytes()));
          output.println("  Mod index: " + facts.identity().modIndex());
          output.println("  Chunk count: " + facts.identity().chunkCount());
          output.println("  Chunk header size: " + facts.identity().chunkHeaderSize());
          output.println("  Dimensions: " + facts.width() + "x" + facts.height());
          output.println("  DXGI format: " + facts.dxgiFormat());
          output.println("  Cubemap: " + ((facts.flags() & 1) != 0));
          output.println("  Tile mode: " + facts.tileMode());
          output.println("  Mip count: " + facts.mipCount());
          output.println("  Decoded size: " + entry.decodedSize());
          output.println("  Stored size: " + entry.storedSize());
          for (var chunk : facts.chunks()) {
            output.println("    Data offset: " + chunk.payloadOffset());
            output.println("    Packed size: " + chunk.packedSize());
            output.println("    Decoded size: " + chunk.unpackedSize());
            output.println("    Compressed: " + (chunk.packedSize() != 0));
            output.println("    Mip range: " + chunk.startMip() + ".." + chunk.endMip());
            output.println("    Sentinel: " + Long.toHexString(chunk.sentinel()));
          }
        }
        if (invocation.dump() && entry.facts() instanceof EntryMetadata.GeneralBa2 facts) {
          output.println("  Ordinal: " + entry.ordinal());
          output.println("  Basename hash: " + Long.toHexString(facts.identity().baseNameHash()));
          output.println("  Directory hash: " + Long.toHexString(facts.identity().directoryHash()));
          output.println(
              "  Extension bytes: "
                  + java.util.HexFormat.of().formatHex(facts.identity().extension().bytes()));
          output.println("  Mod index: " + facts.identity().modIndex());
          output.println("  Chunk count: " + facts.identity().chunkCount());
          output.println("  Chunk header size: " + facts.identity().chunkHeaderSize());
          output.println("  Data offset: " + facts.payloadOffset());
          output.println("  Packed size: " + facts.packedSize());
          output.println("  Decoded size: " + facts.unpackedSize());
          output.println("  Sentinel: " + Long.toHexString(facts.sentinel()));
        }
        if (invocation.dump() && entry.facts() instanceof EntryMetadata.Tes3 facts) {
          output.println("  Ordinal: " + entry.ordinal());
          output.println(
              "  Name hash: "
                  + Long.toUnsignedString(facts.nameHash(), 16).toUpperCase(java.util.Locale.ROOT));
          output.println("  Decoded size: " + entry.decodedSize());
          output.println("  Stored size: " + entry.storedSize());
          output.println("  Name offset: " + facts.nameOffset());
          output.println("  Relative data offset: " + facts.relativeDataOffset());
          output.println("  Data offset: " + facts.dataOffset());
          output.println("  Compressed: false");
        }
        if (invocation.dump() && entry.facts() instanceof EntryMetadata.VersionedBsa facts) {
          output.println("  Ordinal: " + entry.ordinal());
          output.println("  Folder ordinal: " + facts.folderOrdinal());
          output.println(
              "  Folder hash: "
                  + Long.toUnsignedString(facts.folderHash(), 16)
                      .toUpperCase(java.util.Locale.ROOT));
          output.println(
              "  Name hash: "
                  + Long.toUnsignedString(facts.nameHash(), 16).toUpperCase(java.util.Locale.ROOT));
          output.println("  Folder offset: " + facts.folderOffset());
          output.println("  Folder padding before offset: " + facts.folderPaddingBeforeOffset());
          output.println("  Folder padding after offset: " + facts.folderPaddingAfterOffset());
          output.println("  Decoded size: " + entry.decodedSize());
          output.println("  Stored size: " + entry.storedSize());
          output.println("  Size and compression toggle: " + facts.sizeAndCompressionToggle());
          output.println("  Data offset: " + facts.dataOffset());
          output.println("  Compressed: " + facts.compressed());
        }
      }
    }
  }

  /**
   * Reports the same explicit-or-qualified DDS reconstruction choice used by the archive reader.
   */
  private static DdsTarget reconstructionTarget(Path archive, OpenOptions options) {
    if (options.ddsTarget().isPresent()) return options.ddsTarget().orElseThrow();
    return options.compatibilityProfile().isPresent()
            && archive.getFileName().toString().toLowerCase(Locale.ROOT).contains("_xbox.")
        ? DdsTarget.XBOX
        : DdsTarget.PC;
  }

  /** Preserves library diagnostic order, severity, structured locations and canonical values. */
  private static void renderDiagnostics(List<Diagnostic> diagnostics, PrintStream stream) {
    for (Diagnostic diagnostic : diagnostics) {
      stream.println(
          (diagnostic.severity() == DiagnosticSeverity.WARNING ? "Warning: [" : "Error: [")
              + diagnostic.identifier()
              + "] operation="
              + diagnostic.operation()
              + " phase="
              + diagnostic.phase()
              + " location="
              + diagnostic.location()
              + " values="
              + diagnostic.values());
    }
  }

  /** Presents semantic failure fields without relying on provider exception messages. */
  private static void renderFailure(Failure failure, String role, PrintStream stream) {
    stream.println(
        "Error: ["
            + failure.kind().name().toLowerCase(java.util.Locale.ROOT)
            + "] role="
            + role
            + " phase="
            + failure.phase()
            + " ordinal="
            + failure.ordinal()
            + " diagnostic="
            + failure.diagnosticIdentifier()
            + " location="
            + failure.location());
  }

  /** Prints the supported archive invocation forms and family-specific option names. */
  private static void help(PrintStream output) {
    output.println("Fallout 4 DDS BA2 pack: -fo4dds [-z|-z:zlib] (PC DDS only; always compressed)");
    output.println(
        "Fallout 4 General BA2 pack: -fo4 [-z|-z:zlib] -split:0..8 -share:yes|no -mt:yes|no -f:mask[,mask]");
    output.println(
        "Starfield General BA2 pack: -sf1 [-z|-z:zlib|-z:lz4] -split:0..8 -share:yes|no -mt:yes|no -f:mask[,mask]");
    output.println(
        "Starfield DDS BA2 pack: -sf1dds [-z|-z:zlib|-z:lz4] (PC DDS only; always compressed)");
    output.println(
        "jbsa [--compatibility-profile=bsarch-1.0/v1] pack <source1+source2+...> <archive> [options]");
    output.println(
        "jbsa [--compatibility-profile=bsarch-1.0/v1] unpack <archive> [existing-directory] [options]");
    output.println("jbsa [--compatibility-profile=bsarch-1.0/v1] <archive> [-list] [-dump]");
    output.println("TES3 pack: -tes3 -split:0..8 -share:yes|no -mt:yes|no -f:mask[,mask]");
    output.println(
        "TES4 pack: -tes4 [-z|-z:zlib] [-af:hex] [-ff:hex] -split:0..8 -share:yes|no -mt:yes|no -f:mask[,mask]");
    output.println(
        "FO3/FNV/Skyrim LE pack: -fo3|-fnv|-tes5 [-z|-z:zlib] [-af:hex] [-ff:hex] -split:0..8 -share:yes|no -mt:yes|no -f:mask[,mask]");
    output.println(
        "SSE/Skyrim AE pack: -sse [-z|-z:lz4f] [-af:hex] [-ff:hex] -split:0..8 -share:yes|no -mt:yes|no -f:mask[,mask]");
    output.println("Mutations: --replace --no-progress; administration: --help --version");
  }

  /**
   * Keeps JVM shutdown cooperative until publication and cleanup settle; launcher signals are
   * separate.
   */
  private static final class MutationControl implements AutoCloseable {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CountDownLatch settled = new CountDownLatch(1);
    private final Thread shutdown;
    private final OperationControl control;

    /** Registers operation-scoped cancellation without interrupting library workers. */
    MutationControl() {
      shutdown =
          new Thread(
              () -> {
                cancelled.set(true);
                boolean interrupted = false;
                while (settled.getCount() != 0) {
                  try {
                    settled.await();
                  } catch (InterruptedException exception) {
                    interrupted = true;
                  }
                }
                if (interrupted) {
                  Thread.currentThread().interrupt();
                }
              },
              "jbsa-cancellation");
      Runtime.getRuntime().addShutdownHook(shutdown);
      // Shutdown hooks preserve cleanup, but packaged Ctrl+C exit-status handling belongs to the
      // launcher.
      control =
          new OperationControl(
              snapshot -> {
                // Rendering is optional. A packaged renderer must establish stderr console
                // attachment independently; System.console() alone does not prove it.
              },
              cancelled::get);
    }

    OperationControl control() {
      return control;
    }

    /** Releases an in-flight shutdown hook before the caller can invoke System.exit. */
    @Override
    public void close() {
      settled.countDown();
      try {
        Runtime.getRuntime().removeShutdownHook(shutdown);
      } catch (IllegalStateException exception) {
        // Shutdown has already started; the released hook now observes the settled operation.
      }
    }
  }
}
