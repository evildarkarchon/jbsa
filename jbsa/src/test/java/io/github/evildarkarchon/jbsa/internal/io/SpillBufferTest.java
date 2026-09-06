package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.ArtifactState;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.Operation;
import io.github.evildarkarchon.jbsa.OperationPhase;
import io.github.evildarkarchon.jbsa.ResourceLimits;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Qualifies operation-owned scratch replay and cleanup against a real filesystem. */
final class SpillBufferTest {
  @TempDir Path scratchParent;

  /** Small scratch supports append and backpatching without creating a disk artifact. */
  @Test
  void replaysAndBackpatchesSmallScratch() throws Exception {
    IoContext context = IoContext.of(scratchParent, Operation.PACK);
    try (ResourceBudget budget = new ResourceBudget(ResourceLimits.standard(), context);
        SpillBuffer scratch = SpillBuffer.open(scratchParent, budget, context)) {
      scratch.write(0, ByteBuffer.wrap(new byte[] {1, 2, 3}));
      scratch.write(3, ByteBuffer.wrap(new byte[] {4, 5}));
      scratch.write(1, ByteBuffer.wrap(new byte[] {9, 8}));
      assertEquals(5L, scratch.size());
      ByteBuffer replay = ByteBuffer.allocate(5);
      scratch.read(0, replay);
      assertArrayEquals(new byte[] {1, 9, 8, 4, 5}, replay.array());
      try (var children = Files.list(scratchParent)) {
        assertEquals(0L, children.count());
      }
    }
  }

  /** Appending beyond a caller limit leaves the prior representation intact and reusable. */
  @Test
  void rejectsScratchGrowthBeforeChangingContents() throws Exception {
    IoContext context = IoContext.of(scratchParent, Operation.PACK);
    ResourceLimits standard = ResourceLimits.standard();
    ResourceLimits limits =
        new ResourceLimits(
            standard.maxEntries(),
            standard.maxMetadataBytes(),
            standard.maxDecodedBytes(),
            5,
            standard.maxOutputs(),
            standard.maxDiagnostics(),
            standard.maxSecondaryFailures());
    try (ResourceBudget budget = new ResourceBudget(limits, context)) {
      try (SpillBuffer scratch = SpillBuffer.open(scratchParent, budget, context)) {
        scratch.write(0, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        ByteBuffer excess = ByteBuffer.wrap(new byte[] {4, 5, 6});
        ArchiveException failure =
            assertThrows(ArchiveException.class, () -> scratch.write(3, excess));
        assertEquals(FailureKind.POLICY, failure.kind());
        assertEquals("maxScratchBytes", failure.diagnostics().getFirst().values().get("field"));
        assertEquals("6", failure.diagnostics().getFirst().values().get("observed"));
        assertEquals(0, excess.position());
        assertEquals(3L, scratch.size());
        scratch.write(3, ByteBuffer.wrap(new byte[] {4, 5}));
        scratch.write(0, ByteBuffer.wrap(new byte[] {9}));
        ByteBuffer replay = ByteBuffer.allocate(5);
        scratch.read(0, replay);
        assertArrayEquals(new byte[] {9, 2, 3, 4, 5}, replay.array());
      }
      try (ResourceBudget.Lease returned = budget.reserve(64 * 1024, 0, 1, 5)) {
        assertNotNull(returned);
      }
    }
  }

  /** Large scratch crosses into disk storage with one bounded memory credit and one handle. */
  @Test
  void spillsAndReplaysWithFixedWorkingMemoryThenRemovesItsFile() throws Exception {
    IoContext context = IoContext.of(scratchParent, Operation.PACK);
    try (ResourceBudget budget =
        new ResourceBudget(ResourceLimits.standard(), context, 64 * 1024, 0, 1)) {
      try (SpillBuffer scratch = SpillBuffer.open(scratchParent, budget, context)) {
        byte[] window = new byte[64 * 1024];
        java.util.Arrays.fill(window, (byte) 7);
        scratch.write(0, ByteBuffer.wrap(window));
        for (int index = 1; index < 4; index++)
          scratch.write(scratch.size(), ByteBuffer.wrap(window));
        scratch.write(64 * 1024 - 1, ByteBuffer.wrap(new byte[] {9, 8, 6}));
        ByteBuffer replay = ByteBuffer.allocate(5);
        scratch.read(64 * 1024 - 2, replay);
        assertArrayEquals(new byte[] {7, 9, 8, 6, 7}, replay.array());
        assertEquals(256 * 1024L, scratch.size());
        try (var children = Files.list(scratchParent)) {
          assertEquals(1L, children.count());
        }
      }
      try (var children = Files.list(scratchParent)) {
        assertEquals(0L, children.count());
      }
      try (ResourceBudget.Lease returned = budget.reserve(64 * 1024, 0, 1, 256 * 1024)) {
        assertNotNull(returned);
      }
    }
  }

  /** Empty spans are valid, invalid extents cannot grow scratch, and sealing fixes replay bytes. */
  @Test
  void checksExtentsAndSealsUntilIdempotentClose() throws Exception {
    IoContext context = IoContext.of(scratchParent, Operation.PACK);
    try (ResourceBudget budget = new ResourceBudget(ResourceLimits.standard(), context)) {
      SpillBuffer scratch = SpillBuffer.open(scratchParent, budget, context);
      scratch.write(0, ByteBuffer.allocate(0));
      scratch.read(0, ByteBuffer.allocate(0));
      ArchiveException gap =
          assertThrows(
              ArchiveException.class, () -> scratch.write(1, ByteBuffer.wrap(new byte[] {1})));
      assertEquals(FailureKind.DESTINATION, gap.kind());
      assertThrows(
          ArchiveException.class, () -> scratch.write(Long.MAX_VALUE, ByteBuffer.allocate(1)));
      assertThrows(ArchiveException.class, () -> scratch.write(-1, ByteBuffer.allocate(0)));
      scratch.write(0, ByteBuffer.wrap(new byte[] {7}));
      assertThrows(ArchiveException.class, () -> scratch.read(1, ByteBuffer.allocate(1)));
      scratch.seal();
      scratch.seal();
      assertThrows(ArchiveException.class, () -> scratch.write(0, ByteBuffer.wrap(new byte[] {9})));
      ByteBuffer replay = ByteBuffer.allocate(1);
      scratch.read(0, replay);
      assertArrayEquals(new byte[] {7}, replay.array());
      scratch.close();
      scratch.close();
      assertThrows(
          java.nio.channels.ClosedChannelException.class,
          () -> scratch.read(0, ByteBuffer.allocate(1)));
      assertThrows(
          java.nio.channels.ClosedChannelException.class,
          () -> scratch.write(0, ByteBuffer.allocate(1)));
    }
  }

  /** A real Windows delete denial returns the exact residual and transfers cleanup ownership. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void reportsExactResidualAndReturnsCreditsWhenScratchDeletionFails() throws Exception {
    IoContext context = IoContext.of(scratchParent, Operation.PACK);
    try (ResourceBudget budget =
        new ResourceBudget(ResourceLimits.standard(), context, 64 * 1024, 0, 1)) {
      SpillBuffer scratch = SpillBuffer.open(scratchParent, budget, context);
      scratch.write(0, ByteBuffer.allocate(64 * 1024 + 1));
      Path residual;
      try (var children = Files.list(scratchParent)) {
        residual = children.findFirst().orElseThrow();
      }
      Files.setAttribute(residual, "dos:readonly", true);
      try {
        ArchiveException failure = assertThrows(ArchiveException.class, scratch::close);
        assertEquals(FailureKind.DESTINATION, failure.kind());
        assertEquals(OperationPhase.CLEANUP, failure.primaryFailure().phase());
        assertEquals(1, failure.artifacts().size());
        assertEquals(residual.toAbsolutePath().normalize(), failure.artifacts().getFirst().path());
        assertEquals(ArtifactState.RESIDUAL_STAGING, failure.artifacts().getFirst().state());
        assertTrue(Files.exists(residual));
        scratch.close();
        try (ResourceBudget.Lease returned = budget.reserve(64 * 1024, 0, 1, 64 * 1024 + 1)) {
          assertNotNull(returned);
        }
      } finally {
        // Reporting the residual transfers ownership; the test now performs caller-owned cleanup.
        Files.setAttribute(residual, "dos:readonly", false);
        Files.delete(residual);
      }
    }
  }

  /**
   * A filesystem failure aborts scratch ownership while preserving the structured primary cause.
   */
  @Test
  void closesFailedSpillCreationAndReleasesItsReservations() throws Exception {
    Path unavailableParent = scratchParent.resolve("ordinary-file");
    Files.write(unavailableParent, new byte[] {3});
    IoContext context = IoContext.of(scratchParent, Operation.PACK);
    try (ResourceBudget budget =
        new ResourceBudget(ResourceLimits.standard(), context, 64 * 1024, 0, 1)) {
      SpillBuffer scratch = SpillBuffer.open(unavailableParent, budget, context);
      ArchiveException failure =
          assertThrows(
              ArchiveException.class, () -> scratch.write(0, ByteBuffer.allocate(64 * 1024 + 1)));
      assertEquals(FailureKind.DESTINATION, failure.kind());
      assertTrue(failure.artifacts().isEmpty());
      assertEquals(
          unavailableParent,
          failure.primaryFailure().location().orElseThrow().artifact().orElseThrow());
      assertThrows(
          java.nio.channels.ClosedChannelException.class,
          () -> scratch.read(0, ByteBuffer.allocate(0)));
      scratch.close();
      try (ResourceBudget.Lease returned = budget.reserve(64 * 1024, 0, 1, 64 * 1024 + 1)) {
        assertNotNull(returned);
      }
    }
  }

  /** Replay damage stays primary when a subsequent real cleanup denial leaves a residual. */
  @ParameterizedTest
  @ValueSource(longs = {0, 1})
  @EnabledOnOs(OS.WINDOWS)
  void preservesReplayFailureAndBoundsSecondaryCleanupFailures(long secondaryLimit)
      throws Exception {
    IoContext context =
        new IoContext(
            scratchParent, Operation.PACK, OperationPhase.PROCESSING, java.util.OptionalLong.of(9));
    ResourceLimits standard = ResourceLimits.standard();
    ResourceLimits limits =
        new ResourceLimits(
            standard.maxEntries(),
            standard.maxMetadataBytes(),
            standard.maxDecodedBytes(),
            standard.maxScratchBytes(),
            standard.maxOutputs(),
            1,
            secondaryLimit);
    try (ResourceBudget budget = new ResourceBudget(limits, context)) {
      SpillBuffer scratch = SpillBuffer.open(scratchParent, budget, context);
      scratch.write(0, ByteBuffer.allocate(64 * 1024 + 1));
      Path residual;
      try (var children = Files.list(scratchParent)) {
        residual = children.findFirst().orElseThrow();
      }
      // Corrupt only owned scratch to inject a provider-level short read, then deny its deletion.
      Files.write(residual, new byte[0]);
      Files.setAttribute(residual, "dos:readonly", true);
      try {
        ArchiveException failure =
            assertThrows(ArchiveException.class, () -> scratch.read(0, ByteBuffer.allocate(1)));
        assertEquals(FailureKind.DESTINATION, failure.kind());
        assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
        assertEquals(secondaryLimit, failure.secondaryFailures().size());
        if (secondaryLimit > 0) {
          assertEquals(OperationPhase.CLEANUP, failure.secondaryFailures().getFirst().phase());
        }
        assertEquals(1, failure.diagnostics().size());
        assertEquals("operation.records-truncated", failure.diagnostics().getFirst().identifier());
        assertEquals("2", failure.diagnostics().getFirst().values().get("omittedDiagnostics"));
        assertEquals(
            secondaryLimit == 0 ? "1" : "0",
            failure.diagnostics().getFirst().values().get("omittedSecondaryFailures"));
        assertEquals(
            "false",
            failure.diagnostics().getFirst().values().get("additionalDiagnosticsMayExist"));
        assertEquals(
            "false",
            failure.diagnostics().getFirst().values().get("additionalSecondaryFailuresMayExist"));
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(residual, failure.artifacts().getFirst().path());
        assertEquals(9, failure.artifacts().getFirst().ordinal());
        assertEquals(ArtifactState.RESIDUAL_STAGING, failure.artifacts().getFirst().state());
        scratch.close();
      } finally {
        // The exception transfers the exact residual into caller-owned cleanup.
        Files.setAttribute(residual, "dos:readonly", false);
        Files.delete(residual);
      }
    }
  }
}
