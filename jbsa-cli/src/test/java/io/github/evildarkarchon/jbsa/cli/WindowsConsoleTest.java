package io.github.evildarkarchon.jbsa.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Verifies the operation-scoped Windows console cancellation callback contract. */
final class WindowsConsoleTest {
  /** Only Ctrl+C requests cancellation, and repeated events cannot strengthen the request. */
  @Test
  void onlyCtrlCRequestsIdempotentCooperativeCancellation() throws Exception {
    var callback =
        WindowsConsole.class.getDeclaredMethod("handleControl", AtomicBoolean.class, int.class);
    callback.setAccessible(true);
    AtomicBoolean requested = new AtomicBoolean();
    assertEquals(0, callback.invoke(null, requested, 1));
    assertFalse(requested.get());
    assertEquals(1, callback.invoke(null, requested, 0));
    assertTrue(requested.get());
    assertEquals(1, callback.invoke(null, requested, 0));
    assertTrue(requested.get());
  }
}
