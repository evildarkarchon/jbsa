package io.github.evildarkarchon.jbsa.cli;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the two Windows console observations needed by the thin CLI. */
final class WindowsConsole {
  private static final int STD_ERROR_HANDLE = -12;
  private static final int CTRL_C_EVENT = 0;
  private static volatile CancellationHandler cancellationHandler;

  private WindowsConsole() {}

  /** Reports whether the actual stderr handle is a console screen buffer. */
  static boolean stderrAttached() {
    if (!available()) return false;
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", arena);
      Linker linker = Linker.nativeLinker();
      MethodHandle standardHandle =
          linker.downcallHandle(
              kernel.find("GetStdHandle").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
      MethodHandle consoleMode =
          linker.downcallHandle(
              kernel.find("GetConsoleMode").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      MemorySegment stderr = (MemorySegment) standardHandle.invokeExact(STD_ERROR_HANDLE);
      if (stderr.address() == 0 || stderr.address() == -1L) return false;
      MemorySegment mode = arena.allocate(ValueLayout.JAVA_INT);
      return (int) consoleMode.invokeExact(stderr, mode) != 0;
    } catch (VirtualMachineError | ThreadDeath fatal) {
      throw fatal;
    } catch (Throwable unavailable) {
      // Progress is presentation-only; an unproven handle must remain silent.
      return false;
    }
  }

  /** Registers an idempotent Ctrl+C request through process exit. */
  static void registerCancellation(AtomicBoolean cancellationRequested) {
    if (!System.getProperty("os.name", "").startsWith("Windows")) return;
    if (!available()) throw new ConsoleCapabilityException();
    try {
      SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
      MethodHandle setHandler =
          Linker.nativeLinker()
              .downcallHandle(
                  kernel.find("SetConsoleCtrlHandler").orElseThrow(),
                  FunctionDescriptor.of(
                      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
      MethodHandle callback =
          MethodHandles.lookup()
              .findStatic(
                  WindowsConsole.class,
                  "handleControl",
                  MethodType.methodType(int.class, AtomicBoolean.class, int.class))
              .bindTo(cancellationRequested);
      // The process has one CLI invocation. A global stub cannot be freed under an in-flight
      // console callback while the settled result is being reported.
      MemorySegment stub =
          Linker.nativeLinker()
              .upcallStub(
                  callback,
                  FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                  Arena.global());
      if ((int) setHandler.invokeExact(stub, 1) == 0) throw new ConsoleCapabilityException();
      // A late Ctrl+C must not replace the settled status while records are being written.
      cancellationHandler = new CancellationHandler(cancellationRequested, callback, stub);
    } catch (VirtualMachineError | ThreadDeath fatal) {
      throw fatal;
    } catch (Throwable unavailable) {
      throw new ConsoleCapabilityException();
    }
  }

  /** Handles only Ctrl+C; other Windows console events keep their normal handlers. */
  private static int handleControl(AtomicBoolean cancellationRequested, int event) {
    if (event != CTRL_C_EVENT) return 0;
    try {
      cancellationRequested.set(true);
      return 1;
    } catch (Throwable failure) {
      // A foreign callback cannot unwind through Win32; let the next handler see the event.
      return 0;
    }
  }

  /** Requires native access only where Windows console behavior is qualified. */
  private static boolean available() {
    return System.getProperty("os.name", "").startsWith("Windows")
        && WindowsConsole.class.getModule().isNativeAccessEnabled();
  }

  /** Retains the upcall target and native stub until the single CLI process exits. */
  private record CancellationHandler(
      AtomicBoolean cancellationRequested, MethodHandle callback, MemorySegment stub) {}

  /** A missing native console control capability is an operational failure before mutation. */
  static final class ConsoleCapabilityException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
