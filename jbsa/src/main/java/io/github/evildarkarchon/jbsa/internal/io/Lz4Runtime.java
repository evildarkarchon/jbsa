package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.util.*;

/**
 * Lazy, process-lifetime LZ4 provider selection for the release-pinned internal LZ4 profiles.
 *
 * <p>JBSA-CODEC-014 pairs the qualified native LWJGL adapter with lz4-java's pure-Java safe
 * provider. JBSA-CODEC-015 requires preflight to pin the native provider when it loads and the
 * portable provider otherwise, before any side effect, and never to switch afterwards.
 */
public final class Lz4Runtime {
  /** Opaque evidence identity of the native LWJGL/LZ4 1.10.0 profile. */
  public static final String PROFILE = "jbsa-lz4-v1";

  /** Opaque evidence identity of the portable lz4-java profile; never conflated with native. */
  public static final String PORTABLE_PROFILE = "jbsa-lz4-portable-v1";

  private static boolean loaded;
  private static Provider pinned;

  private Lz4Runtime() {}

  /** One LZ4 implementation; every operation in a process runs through the same pinned one. */
  public enum Provider {
    /** The hidden LWJGL 3.4.3 adapter backed by upstream LZ4 1.10.0, qualified on Windows x64. */
    NATIVE(PROFILE),
    /** lz4-java's pure-Java safe provider, available wherever the native adapter is not. */
    PORTABLE(PORTABLE_PROFILE);

    private final String profile;

    Provider(String profile) {
      this.profile = profile;
    }

    /** Returns the opaque codec-profile identity recorded in this provider's diagnostics. */
    public String profile() {
      return profile;
    }
  }

  /**
   * Pins and returns the process's LZ4 provider before the caller's first side effect. The native
   * adapter wins when it loads; any native unavailability selects the portable provider instead,
   * and the choice never changes afterwards, so one operation can never mix providers.
   *
   * @throws ArchiveException {@code CAPABILITY codec.unavailable} only when the native adapter is
   *     unavailable and lz4-java itself cannot be loaded
   */
  public static synchronized Provider preflight(String codec, String direction, IoContext context)
      throws ArchiveException {
    Provider provider = selected();
    if (provider == Provider.PORTABLE) {
      try {
        PortableLz4.admit();
      } catch (LinkageError | RuntimeException cause) {
        throw unavailable(context, codec, direction, "provider-unavailable", cause);
      }
    }
    return provider;
  }

  /**
   * Returns the pinned provider, selecting it on first use without throwing. Resource accounting
   * calls this before an adapter preflight so it charges the working set of the provider that will
   * actually run; the later preflight still reports a missing portable provider.
   */
  public static synchronized Provider selected() {
    if (pinned == null)
      pinned = nativeUnavailableReason() == null ? Provider.NATIVE : Provider.PORTABLE;
    return pinned;
  }

  /**
   * Checks platform, host grants and native availability without changing host-process policy. This
   * is the native adapter's own admission, kept separate from {@link #preflight} so native loading
   * can be qualified directly; production callers use {@link #preflight}.
   *
   * @throws ArchiveException {@code CAPABILITY codec.unavailable} with a {@code capabilityCause}
   *     naming the first native check that failed
   */
  public static synchronized void nativePreflight(String codec, String direction, IoContext context)
      throws ArchiveException {
    Failure failure = nativeUnavailableReason();
    if (failure != null)
      throw unavailable(context, codec, direction, failure.reason(), failure.cause());
  }

  /** One native admission failure: its stable diagnostic cause and the retained provider detail. */
  private record Failure(String reason, Throwable cause) {}

  /** Runs the native checks once they are needed, returning null after the adapter has loaded. */
  private static Failure nativeUnavailableReason() {
    // LWJGL writes its extraction path during loading; loaded libraries cannot change afterward.
    if (loaded) return null;
    String reason = "provider-unavailable";
    try {
      if (!System.getProperty("os.name", "").startsWith("Windows")
          || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch", ""))) {
        reason = "platform";
        throw new IllegalStateException();
      }
      // Reject provider path/name overrides before either provider class can initialize.
      for (String key :
          List.of("org.lwjgl.librarypath", "org.lwjgl.libname", "org.lwjgl.lz4.libname")) {
        if (System.getProperty(key) != null) {
          reason = "native-configuration";
          throw new IllegalStateException();
        }
      }
      ClassLoader loader = Lz4Runtime.class.getClassLoader();
      Class<?> core = Class.forName("org.lwjgl.system.Library", false, loader);
      Class<?> binding = Class.forName("org.lwjgl.util.lz4.LZ4", false, loader);
      if (!core.getModule().isNativeAccessEnabled()
          || !binding.getModule().isNativeAccessEnabled()) {
        reason = "native-access";
        throw new IllegalStateException();
      }
      if (org.lwjgl.util.lz4.LZ4.LZ4_versionNumber() != 11000) {
        reason = "provider-version";
        throw new IllegalStateException();
      }
      loaded = true;
      return null;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException cause) {
      return new Failure(reason, cause);
    }
  }

  /** Builds the normalized capability failure with its stable cause as a diagnostic value. */
  private static ArchiveException unavailable(
      IoContext context, String codec, String direction, String reason, Throwable cause) {
    ArchiveException base =
        failure(
            context, FailureKind.CAPABILITY, "codec.unavailable", codec, direction, -1, -1, cause);
    Diagnostic diagnostic = base.diagnostics().getFirst();
    var values = new TreeMap<>(diagnostic.values());
    values.put("capabilityCause", reason);
    return new ArchiveException(
        "codec.unavailable",
        base.primaryFailure(),
        List.of(
            new Diagnostic(
                diagnostic.identifier(),
                diagnostic.severity(),
                context.operation(),
                context.phase(),
                diagnostic.location(),
                values,
                Optional.empty())),
        List.of(),
        Optional.empty(),
        List.of());
  }

  /** Retains provider details only as causes while recording stable codec and location evidence. */
  public static ArchiveException failure(
      IoContext context,
      FailureKind kind,
      String identifier,
      String codec,
      String direction,
      long expected,
      long actual,
      Throwable cause) {
    return failure(
        Provider.NATIVE, context, kind, identifier, codec, direction, expected, actual, cause);
  }

  /**
   * Builds one normalized codec failure whose opaque profile names the provider that ran, so
   * portable and native evidence are never conflated (JBSA-CODEC-012, JBSA-CODEC-013).
   */
  public static ArchiveException failure(
      Provider provider,
      IoContext context,
      FailureKind kind,
      String identifier,
      String codec,
      String direction,
      long expected,
      long actual,
      Throwable cause) {
    ArchiveException base = context.failure(kind, identifier, cause);
    var values = new TreeMap<String, String>();
    values.put("codec", codec);
    values.put("direction", direction);
    values.put("profile", provider.profile());
    if (expected >= 0) values.put("expected", Long.toString(expected));
    if (actual >= 0) values.put("actual", Long.toString(actual));
    return new ArchiveException(
        identifier,
        base.primaryFailure(),
        List.of(
            new Diagnostic(
                identifier,
                DiagnosticSeverity.ERROR,
                context.operation(),
                context.phase(),
                base.diagnostics().getFirst().location(),
                values,
                Optional.empty())),
        List.of(),
        Optional.empty(),
        List.of());
  }
}
