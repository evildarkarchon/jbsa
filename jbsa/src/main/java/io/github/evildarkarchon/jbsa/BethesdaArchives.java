package io.github.evildarkarchon.jbsa;

import io.github.evildarkarchon.jbsa.internal.io.ArchiveInput;
import io.github.evildarkarchon.jbsa.internal.io.OperationSession;
import io.github.evildarkarchon.jbsa.internal.io.OwnedArchive;
import io.github.evildarkarchon.jbsa.internal.tes3.Tes3Reader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The synchronous, stateless Bethesda Archive module, at pre-1.0 Contract Baseline.
 *
 * <p>Queries check source access and selectors before family dispatch. TES3 supports structural
 * inspection, owned stored content, extraction, and packing; other family slices report checked
 * capability failures. This object owns no resource lifetime.
 */
public final class BethesdaArchives {
  private static final BethesdaArchives STANDARD = new BethesdaArchives();

  private BethesdaArchives() {}

  /** Returns the sole stateless module instance; callers must not close it. */
  public static BethesdaArchives standard() {
    return STANDARD;
  }

  /**
   * Recognizes only the bounded binary selectors, independently of the file's name or extension.
   *
   * @param path source file, opened and closed within this call
   * @return detached recognition, without an Archive Disposition or an encode-support claim
   * @throws ArchiveException on source I/O failure or unavailable input-sharing capability
   */
  public ArchiveDetection detect(Path path) throws ArchiveException {
    Objects.requireNonNull(path, "path");
    // The largest identifying selector is the Starfield v3 method at offset 32. Never read
    // payloads.
    ByteBuffer prefix;
    try (ArchiveInput source = ArchiveInput.open(path, Operation.DETECT)) {
      prefix = ByteBuffer.allocate((int) Math.min(36L, source.size()));
      source.readExact(0, prefix);
    } catch (ArchiveException cause) {
      throw cause;
    } catch (IOException cause) {
      throw failure(Operation.DETECT, FailureKind.SOURCE, "operation.source-io", path, cause);
    }
    return Detection.recognize(Arrays.copyOf(prefix.array(), prefix.position()));
  }

  /**
   * Inspects structure using exactly {@link OpenOptions#standard()}, with no environment inference.
   *
   * @throws ArchiveException if the source cannot be inspected
   */
  public ArchiveInspection inspect(Path path) throws ArchiveException {
    return inspect(path, OpenOptions.standard());
  }

  /**
   * Returns detached structural inspection without decoding every payload.
   *
   * @throws ArchiveException if the source cannot be inspected or its family parser is unavailable
   */
  public ArchiveInspection inspect(Path path, OpenOptions options) throws ArchiveException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(options, "options");
    try (OpenArchive archive = query(Operation.INSPECT, path, options)) {
      return archive.inspection();
    } catch (ArchiveException failure) {
      throw failure;
    } catch (IOException cause) {
      throw failure(Operation.INSPECT, FailureKind.SOURCE, "operation.source-io", path, cause);
    }
  }

  /**
   * Opens a caller-owned archive; the caller must close the result after all child content reads.
   *
   * @throws ArchiveException if the archive cannot be opened or its family parser is unavailable
   */
  public OpenArchive open(Path path, OpenOptions options) throws ArchiveException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(options, "options");
    return query(Operation.OPEN, path, options);
  }

  /** Recognizes and loads from one owned handle, closing it on every unsuccessful dispatch. */
  private static OpenArchive query(Operation operation, Path path, OpenOptions options)
      throws ArchiveException {
    try {
      return OwnedArchive.load(
          path,
          options.resourceLimits(),
          operation,
          builder -> {
            ArchiveDetection detection =
                Detection.recognize(
                    builder.readSelectors((int) Math.min(4, builder.size())).array());
            if (detection.family().filter(ArchiveFamily.TES3_BSA::equals).isPresent()) {
              return Tes3Reader.load(builder, path, options, operation);
            }
            detection =
                Detection.recognize(
                    builder.readSelectors((int) Math.min(36, builder.size())).array());
            throw switch (detection.status()) {
              case UNRECOGNIZED ->
                  failure(operation, FailureKind.FORMAT, "archive.unrecognized", path, null);
              case INDETERMINATE ->
                  failure(operation, FailureKind.FORMAT, "archive.incomplete-selector", path, null);
              case UNSUPPORTED_VARIANT ->
                  failure(
                      operation,
                      FailureKind.UNSUPPORTED,
                      "archive.unsupported-variant",
                      path,
                      null);
              case SUPPORTED_FAMILY -> unavailable(operation, path);
            };
          });
    } catch (ArchiveException failure) {
      throw failure;
    } catch (IOException cause) {
      throw failure(operation, FailureKind.SOURCE, "operation.source-io", path, cause);
    }
  }

  /**
   * Extracts selected entries synchronously under the request's policy and operation control.
   *
   * @throws ArchiveException on cancellation, observer failure, or unavailable family capability
   */
  public OperationReport extract(ExtractRequest request, OperationControl control)
      throws ArchiveException {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(control, "control");
    return io.github.evildarkarchon.jbsa.internal.tes3.Tes3Extractor.extract(request, control);
  }

  /**
   * Packs ordered overlay sources synchronously under the request's policy and operation control.
   * Generated channel ownership transfers to JBSA when a factory is invoked.
   *
   * @throws ArchiveException on cancellation, observer failure, or unavailable family capability
   */
  public OperationReport pack(PackRequest request, OperationControl control)
      throws ArchiveException {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(control, "control");
    if (request.family() == ArchiveFamily.TES3_BSA)
      return io.github.evildarkarchon.jbsa.internal.tes3.Tes3Packer.pack(request, control);
    return unavailableMutation(
        Operation.PACK,
        request.destination(),
        request.resourceLimits(),
        request.diagnosticPolicy(),
        control);
  }

  /** Applies shared outcome and progress semantics while family execution remains unavailable. */
  private static OperationReport unavailableMutation(
      Operation operation,
      Path path,
      ResourceLimits limits,
      DiagnosticPolicy policy,
      OperationControl control)
      throws ArchiveException {
    var session = new OperationSession(operation, limits, policy, control);
    try {
      session.begin();
      session.accept(unavailable(operation, path));
    } catch (ArchiveException failure) {
      // begin() has already accepted cancellation or observer failure into this invocation.
    } finally {
      session.cleanup();
      session.cleaned(0);
    }
    return session.finish(List.of());
  }

  /** Keeps an unimplemented family slice distinct from unsupported encoded semantics. */
  private static ArchiveException unavailable(Operation operation, Path path) {
    return failure(
        operation, FailureKind.CAPABILITY, "baseline.archive-operation-unavailable", path, null);
  }

  /**
   * Constructs detached, structured baseline failures without creating output or retaining handles.
   */
  private static ArchiveException failure(
      Operation operation, FailureKind kind, String identifier, Path path, Throwable cause) {
    DiagnosticLocation location =
        new DiagnosticLocation(
            operation == Operation.PACK ? Optional.empty() : Optional.of(path),
            OptionalLong.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            operation == Operation.PACK ? Optional.of(path) : Optional.empty());
    Diagnostic diagnostic =
        new Diagnostic(
            identifier,
            DiagnosticSeverity.ERROR,
            operation,
            OperationPhase.PREFLIGHT,
            location,
            new java.util.TreeMap<>(),
            Optional.empty());
    Failure primary =
        new Failure(
            kind,
            OperationPhase.PREFLIGHT,
            OptionalLong.empty(),
            Optional.of(identifier),
            Optional.of(location),
            Optional.ofNullable(cause));
    return new ArchiveException(
        identifier, primary, List.of(diagnostic), List.of(), Optional.empty(), List.of());
  }
}
