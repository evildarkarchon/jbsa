package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.Operation;
import io.github.evildarkarchon.jbsa.ResourceLimits;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Qualifies internal reservations independently of host memory and archive-format implementations.
 */
final class ResourceBudgetTest {
  private static final IoContext CONTEXT = IoContext.of(Path.of("archive.bin"), Operation.OPEN);

  /**
   * An exhausted dimension rejects the whole reservation; closing a lease releases every credit.
   */
  @Test
  void reservesAtomicallyAndReleasesIdempotently() throws Exception {
    try (ResourceBudget budget = new ResourceBudget(limits(100, 100, 100), CONTEXT, 10, 20, 1)) {
      ResourceBudget.Lease first = budget.reserve(4, 5, 1, 6);
      ArchiveException exhausted =
          assertThrows(ArchiveException.class, () -> budget.reserve(6, 15, 1, 94));
      assertEquals(FailureKind.POLICY, exhausted.kind());
      assertEquals(
          "io.resource-capacity", exhausted.primaryFailure().diagnosticIdentifier().orElseThrow());
      assertTrue(exhausted.diagnostics().getFirst().values().isEmpty());
      try (ResourceBudget.Lease remainder = budget.reserve(6, 15, 0, 94)) {
        assertNotNull(remainder);
      }
      first.close();
      first.close();
      try (ResourceBudget.Lease all = budget.reserve(10, 20, 1, 100)) {
        assertNotNull(all);
        assertThrows(ArchiveException.class, () -> budget.reserve(1, 0, 0, 0));
      }
    }
  }

  /**
   * Encoded metadata accumulates semantically while admission reserves parsed storage in advance.
   */
  @Test
  void countsMetadataAndRetainsItsHeapReservation() throws Exception {
    try (ResourceBudget budget = new ResourceBudget(limits(100, 10, 100), CONTEXT, 80, 0, 1)) {
      budget.metadata(4);
      budget.metadata(6);
      ArchiveException semantic = assertThrows(ArchiveException.class, () -> budget.metadata(1));
      assertEquals("maxMetadataBytes", semantic.diagnostics().getFirst().values().get("field"));
      assertEquals("10", semantic.diagnostics().getFirst().values().get("ceiling"));
      assertEquals("11", semantic.diagnostics().getFirst().values().get("observed"));
      assertThrows(ArchiveException.class, () -> budget.reserve(1, 0, 0, 0));
    }
    try (ResourceBudget budget = new ResourceBudget(limits(100, 10, 100), CONTEXT, 8, 0, 1)) {
      assertThrows(ArchiveException.class, () -> budget.metadata(2));
      budget.metadata(1);
    }
  }

  /** Entry counts are checked before reserving the full eager index, and equality is admissible. */
  @Test
  void boundsTheEntryIndexBeforeAllocation() throws Exception {
    try (ResourceBudget budget = new ResourceBudget(limits(2, 100, 100), CONTEXT, 1024, 0, 1)) {
      budget.checkEntries(2);
      ArchiveException semantic = assertThrows(ArchiveException.class, () -> budget.entryIndex(3));
      assertEquals("maxEntries", semantic.diagnostics().getFirst().values().get("field"));
      assertEquals("3", semantic.diagnostics().getFirst().values().get("observed"));
      budget.entryIndex(2);
      assertThrows(ArchiveException.class, () -> budget.reserve(1, 0, 0, 0));
    }
  }

  /** Scratch is a peak retained extent and reports exact decimal evidence beyond signed long. */
  @Test
  void reportsScratchOverflowAndReusesReleasedPeakCapacity() throws Exception {
    try (ResourceBudget budget =
        new ResourceBudget(
            limits(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
            CONTEXT,
            Long.MAX_VALUE,
            0,
            1)) {
      try (ResourceBudget.Lease maximum = budget.reserve(0, 0, 0, Long.MAX_VALUE)) {
        assertNotNull(maximum);
        ArchiveException overflow =
            assertThrows(ArchiveException.class, () -> budget.reserve(1, 0, 0, Long.MAX_VALUE));
        assertEquals("maxScratchBytes", overflow.diagnostics().getFirst().values().get("field"));
        assertEquals(
            "18446744073709551614", overflow.diagnostics().getFirst().values().get("observed"));
      }
      try (ResourceBudget.Lease again = budget.reserve(Long.MAX_VALUE, 0, 0, Long.MAX_VALUE)) {
        assertNotNull(again);
      }
      ArchiveException index =
          assertThrows(ArchiveException.class, () -> budget.entryIndex(Long.MAX_VALUE));
      assertEquals(
          "io.resource-capacity", index.primaryFailure().diagnosticIdentifier().orElseThrow());
      assertTrue(index.diagnostics().getFirst().values().isEmpty());
      ArchiveException metadata =
          assertThrows(ArchiveException.class, () -> budget.metadata(Long.MAX_VALUE));
      assertEquals(
          "io.resource-capacity", metadata.primaryFailure().diagnosticIdentifier().orElseThrow());
    }
  }

  /** Closing the budget invalidates new allocations and tolerates outstanding lease cleanup. */
  @Test
  void closesOutstandingReservationsAndRejectsNegativeRequests() throws Exception {
    ResourceBudget budget = new ResourceBudget(limits(100, 100, 100), CONTEXT, 10, 10, 1);
    ResourceBudget.Lease lease = budget.reserve(1, 1, 1, 1);
    assertThrows(IllegalArgumentException.class, () -> budget.reserve(-1, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> budget.reserve(0, -1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> budget.reserve(0, 0, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> budget.reserve(0, 0, 0, -1));
    assertThrows(IllegalArgumentException.class, () -> budget.metadata(-1));
    assertThrows(IllegalArgumentException.class, () -> budget.entryIndex(-1));
    budget.close();
    lease.close();
    lease.close();
    budget.close();
    assertThrows(IllegalStateException.class, () -> budget.reserve(0, 0, 0, 0));
    assertThrows(IllegalStateException.class, () -> budget.metadata(0));
    assertThrows(IllegalStateException.class, () -> budget.entryIndex(0));
  }

  /** Competing children cannot admit the same handle credit while the winner retains its lease. */
  @Test
  void admitsOnlyOneConcurrentOwnerOfTheLastHandle() throws Exception {
    try (ResourceBudget budget = new ResourceBudget(limits(100, 100, 100), CONTEXT, 100, 0, 1);
        var executor = Executors.newFixedThreadPool(12)) {
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch attempted = new CountDownLatch(12);
      CountDownLatch release = new CountDownLatch(1);
      AtomicInteger admitted = new AtomicInteger();
      var tasks = new ArrayList<Future<?>>();
      try {
        for (int i = 0; i < 12; i++) {
          tasks.add(
              executor.submit(
                  () -> {
                    start.await();
                    ResourceBudget.Lease lease = null;
                    try {
                      lease = budget.reserve(1, 0, 1, 0);
                      admitted.incrementAndGet();
                    } catch (ArchiveException exhausted) {
                      assertEquals(
                          "io.resource-capacity",
                          exhausted.primaryFailure().diagnosticIdentifier().orElseThrow());
                    } finally {
                      attempted.countDown();
                    }
                    try {
                      assertTrue(release.await(5, TimeUnit.SECONDS));
                    } finally {
                      if (lease != null) lease.close();
                    }
                    return null;
                  }));
        }
        start.countDown();
        assertTrue(attempted.await(5, TimeUnit.SECONDS));
        assertEquals(1, admitted.get());
      } finally {
        // Release workers even when an assertion fails so executor cleanup cannot deadlock.
        start.countDown();
        release.countDown();
      }
      for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
      try (ResourceBudget.Lease remaining = budget.reserve(100, 0, 1, 100)) {
        assertNotNull(remaining);
      }
    }
  }

  /** The stored substrate permits one owned file handle and allocates no native working buffers. */
  @Test
  void defaultCapacityAdmitsOneHandleAndNoNativeBuffers() throws Exception {
    try (ResourceBudget budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT)) {
      assertThrows(ArchiveException.class, () -> budget.reserve(0, 1, 0, 0));
      try (ResourceBudget.Lease input = budget.reserve(512, 0, 1, 0)) {
        assertNotNull(input);
        assertThrows(ArchiveException.class, () -> budget.reserve(0, 0, 1, 0));
      }
    }
  }

  /** Constructs semantic limits with unrelated ceilings left at their documented defaults. */
  private static ResourceLimits limits(long entries, long metadata, long scratch) {
    ResourceLimits standard = ResourceLimits.standard();
    return new ResourceLimits(
        entries,
        metadata,
        standard.maxDecodedBytes(),
        scratch,
        standard.maxOutputs(),
        standard.maxDiagnostics(),
        standard.maxSecondaryFailures());
  }
}
