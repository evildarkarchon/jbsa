package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Versioned-BSA LZ4-frame profile layered on the qualified shared Windows x64 runtime. */
public final class BsaLz4Frame {
  public static final String PROFILE = "jbsa-bsa-069-lz4-v1";

  private BsaLz4Frame() {}

  /** Checks the shared native runtime while binding capability evidence to the BSA profile. */
  public static void preflight(String direction, IoContext context) throws ArchiveException {
    try {
      Lz4Runtime.preflight("lz4-frame", direction, context);
    } catch (ArchiveException failure) {
      throw reprofile(failure);
    }
  }

  /** Encodes one exact level-12 independent-block frame and binds failures to this profile. */
  public static long encode(
      JdkZlib.ByteSource source,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context)
      throws IOException {
    try {
      preflight("encode", context);
      return Lz4Frame.encodeBsa(source, decodedSize, sink, checkpoint, budget, context);
    } catch (ArchiveException failure) {
      throw reprofile(failure);
    }
  }

  /** Borrows a worker's full codec reservation while retaining the BSA profile diagnostics. */
  public static long encodePrecharged(
      JdkZlib.ByteSource source,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      ResourceBudget.Lease admission,
      IoContext context)
      throws IOException {
    try {
      preflight("encode", context);
      return Lz4Frame.encodeBsaPrecharged(
          source, decodedSize, sink, checkpoint, budget, admission, context);
    } catch (ArchiveException failure) {
      throw reprofile(failure);
    }
  }

  /** Creates a lazy decoder whose later content failures retain the BSA profile identity. */
  public static Decoder decoder(
      JdkZlib.ByteSource source, long storedSize, long decodedSize, IoContext context)
      throws ArchiveException {
    try {
      preflight("decode", context);
      return new Decoder(Lz4Frame.decoder(source, storedSize, decodedSize, context));
    } catch (ArchiveException failure) {
      throw reprofile(failure);
    }
  }

  /** Owns one shared-runtime decoder while normalizing its public profile evidence. */
  public static final class Decoder implements AutoCloseable {
    private final Lz4Frame.Decoder delegate;

    private Decoder(Lz4Frame.Decoder delegate) {
      this.delegate = delegate;
    }

    /** Reads one bounded window and rewrites only the opaque codec-profile value on failure. */
    public int read(ByteBuffer destination) throws IOException {
      try {
        return delegate.read(destination);
      } catch (ArchiveException failure) {
        throw reprofile(failure);
      }
    }

    /** Releases the delegated native state exactly once. */
    @Override
    public void close() {
      delegate.close();
    }
  }

  /** Preserves failure ownership and causes while selecting the family codec-profile identity. */
  private static ArchiveException reprofile(ArchiveException failure) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    for (Diagnostic diagnostic : failure.diagnostics()) {
      var values = new TreeMap<>(diagnostic.values());
      values.put("profile", PROFILE);
      diagnostics.add(
          new Diagnostic(
              diagnostic.identifier(),
              diagnostic.severity(),
              diagnostic.operation(),
              diagnostic.phase(),
              diagnostic.location(),
              values,
              diagnostic.explanation()));
    }
    if (failure instanceof ArchiveCancelledException)
      return new ArchiveCancelledException(
          failure.getMessage(),
          failure.primaryFailure(),
          diagnostics,
          failure.artifacts(),
          failure.assessment(),
          failure.secondaryFailures());
    return new ArchiveException(
        failure.getMessage(),
        failure.primaryFailure(),
        diagnostics,
        failure.artifacts(),
        failure.assessment(),
        failure.secondaryFailures());
  }
}
