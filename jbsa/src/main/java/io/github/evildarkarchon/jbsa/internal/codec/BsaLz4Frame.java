package io.github.evildarkarchon.jbsa.internal.codec;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.Lz4Runtime.Provider;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Versioned-BSA LZ4-frame profile layered on the shared, provider-pinned LZ4 runtime. Every call
 * runs through the provider its {@link AdmittedCodec} resolved, so it never preflights again, and
 * binds failures to that codec's family-layered profile identity.
 */
public final class BsaLz4Frame {
  /** Opaque evidence identity of this profile under the native LWJGL provider. */
  public static final String PROFILE = "jbsa-bsa-069-lz4-v1";

  /** Opaque evidence identity of this profile under the portable lz4-java provider. */
  public static final String PORTABLE_PROFILE = "jbsa-bsa-069-lz4-portable-v1";

  private BsaLz4Frame() {}

  /**
   * Pins the shared runtime's provider while binding capability evidence to the BSA profile. Only
   * {@link AdmittedCodec#admit} calls this.
   *
   * @return the provider every later call in this process uses
   */
  static Provider preflight(String direction, IoContext context) throws ArchiveException {
    try {
      return Lz4Runtime.preflight("lz4-frame", direction, context);
    } catch (ArchiveException failure) {
      // No provider ran, so the failure keeps the family's primary (native) profile identity.
      throw reprofile(failure, PROFILE);
    }
  }

  /**
   * Encodes one exact level-12 independent-block frame, reserving its own codec credit, and binds
   * failures to this profile.
   *
   * @param admitted the admitted BSA frame codec, whose provider runs the call
   */
  public static long encode(
      AdmittedCodec admitted,
      ByteSource source,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context)
      throws IOException {
    Provider provider = provider(admitted);
    try {
      return Lz4Frame.encode(
          provider, source, decodedSize, sink, checkpoint, budget, null, context, true);
    } catch (ArchiveException failure) {
      throw reprofile(failure, admitted.profile());
    }
  }

  /**
   * Borrows a worker's full codec reservation while retaining the BSA profile diagnostics.
   *
   * @param admitted the admitted BSA frame codec, whose provider runs the call
   * @param admission a lease already covering {@code admitted}'s encode cost
   */
  public static long encodePrecharged(
      AdmittedCodec admitted,
      ByteSource source,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget budget,
      ResourceBudget.Lease admission,
      IoContext context)
      throws IOException {
    java.util.Objects.requireNonNull(admission, "admission");
    Provider provider = provider(admitted);
    try {
      return Lz4Frame.encode(
          provider, source, decodedSize, sink, checkpoint, budget, admission, context, true);
    } catch (ArchiveException failure) {
      throw reprofile(failure, admitted.profile());
    }
  }

  /**
   * Creates a lazy decoder whose later content failures retain the BSA profile identity.
   *
   * @param admitted the admitted BSA frame codec, whose provider runs the decoder
   */
  public static Decoder decoder(
      AdmittedCodec admitted,
      ByteSource source,
      long storedSize,
      long decodedSize,
      IoContext context)
      throws ArchiveException {
    Provider provider = provider(admitted);
    try {
      return new Decoder(
          Lz4Frame.decoder(provider, source, storedSize, decodedSize, context), admitted.profile());
    } catch (ArchiveException failure) {
      throw reprofile(failure, admitted.profile());
    }
  }

  /** Returns the provider the admitted BSA frame codec resolved, rejecting any other codec. */
  private static Provider provider(AdmittedCodec admitted) {
    if (admitted.codec() != PayloadCodec.BSA_LZ4_FRAME)
      throw new IllegalArgumentException("The BSA frame needs the admitted BSA frame codec");
    return admitted.provider();
  }

  /** Owns one shared-runtime decoder while normalizing its public profile evidence. */
  public static final class Decoder implements AutoCloseable {
    private final Lz4Frame.Decoder delegate;
    private final String profile;

    private Decoder(Lz4Frame.Decoder delegate, String profile) {
      this.delegate = delegate;
      this.profile = profile;
    }

    /** Reads one bounded window and rewrites only the opaque codec-profile value on failure. */
    public int read(ByteBuffer destination) throws IOException {
      try {
        return delegate.read(destination);
      } catch (ArchiveException failure) {
        throw reprofile(failure, profile);
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
  private static ArchiveException reprofile(ArchiveException failure, String profile) {
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
