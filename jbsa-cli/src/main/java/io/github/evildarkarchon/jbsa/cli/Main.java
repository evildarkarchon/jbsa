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
import io.github.evildarkarchon.jbsa.PackRequest;
import io.github.evildarkarchon.jbsa.ResourceLimits;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
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
          renderInspection(invocation, inspection, output);
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
                            ArchiveFamily.TES3_BSA,
                            ArchiveEncoding.tes3(),
                            invocation.profile(),
                            invocation.sources(),
                            invocation.targetPolicy(),
                            DiagnosticPolicy.standard(),
                            ResourceLimits.standard(),
                            invocation.workers(),
                            invocation.packOptions(),
                            Optional.empty()),
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
      return failure.kind() == FailureKind.CANCELLED ? 130 : 1;
    }
  }

  /** Renders stable TES3 header and archive-order entry facts from one detached inspection. */
  private static void renderInspection(
      Invocation invocation, ArchiveInspection inspection, PrintStream output) {
    output.println("Archive: " + invocation.archive());
    output.println("Family: " + inspection.metadata().family());
    output.println("Entries: " + inspection.metadata().entryCount());
    output.println("Compressed entries: 0");
    output.println("Codec: STORED");
    if (inspection.metadata() instanceof ArchiveMetadata.Tes3 metadata) {
      output.println("Hash offset: " + metadata.hashOffset());
      output.println("Data base offset: " + metadata.dataBaseOffset());
    }
    if (invocation.list()) {
      for (EntryMetadata entry : inspection.entries()) {
        output.println(entry.displayName());
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
      }
    }
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

  /** Prints the stable supported TES3 invocation forms and option names. */
  private static void help(PrintStream output) {
    output.println(
        "jbsa [--compatibility-profile=bsarch-1.0/v1] pack <source1+source2+...> <archive> [options]");
    output.println(
        "jbsa [--compatibility-profile=bsarch-1.0/v1] unpack <archive> [existing-directory] [options]");
    output.println("jbsa [--compatibility-profile=bsarch-1.0/v1] <archive> [-list] [-dump]");
    output.println("TES3 pack: -tes3 -split:0..8 -share:yes|no -mt:yes|no -f:mask[,mask]");
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
