package io.github.evildarkarchon.jbsa.internal.codec;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
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

  /** Opaque evidence identity of the versioned-BSA 0x69 frame profile under the native provider. */
  static final String BSA_FRAME_PROFILE = "jbsa-bsa-069-lz4-v1";

  /** Opaque evidence identity of the versioned-BSA 0x69 frame profile under lz4-java. */
  static final String PORTABLE_BSA_FRAME_PROFILE = "jbsa-bsa-069-lz4-portable-v1";

  private static boolean loaded;
  private static Provider pinned;

  private Lz4Runtime() {}

  /** One LZ4 implementation; every operation in a process runs through the same pinned one. */
  public enum Provider {
    /** The hidden LWJGL 3.4.3 adapter backed by upstream LZ4 1.10.0, qualified on Windows x64. */
    NATIVE(PROFILE, BSA_FRAME_PROFILE),
    /** lz4-java's pure-Java safe provider, available wherever the native adapter is not. */
    PORTABLE(PORTABLE_PROFILE, PORTABLE_BSA_FRAME_PROFILE);

    private final String profile;
    private final String bsaFrameProfile;

    Provider(String profile, String bsaFrameProfile) {
      this.profile = profile;
      this.bsaFrameProfile = bsaFrameProfile;
    }

    /** Returns the opaque codec-profile identity recorded in this provider's diagnostics. */
    public String profile() {
      return profile;
    }

    /**
     * Returns the Versioned BSA family-layered identity of the 0x69 frame profile under this
     * provider, which the {@link PayloadCodec#BSA_LZ4_FRAME} admitted codec's failures carry.
     */
    String bsaFrameProfile() {
      return bsaFrameProfile;
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
    try {
      // Only the portable adapter has anything left to load; native pinning implies it loaded.
      Lz4Adapter.of(provider).admit();
    } catch (LinkageError | RuntimeException cause) {
      // The portable set is pinned even though lz4-java cannot load, so its identity is reported.
      throw unavailable(provider, context, codec, direction, "provider-unavailable", cause);
    }
    return provider;
  }

  /**
   * Returns the pinned provider, selecting it on first use without throwing. Production reaches it
   * only through admission: {@link #preflight} pins with it, and {@link AdmittedCodec} names a
   * JDK-only codec's identity with it. Callers cost a codec through its {@link AdmittedCodec}
   * instead, so they always charge the provider admission resolved.
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
    // This admission concerns only the native adapter, so its evidence names the native profile.
    if (failure != null)
      throw unavailable(
          Provider.NATIVE, context, codec, direction, failure.reason(), failure.cause());
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
      // Only now, with LWJGL known to resolve, may the native adapter's class be touched.
      if (NativeLz4.versionNumber() != 11000) {
        reason = "provider-version";
        throw new IllegalStateException();
      }
      loaded = true;
      return null;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException cause) {
      return new Failure(reason, cause);
    }
  }

  /**
   * Builds the normalized capability failure with its stable cause as a diagnostic value.
   *
   * @param provider the provider whose admission failed; its profile is the evidence identity
   */
  private static ArchiveException unavailable(
      Provider provider,
      IoContext context,
      String codec,
      String direction,
      String reason,
      Throwable cause) {
    ArchiveException base =
        failure(
            provider,
            context,
            FailureKind.CAPABILITY,
            "codec.unavailable",
            codec,
            direction,
            -1,
            -1,
            cause);
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

  /**
   * Builds one normalized codec failure whose opaque profile names the provider that ran, so
   * portable and native evidence are never conflated (JBSA-CODEC-012, JBSA-CODEC-013). Provider
   * details are retained only as causes; the values record stable codec and location evidence.
   * There is deliberately no overload that defaults the provider: every caller names it.
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
