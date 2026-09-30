package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.Lz4Runtime.Provider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Versioned-BSA LZ4-frame profile layered on the shared, provider-pinned LZ4 runtime. */
public final class BsaLz4Frame {
  /** Opaque evidence identity of this profile under the native LWJGL provider. */
  public static final String PROFILE = "jbsa-bsa-069-lz4-v1";

  /** Opaque evidence identity of this profile under the portable lz4-java provider. */
  public static final String PORTABLE_PROFILE = "jbsa-bsa-069-lz4-portable-v1";

  private BsaLz4Frame() {}

  /**
   * Pins the shared runtime's provider while binding capability evidence to the BSA profile.
   *
   * @return the provider every later call in this process uses
   */
  public static Provider preflight(String direction, IoContext context) throws ArchiveException {
    try {
      return Lz4Runtime.preflight("lz4-frame", direction, context);
    } catch (ArchiveException failure) {
      // No provider ran, so the failure keeps the family's primary (native) profile identity.
      throw reprofile(failure, Provider.NATIVE);
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
    Provider provider = preflight("encode", context);
    try {
      return Lz4Frame.encode(
          provider, source, decodedSize, sink, checkpoint, budget, null, context, true);
    } catch (ArchiveException failure) {
      throw reprofile(failure, provider);
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
    java.util.Objects.requireNonNull(admission, "admission");
    Provider provider = preflight("encode", context);
    try {
      return Lz4Frame.encode(
          provider, source, decodedSize, sink, checkpoint, budget, admission, context, true);
    } catch (ArchiveException failure) {
      throw reprofile(failure, provider);
    }
  }

  /** Creates a lazy decoder whose later content failures retain the BSA profile identity. */
  public static Decoder decoder(
      JdkZlib.ByteSource source, long storedSize, long decodedSize, IoContext context)
      throws ArchiveException {
    Provider provider = preflight("decode", context);
    try {
      return new Decoder(
          Lz4Frame.decoder(provider, source, storedSize, decodedSize, context), provider);
    } catch (ArchiveException failure) {
      throw reprofile(failure, provider);
    }
  }

  /** Owns one shared-runtime decoder while normalizing its public profile evidence. */
  public static final class Decoder implements AutoCloseable {
    private final Lz4Frame.Decoder delegate;
    private final Provider provider;

    private Decoder(Lz4Frame.Decoder delegate, Provider provider) {
      this.delegate = delegate;
      this.provider = provider;
    }

    /** Reads one bounded window and rewrites only the opaque codec-profile value on failure. */
    public int read(ByteBuffer destination) throws IOException {
      try {
        return delegate.read(destination);
      } catch (ArchiveException failure) {
        throw reprofile(failure, provider);
      }
    }

    /** Releases the delegated codec state exactly once. */
    @Override
    public void close() {
      delegate.close();
    }
  }

  /**
   * Preserves failure ownership and causes while selecting the family codec-profile identity of the
   * provider that ran, so portable and native evidence stay distinct.
   */
  private static ArchiveException reprofile(ArchiveException failure, Provider provider) {
    String profile = provider == Provider.PORTABLE ? PORTABLE_PROFILE : PROFILE;
    List<Diagnostic> diagnostics = new ArrayList<>();
    for (Diagnostic diagnostic : failure.diagnostics()) {
      var values = new TreeMap<>(diagnostic.values());
      values.put("profile", profile);
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
