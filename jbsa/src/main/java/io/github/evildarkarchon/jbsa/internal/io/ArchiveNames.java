package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.CompatibilityProfile;
import io.github.evildarkarchon.jbsa.FailureKind;
import java.nio.charset.Charset;
import java.util.Optional;

/** Archive Name Encoding shared by every reader and pack adapter. */
public final class ArchiveNames {
  private ArchiveNames() {}

  /**
   * Snapshots the selected operation's archive-name encoding; only an explicit profile reads ACP.
   * Native access or an unavailable charset yields checked capability failure, never a fallback.
   */
  public static Charset encoding(Optional<CompatibilityProfile> profile, IoContext context)
      throws ArchiveException {
    if (profile.isEmpty()) return Charset.forName("windows-1252");
    if (!System.getProperty("os.name").startsWith("Windows")
        || !ArchiveNames.class.getModule().isNativeAccessEnabled())
      throw context.failure(FailureKind.CAPABILITY, "name.active-ansi-unavailable", null);
    try (var arena = java.lang.foreign.Arena.ofConfined()) {
      var lookup = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", arena);
      var function =
          java.lang.foreign.Linker.nativeLinker()
              .downcallHandle(
                  lookup.find("GetACP").orElseThrow(),
                  java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT));
      int codePage = (int) function.invokeExact();
      String charset =
          switch (codePage) {
            case 65001 -> "UTF-8";
            case 932 -> "windows-31j";
            case 936 -> "GBK";
            case 949 -> "x-windows-949";
            case 950 -> "x-windows-950";
            case 874 -> "x-windows-874";
            default -> "windows-" + Integer.toUnsignedString(codePage);
          };
      return Charset.forName(charset);
    } catch (VirtualMachineError | ThreadDeath fatal) {
      throw fatal;
    } catch (Throwable failure) {
      throw context.failure(FailureKind.CAPABILITY, "name.active-ansi-unavailable", failure);
    }
  }
}
