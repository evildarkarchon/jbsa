package io.github.evildarkarchon.jbsa.internal.io;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** No-follow Windows file identities and handles that prevent directory replacement. */
public final class WindowsPathIdentity {
  private static final int SHARE_READ_WRITE = 3;
  private static final int SHARE_ALL = 7;
  private static final int OPEN_EXISTING = 3;
  private static final int OPEN_DIRECTORY_NOFOLLOW = 0x02200000;
  private static final int READ_ATTRIBUTES = 0x80;
  private static final int LIST_DIRECTORY_OR_READ_DATA = 1;
  private static final int DIRECTORY_ATTRIBUTE = 0x10;
  private static final int REPARSE_ATTRIBUTE = 0x400;
  private static final int DEVICE_ATTRIBUTE = 0x40;

  private WindowsPathIdentity() {}

  /**
   * Reads the named entry itself, returning null only when it or a parent does not exist.
   *
   * @throws IOException if Windows cannot open or inspect the entry
   * @throws UnsupportedOperationException if native access or stable identity is unavailable
   */
  public static Snapshot inspect(Path path) throws IOException {
    Pin pin = open(path, SHARE_ALL);
    if (pin == null) {
      return null;
    }
    try (pin) {
      return pin.snapshot();
    }
  }

  /**
   * Opens the entry without following its final reparse point and denies deletion and rename until
   * closed. Callers must inspect the snapshot before accepting the entry as a directory.
   *
   * @throws IOException if the entry is absent, inaccessible, or already open for deletion
   * @throws UnsupportedOperationException if native access or stable identity is unavailable
   */
  public static Pin pin(Path path) throws IOException {
    Pin pin = open(path, SHARE_READ_WRITE);
    if (pin == null) {
      throw new IOException(
          "The entry to pin does not exist", new NoSuchFileException(path.toString()));
    }
    return pin;
  }

  /** A detached entry identity and its no-follow type; no operating-system handle is retained. */
  public record Snapshot(
      Object identity, boolean directory, boolean regular, boolean indirection) {}

  /** Full Windows identity: the volume serial plus all 128 file-ID bits, including on ReFS. */
  private record Identity(long volume, long low, long high) {}

  /** An owned deny-delete handle. Closing is idempotent and synchronized with other close calls. */
  public static final class Pin implements AutoCloseable {
    private final NativeApi api;
    private final MemorySegment handle;
    private final Snapshot snapshot;
    private boolean closed;

    /** Takes sole ownership of an open handle after its attributes have been captured. */
    private Pin(NativeApi api, MemorySegment handle, Snapshot snapshot) {
      this.api = api;
      this.handle = handle;
      this.snapshot = snapshot;
    }

    /** Returns the detached no-follow attributes captured from this handle when it was opened. */
    public Snapshot snapshot() {
      return snapshot;
    }

    /** Releases the deny-delete handle; a failed close is reported and may be retried. */
    @Override
    public synchronized void close() throws IOException {
      if (!closed) {
        api.close(handle);
        closed = true;
      }
    }
  }

  /** Acquires a handle first so identity and attributes describe the same entry despite renames. */
  private static Pin open(Path path, int sharing) throws IOException {
    if (!System.getProperty("os.name").startsWith("Windows")
        || path.getFileSystem() != FileSystems.getDefault()
        || !WindowsPathIdentity.class.getModule().isNativeAccessEnabled()) {
      throw new UnsupportedOperationException("Windows native path identity is unavailable");
    }
    NativeApi api = NativeHolder.api();
    try (Arena arena = Arena.ofConfined()) {
      String absolute = path.toAbsolutePath().normalize().toString();
      if (absolute.length() > 32759) {
        throw new IOException("Windows entry exceeds the extended path limit");
      }
      // Extended paths preserve long names; UNC paths have a distinct extended-path prefix.
      String nativePath =
          absolute.startsWith("\\\\")
              ? "\\\\?\\UNC\\" + absolute.substring(2)
              : "\\\\?\\" + absolute;
      MemorySegment name = arena.allocateFrom(nativePath, StandardCharsets.UTF_16LE);
      MemorySegment state = arena.allocate(api.stateLayout);
      MemorySegment handle;
      // Metadata-only handles do not enforce deny-delete sharing. Listing access makes a pin
      // participate in sharing checks so another process cannot rename its directory.
      int access = READ_ATTRIBUTES | (sharing == SHARE_ALL ? 0 : LIST_DIRECTORY_OR_READ_DATA);
      try {
        handle =
            (MemorySegment)
                api.create.invokeExact(
                    state,
                    name,
                    access,
                    sharing,
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    OPEN_DIRECTORY_NOFOLLOW,
                    MemorySegment.NULL);
      } catch (Throwable failure) {
        throw invocationFailure(failure);
      }
      if (handle.address() == -1L) {
        int error = api.error(state);
        if (error == 2 || error == 3) {
          return null;
        }
        throw nativeFailure("Cannot open the Windows entry", error);
      }
      try {
        return new Pin(api, handle, api.snapshot(handle, arena, state));
      } catch (IOException | RuntimeException | Error failure) {
        try {
          api.close(handle);
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }
  }

  /**
   * Retains load failures without poisoning class initialization for subsequent capability checks.
   */
  private static final class NativeHolder {
    private static final NativeApi API;
    private static final Throwable FAILURE;

    static {
      NativeApi api = null;
      Throwable failure = null;
      try {
        api = new NativeApi();
      } catch (RuntimeException | LinkageError unavailable) {
        failure = unavailable;
      }
      API = api;
      FAILURE = failure;
    }

    /** Returns the system binding or a recoverable capability failure. */
    private static NativeApi api() {
      if (API == null) {
        throw new UnsupportedOperationException(
            "Windows native path identity is unavailable", FAILURE);
      }
      return API;
    }
  }

  /** System-library bindings use Windows DWORD/BOOL integers and pointer-sized HANDLE values. */
  private static final class NativeApi {
    private final MemoryLayout stateLayout = Linker.Option.captureStateLayout();
    private final long errorOffset =
        stateLayout.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
    private final MethodHandle create;
    private final MethodHandle information;
    private final MethodHandle identity;
    private final MethodHandle close;

    /** Loads only the platform Kernel32 library; its symbols live for the process lifetime. */
    private NativeApi() {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel = SymbolLookup.libraryLookup("Kernel32.dll", Arena.global());
      // Capture last-error in the downcall: a later Java/native transition can overwrite it.
      Linker.Option errors = Linker.Option.captureCallState("GetLastError");
      create =
          linker.downcallHandle(
              kernel.find("CreateFileW").orElseThrow(),
              FunctionDescriptor.of(
                  ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
              errors);
      information =
          linker.downcallHandle(
              kernel.find("GetFileInformationByHandle").orElseThrow(),
              FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
              errors);
      identity =
          linker.downcallHandle(
              kernel.find("GetFileInformationByHandleEx").orElseThrow(),
              FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
              errors);
      close =
          linker.downcallHandle(
              kernel.find("CloseHandle").orElseThrow(),
              FunctionDescriptor.of(JAVA_INT, ADDRESS),
              errors);
    }

    /** Reads both attribute flags and the complete file identity from a single open handle. */
    private Snapshot snapshot(MemorySegment handle, Arena arena, MemorySegment state)
        throws IOException {
      // BY_HANDLE_FILE_INFORMATION is thirteen DWORDs; FILE_ID_INFO is a 64-bit serial plus 128 ID
      // bits.
      MemorySegment attributes = arena.allocate(52, 4);
      MemorySegment fileId = arena.allocate(24, 8);
      int read;
      try {
        read = (int) information.invokeExact(state, handle, attributes);
      } catch (Throwable failure) {
        throw invocationFailure(failure);
      }
      if (read == 0) {
        throw nativeFailure("Cannot inspect the Windows entry", error(state));
      }
      try {
        read = (int) identity.invokeExact(state, handle, 18, fileId, 24);
      } catch (Throwable failure) {
        throw invocationFailure(failure);
      }
      if (read == 0) {
        throw new UnsupportedOperationException(
            "Windows stable file identity is unavailable",
            nativeFailure("Cannot obtain the Windows file identity", error(state)));
      }
      int flags = attributes.get(JAVA_INT, 0);
      boolean directory = (flags & DIRECTORY_ATTRIBUTE) != 0;
      boolean indirection = (flags & REPARSE_ATTRIBUTE) != 0;
      return new Snapshot(
          new Identity(
              fileId.get(JAVA_LONG, 0), fileId.get(JAVA_LONG, 8), fileId.get(JAVA_LONG, 16)),
          directory,
          !directory && !indirection && (flags & DEVICE_ATTRIBUTE) == 0,
          indirection);
    }

    /** Closes exactly one owned handle and preserves native failure details only in the cause. */
    private void close(MemorySegment handle) throws IOException {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment state = arena.allocate(stateLayout);
        int result;
        try {
          result = (int) close.invokeExact(state, handle);
        } catch (Throwable failure) {
          throw invocationFailure(failure);
        }
        if (result == 0) {
          throw nativeFailure("Cannot release the Windows entry handle", error(state));
        }
      }
    }

    private int error(MemorySegment state) {
      return state.get(JAVA_INT, errorOffset);
    }
  }

  /** Keeps unstable operating-system details out of the stable outer diagnostic text. */
  private static IOException nativeFailure(String message, int error) {
    return new IOException(
        message, new IOException("Windows error " + Integer.toUnsignedString(error)));
  }

  /** Propagates VM/programming failures and wraps checked downcall failures as I/O failures. */
  private static IOException invocationFailure(Throwable failure) {
    if (failure instanceof Error error) {
      throw error;
    }
    if (failure instanceof RuntimeException runtime) {
      throw runtime;
    }
    return new IOException("Windows path identity invocation failed", failure);
  }
}
