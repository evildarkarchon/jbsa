package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.util.*;

/** Lazy, process-lifetime capability admission for the release-pinned internal LZ4 profiles. */
public final class Lz4Runtime {
  public static final String PROFILE = "jbsa-lz4-v1";
  private static boolean loaded;

  private Lz4Runtime() {}

  /** Checks platform, host grants and native availability without changing host-process policy. */
  public static synchronized void preflight(String codec, String direction, IoContext context)
      throws ArchiveException {
    // LWJGL writes its extraction path during loading; loaded libraries cannot change afterward.
    if (loaded) return;
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
    } catch (ReflectiveOperationException | LinkageError | RuntimeException cause) {
      ArchiveException base =
          failure(
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
      throw new ArchiveException(
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
    ArchiveException base = context.failure(kind, identifier, cause);
    var values = new TreeMap<String, String>();
    values.put("codec", codec);
    values.put("direction", direction);
    values.put("profile", PROFILE);
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
