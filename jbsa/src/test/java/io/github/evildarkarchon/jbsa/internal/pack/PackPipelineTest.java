package io.github.evildarkarchon.jbsa.internal.pack;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import io.github.evildarkarchon.jbsa.internal.io.PublicationTransaction;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives {@link PackPipeline} directly through the test-only {@link MemoryEncoder} adapter, so the
 * pipeline's own lifecycle is observable without any real Archive Family's wire rules.
 */
class PackPipelineTest {
  @TempDir Path temporary;

  /** Counts every payload factory invocation made by the current test's sources. */
  private final AtomicInteger opens = new AtomicInteger();

  /**
   * The current test's source opens, as {@code "open <name>"}, interleaved with whatever else the
   * test records, such as Progress Snapshots.
   */
  private final List<Object> trace = Collections.synchronizedList(new ArrayList<>());

  /**
   * The fixed sequence: admit, source planning, adapter plan, unmatched overrides, the empty entry
   * set, then the decoded-size limit. Each rung repairs only the rule that fired on the previous
   * one.
   */
  @Test
  void admissionRunsInTheFixedSequence() throws Exception {
    Path target = temporary.resolve("order.mem");
    PackSource missing = new PackSource.DetectedPath(temporary.resolve("missing"));
    var unmatched = Map.of(new NormalizedNameIdentity("nobody"), PackOptions.Compression.STORED);
    var tight = limits(1);

    var events = new ArrayList<String>();
    var refusing = new MemoryEncoder(0, true, events);
    assertRejected(
        refusing,
        request(target, options(0, true, unmatched), tight, 1, missing, source("bad", 2)),
        FailureKind.UNSUPPORTED,
        "memory.admit-refused");
    assertEquals(List.of("admit"), events, "admit precedes source planning");

    var encoder = new MemoryEncoder(0, false, events);
    // Source planning runs before the adapter's own per-entry rules.
    assertEquals(
        FailureKind.SOURCE,
        assertRejected(
                encoder,
                request(target, options(0, true, unmatched), tight, 1, missing, source("bad", 2)))
            .kind());
    assertRejected(
        encoder,
        request(target, options(0, true, unmatched), tight, 1, source("bad", 2)),
        FailureKind.POLICY,
        "memory.bad-name");
    assertRejected(
        encoder,
        request(target, options(0, true, unmatched), tight, 1, source("good", 2)),
        FailureKind.POLICY,
        "pack.unmatched-entry-compression");
    assertRejected(
        encoder,
        request(target, options(0, true, unmatched), tight, 1),
        FailureKind.POLICY,
        "pack.unmatched-entry-compression");
    assertRejected(
        encoder,
        request(target, options(0, true, Map.of()), tight, 1),
        FailureKind.POLICY,
        "memory.empty-entry-set");
    assertRejected(
        encoder,
        request(target, options(0, true, Map.of()), tight, 1, source("good", 2)),
        FailureKind.POLICY,
        "operation.resource-limit");
    assertEquals(0, opens.get());
  }

  /** Q14: the limit is charged in Logical Plan Order even though output order is reversed. */
  @Test
  void decodedLimitIsChargedInLogicalPlanOrder() {
    var failure =
        assertRejected(
            new MemoryEncoder(0),
            request(
                temporary.resolve("limit.mem"),
                options(0, true, Map.of()),
                limits(5),
                1,
                source("a", 5),
                source("b", 1),
                source("c", 1)),
            FailureKind.POLICY,
            "operation.resource-limit");
    // Output order (c, b, a) would observe 7; plan order (a, b) trips first at 6.
    assertEquals("6", failure.diagnostics().getFirst().values().get("observed"));
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
  }

  /**
   * A programmer error raised by admission ends as INTERNAL {@code operation.internal-failure} at
   * PREFLIGHT, retaining the error as its cause, and still settles zero CLEANUP progress.
   */
  @Test
  void unexpectedAdmitErrorSettlesInternalFailureWithCleanup() {
    var bug = new IllegalStateException("admit bug");
    FamilyAdapter<String> adapter =
        (request, context) -> {
          throw bug;
        };
    var snapshots = new ArrayList<ProgressSnapshot>();
    var request =
        request(
            temporary.resolve("admit.mem"),
            options(0, true, Map.of()),
            limits(10),
            1,
            source("a", 1));

    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PackPipeline.pack(
                    request, new OperationControl(snapshots::add, () -> false), adapter));

    assertInternal(failure, OperationPhase.PREFLIGHT);
    assertSame(bug, failure.primaryFailure().cause().orElseThrow());
    assertEquals(
        List.of(
            snapshot(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 0, OptionalLong.empty()),
            snapshot(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0, OptionalLong.empty()),
            snapshot(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0, OptionalLong.of(0))),
        snapshots);
    assertEquals(0, opens.get());
    assertFalse(Files.exists(request.destination()), "an output was left");
  }

  /**
   * A programmer error raised by the adapter's plan, after source planning counted its one entry,
   * ends the same way: INTERNAL at PREFLIGHT with its cause, then zero CLEANUP progress.
   */
  @Test
  void unexpectedPlanErrorSettlesInternalFailureWithCleanup() {
    var snapshots = new ArrayList<ProgressSnapshot>();
    var request =
        request(
            temporary.resolve("plan.mem"),
            options(0, true, Map.of()),
            limits(10),
            1,
            source("boom", 1));

    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PackPipeline.pack(
                    request,
                    new OperationControl(snapshots::add, () -> false),
                    new MemoryEncoder(0)));

    assertInternal(failure, OperationPhase.PREFLIGHT);
    var cause =
        assertInstanceOf(AssertionError.class, failure.primaryFailure().cause().orElseThrow());
    assertEquals("plan bug", cause.getMessage());
    assertEquals(
        List.of(
            snapshot(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 0, OptionalLong.empty()),
            snapshot(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 1, OptionalLong.empty()),
            snapshot(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0, OptionalLong.empty()),
            snapshot(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0, OptionalLong.of(0))),
        snapshots);
    assertEquals(0, opens.get());
    assertFalse(Files.exists(request.destination()), "an output was left");
  }

  /**
   * Streamed emission carries stored, unframed payloads only. A streamed plan holding a compressed
   * ({@code .z}) or framed ({@code .f}) entry is a programmer error caught once, before splitting
   * and before any source is read: INTERNAL at PREFLIGHT, then zero CLEANUP progress, and no part
   * is published. The stored entry {@code b} comes first in output order, so a check made while
   * staging would already have read it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"a.z", "a.f"})
  void streamedPlanOfANonStoredOrFramedEntryFailsFastAsInternal(String unsupported)
      throws IOException {
    var snapshots = new ArrayList<ProgressSnapshot>();
    var request =
        request(
            temporary.resolve("streamed.mem"),
            options(1, true, Map.of()),
            limits(10),
            1,
            source(unsupported, 2),
            source("b", 2));

    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PackPipeline.pack(
                    request,
                    new OperationControl(snapshots::add, () -> false),
                    new MemoryEncoder(0)));

    assertInternal(failure, OperationPhase.PREFLIGHT);
    assertInstanceOf(IllegalStateException.class, failure.primaryFailure().cause().orElseThrow());
    assertEquals(
        List.of(
            snapshot(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 0, OptionalLong.empty()),
            snapshot(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 1, OptionalLong.empty()),
            snapshot(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 2, OptionalLong.empty()),
            snapshot(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0, OptionalLong.empty()),
            snapshot(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0, OptionalLong.of(0))),
        snapshots);
    assertEquals(0, opens.get(), "a source was read");
    // The one-byte target would split each entry into its own part; neither part may exist.
    try (var left = Files.list(temporary)) {
      assertEquals(List.of(), left.toList(), "a part was published");
    }
  }

  /**
   * An explicit target splits a streamed pack into whole-entry parts, published under numbered
   * names. The Splitting arms themselves are pinned by {@code SplitPlannerTest}.
   */
  @Test
  void explicitSplitTargetPublishesNumberedParts() throws Exception {
    var report =
        PackPipeline.pack(
            request(
                temporary.resolve("split.mem"),
                options(1, false, Map.of()),
                ResourceLimits.standard(),
                1,
                source("a", 3),
                source("b", 3)),
            OperationControl.standard(),
            new MemoryEncoder(0));
    assertEquals(2, report.archiveParts().size());
    assertTrue(Files.exists(temporary.resolve("split.mem")));
    assertTrue(Files.exists(temporary.resolve("split2.mem")));
  }

  /** Preflight checks exactly the known part set before any source payload is opened. */
  @Test
  void preflightChecksTheKnownPartCountBeforeReadingSources() throws Exception {
    Path target = temporary.resolve("parts.mem");
    Path second = PublicationTransaction.splitPath(target, 2);
    Files.write(second, new byte[] {7});
    var failure =
        assertRejected(
            new MemoryEncoder(0),
            request(
                target,
                options(1, false, Map.of()),
                ResourceLimits.standard(),
                1,
                source("a", 3),
                source("b", 3)),
            FailureKind.POLICY,
            "extraction.target-exists");
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(second));

    // A single-part plan never targets the numbered sibling, so it publishes beside it.
    var report =
        PackPipeline.pack(
            request(
                target,
                options(0, false, Map.of()),
                ResourceLimits.standard(),
                1,
                source("a", 3),
                source("b", 3)),
            OperationControl.standard(),
            new MemoryEncoder(0));
    assertEquals(1, report.archiveParts().size());
  }

  /** Output order, tables, and per-part Content Sharing of equal payloads. */
  @Test
  void sharesIdenticalPayloadsWithinOnePartOnly() throws Exception {
    Path whole = temporary.resolve("whole.mem");
    PackPipeline.pack(
        request(
            whole,
            options(0, true, Map.of()),
            ResourceLimits.standard(),
            1,
            source("x", new byte[] {1, 2, 3, 4}),
            source("y", new byte[] {1, 2, 3, 4}),
            source("z", new byte[] {1, 2, 3, 5})),
        OperationControl.standard(),
        new MemoryEncoder(0));
    var records = MemoryEncoder.read(whole);
    // Output order reverses plan order: z, y, x. y is placed first, so x shares y's copy.
    assertEquals(List.of("z", "y", "x"), records.stream().map(Record::name).toList());
    assertEquals(records.get(1).offset(), records.get(2).offset());
    assertNotEquals(records.get(0).offset(), records.get(1).offset());
    assertEquals(8 + 3 * 16 + 8, Files.size(whole));
    for (Record record : records)
      assertArrayEquals(
          record.name().equals("z") ? new byte[] {1, 2, 3, 5} : new byte[] {1, 2, 3, 4},
          record.payload());

    // With one entry per part, each part must keep its own copy.
    Path split = temporary.resolve("split.mem");
    var report =
        PackPipeline.pack(
            request(
                split,
                options(1, true, Map.of()),
                ResourceLimits.standard(),
                1,
                source("x", new byte[] {1, 2, 3, 4}),
                source("y", new byte[] {1, 2, 3, 4})),
            OperationControl.standard(),
            new MemoryEncoder(0));
    for (var part : report.archiveParts()) {
      assertEquals(8 + 16 + 4, part.byteSize());
      assertArrayEquals(
          new byte[] {1, 2, 3, 4}, MemoryEncoder.read(part.path()).getFirst().payload());
    }
  }

  /** Cooperative Cancellation during payload processing publishes nothing. */
  @Test
  void cancellationDuringProcessingPublishesNothing() {
    var cancelled = new AtomicBoolean();
    var control = new OperationControl(snapshot -> {}, cancelled::get);
    PackSource cancelling =
        new PackSource.GeneratedEntry(
            "a",
            1,
            () -> {
              cancelled.set(true);
              return Channels.newChannel(new ByteArrayInputStream(new byte[1]));
            });
    Path target = temporary.resolve("cancelled.mem");
    var failure =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                PackPipeline.pack(
                    request(
                        target,
                        options(0, false, Map.of()),
                        ResourceLimits.standard(),
                        1,
                        cancelling),
                    control,
                    new MemoryEncoder(0)));
    assertEquals(FailureKind.CANCELLED, failure.kind());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertFalse(Files.exists(target));
  }

  /**
   * Worker failures keep their structured kind at the entry's output ordinal: an I/O failure is
   * SOURCE and an unexpected runtime failure is INTERNAL.
   */
  @Test
  void workerFailuresMapToStructuredKindsAtTheirOutputOrdinal() {
    PackSource opening =
        new PackSource.GeneratedEntry(
            "a",
            1,
            () -> {
              throw new IOException("open");
            });
    var io =
        assertThrows(
            ArchiveException.class,
            () ->
                PackPipeline.pack(
                    request(
                        temporary.resolve("io.mem"),
                        options(0, false, Map.of()),
                        ResourceLimits.standard(),
                        4,
                        opening,
                        source("b", 1),
                        source("c", 1)),
                    OperationControl.standard(),
                    new MemoryEncoder(0)));
    assertEquals(FailureKind.SOURCE, io.kind());
    assertEquals(Optional.of("operation.source-io"), io.primaryFailure().diagnosticIdentifier());
    assertEquals(OperationPhase.PROCESSING, io.primaryFailure().phase());
    // "a" is first in plan order but last in the reversed output order.
    assertEquals(OptionalLong.of(2), io.primaryFailure().ordinal());

    PackSource throwing =
        new PackSource.GeneratedEntry(
            "a",
            1,
            () ->
                new ReadableByteChannel() {
                  public int read(ByteBuffer bytes) {
                    throw new IllegalStateException("provider bug");
                  }

                  public boolean isOpen() {
                    return true;
                  }

                  public void close() {
                    // Nothing to release.
                  }
                });
    var internal =
        assertThrows(
            ArchiveException.class,
            () ->
                PackPipeline.pack(
                    request(
                        temporary.resolve("internal.mem"),
                        options(0, false, Map.of()),
                        ResourceLimits.standard(),
                        4,
                        throwing,
                        source("b", 1),
                        source("c", 1)),
                    OperationControl.standard(),
                    new MemoryEncoder(0)));
    assertEquals(FailureKind.INTERNAL, internal.kind());
    assertEquals(
        Optional.of("operation.internal-failure"),
        internal.primaryFailure().diagnosticIdentifier());
    assertFalse(Files.exists(temporary.resolve("io.mem")));
    assertFalse(Files.exists(temporary.resolve("internal.mem")));
  }

  /**
   * The earliest output ordinal's failure is primary even when a later one fails too, and a source
   * close failure behind a read failure is retained as a CLEANUP Secondary Failure.
   */
  @Test
  void primaryAndSecondaryFailuresAreOrdered() {
    var twoFailures =
        assertThrows(
            ArchiveException.class,
            () ->
                PackPipeline.pack(
                    request(
                        temporary.resolve("two.mem"),
                        options(0, false, Map.of()),
                        ResourceLimits.standard(),
                        4,
                        failing("a", false),
                        failing("b", false)),
                    OperationControl.standard(),
                    new MemoryEncoder(0)));
    // Output order is b, a: b is ordinal 0 and must be primary whichever worker failed first.
    assertEquals(OptionalLong.of(0), twoFailures.primaryFailure().ordinal());

    var readThenClose =
        assertThrows(
            ArchiveException.class,
            () ->
                PackPipeline.pack(
                    request(
                        temporary.resolve("close.mem"),
                        options(0, true, Map.of()),
                        ResourceLimits.standard(),
                        1,
                        failing("a", true)),
                    OperationControl.standard(),
                    new MemoryEncoder(0)));
    assertEquals("read", readThenClose.getCause().getMessage());
    assertEquals(OperationPhase.PROCESSING, readThenClose.primaryFailure().phase());
    assertEquals(1, readThenClose.secondaryFailures().size());
    var secondary = readThenClose.secondaryFailures().getFirst();
    assertEquals(OperationPhase.CLEANUP, secondary.phase());
    assertEquals("close", secondary.cause().orElseThrow().getMessage());
  }

  /**
   * Set-wide raw sharing decides owners once for the archive set: a shared owner costs a part only
   * at its first reference, so equal payloads fit a part they would overflow unshared, and every
   * part that references an owner emits its own copy. Worker count never changes the output.
   */
  @Test
  void sharesRawOwnersAcrossTheArchiveSetOncePerPart() throws Exception {
    for (long workers : new long[] {1, 4}) {
      // Output order is z, y, x. x shares the owner y created, so it costs nothing in their part.
      Path shared = temporary.resolve("shared" + workers + ".mem");
      var report =
          PackPipeline.pack(
              request(
                  shared,
                  options(8, true, Map.of()),
                  ResourceLimits.standard(),
                  workers,
                  source("x", new byte[] {1, 2, 3, 4}),
                  source("y", new byte[] {1, 2, 3, 4}),
                  source("z", new byte[] {1, 2, 3, 5})),
              OperationControl.standard(),
              MemoryEncoder.setWide(false));
      assertEquals(1, report.archiveParts().size());
      var records = MemoryEncoder.read(shared);
      assertEquals(List.of("z", "y", "x"), records.stream().map(Record::name).toList());
      assertEquals(records.get(1).offset(), records.get(2).offset());
      assertEquals(8 + 3 * 16 + 8, Files.size(shared));

      // Without sharing x costs its own four bytes and overflows into a second part.
      var unshared =
          PackPipeline.pack(
              request(
                  temporary.resolve("unshared" + workers + ".mem"),
                  options(8, false, Map.of()),
                  ResourceLimits.standard(),
                  workers,
                  source("x", new byte[] {1, 2, 3, 4}),
                  source("y", new byte[] {1, 2, 3, 4}),
                  source("z", new byte[] {1, 2, 3, 5})),
              OperationControl.standard(),
              MemoryEncoder.setWide(false));
      assertEquals(2, unshared.archiveParts().size());

      // One owner, referenced from two parts, is emitted in each of them. Shared, y costs nothing
      // and still overflows a one-byte target; re-costed in its new part, it pays for the owner.
      var perEntry =
          PackPipeline.pack(
              request(
                  temporary.resolve("per-entry" + workers + ".mem"),
                  options(1, true, Map.of()),
                  ResourceLimits.standard(),
                  workers,
                  source("x", new byte[] {1, 2, 3, 4}),
                  source("y", new byte[] {1, 2, 3, 4})),
              OperationControl.standard(),
              MemoryEncoder.setWide(false));
      assertEquals(2, perEntry.archiveParts().size());
      for (var part : perEntry.archiveParts()) {
        assertEquals(8 + 16 + 4, part.byteSize());
        assertArrayEquals(
            new byte[] {1, 2, 3, 4}, MemoryEncoder.read(part.path()).getFirst().payload());
      }
    }
  }

  /**
   * A declared readback reopens every staged part before any is published. MEM1 is not a readable
   * archive, so the reopen's own structured failure is primary, and no part of the set appears.
   */
  @Test
  void readbackFailurePublishesNoPart() throws Exception {
    Path target = temporary.resolve("readback.mem");
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PackPipeline.pack(
                    request(
                        target,
                        options(1, false, Map.of()),
                        ResourceLimits.standard(),
                        1,
                        source("a", 3),
                        source("b", 3)),
                    OperationControl.standard(),
                    MemoryEncoder.setWide(true)));
    assertEquals(FailureKind.FORMAT, failure.kind());
    assertEquals(
        Optional.of("archive.unrecognized"), failure.primaryFailure().diagnosticIdentifier());
    assertFalse(Files.exists(target));
    assertFalse(Files.exists(PublicationTransaction.splitPath(target, 2)));

    // The same plan without a readback declaration publishes both parts.
    var report =
        PackPipeline.pack(
            request(
                target,
                options(1, false, Map.of()),
                ResourceLimits.standard(),
                1,
                source("a", 3),
                source("b", 3)),
            OperationControl.standard(),
            MemoryEncoder.setWide(false));
    assertEquals(2, report.archiveParts().size());
  }

  /**
   * Per-part stored records share only when their complete stored records, frame included, are
   * byte-identical and they land in one Archive Part. A record shared in one part still costs its
   * full stored size when splitting, and each part that holds it keeps its own copy.
   */
  @Test
  void perPartRecordsShareIdenticalRecordsWithinOnePartOnly() throws Exception {
    Path whole = temporary.resolve("records.mem");
    PackPipeline.pack(
        request(
            whole,
            options(0, true, Map.of()),
            ResourceLimits.standard(),
            1,
            source("x", new byte[] {1, 2, 3, 4}),
            source("y", new byte[] {1, 2, 3, 4}),
            source("zz", new byte[] {1, 2, 3, 4}),
            source("w", new byte[] {1, 2, 3, 5})),
        OperationControl.standard(),
        MemoryEncoder.perPartRecords(Long.MAX_VALUE));
    var records = MemoryEncoder.read(whole);
    // Output order is w, zz, y, x. x's record equals y's, so x references y's copy; zz has the
    // same payload under a two-byte name, so its frame makes it a distinct record.
    assertEquals(List.of("w", "zz", "y", "x"), records.stream().map(Record::name).toList());
    assertEquals(records.get(2).offset(), records.get(3).offset());
    assertEquals(3, records.stream().map(Record::offset).distinct().count());
    assertEquals(8 + 4 * 16 + 3 * 5, Files.size(whole));
    assertArrayEquals(new byte[] {1, 1, 2, 3, 5}, records.get(0).payload());
    assertArrayEquals(new byte[] {2, 1, 2, 3, 4}, records.get(1).payload());
    assertArrayEquals(new byte[] {1, 1, 2, 3, 4}, records.get(2).payload());
    assertArrayEquals(new byte[] {1, 1, 2, 3, 4}, records.get(3).payload());

    // Two five-byte records fit a ten-byte target, and the second shares the first's copy.
    Path fits = temporary.resolve("fits.mem");
    var together =
        PackPipeline.pack(
            request(
                fits,
                options(10, true, Map.of()),
                ResourceLimits.standard(),
                1,
                source("x", new byte[] {1, 2, 3, 4}),
                source("y", new byte[] {1, 2, 3, 4})),
            OperationControl.standard(),
            MemoryEncoder.perPartRecords(Long.MAX_VALUE));
    assertEquals(1, together.archiveParts().size());
    assertEquals(8 + 2 * 16 + 5, Files.size(fits));

    // A nine-byte target splits them even though sharing would have stored only five bytes, and
    // each part keeps its own copy of the record.
    var apart =
        PackPipeline.pack(
            request(
                temporary.resolve("apart.mem"),
                options(9, true, Map.of()),
                ResourceLimits.standard(),
                1,
                source("x", new byte[] {1, 2, 3, 4}),
                source("y", new byte[] {1, 2, 3, 4})),
            OperationControl.standard(),
            MemoryEncoder.perPartRecords(Long.MAX_VALUE));
    assertEquals(2, apart.archiveParts().size());
    for (var part : apart.archiveParts()) {
      assertEquals(8 + 16 + 5, part.byteSize());
      assertArrayEquals(
          new byte[] {1, 1, 2, 3, 4}, MemoryEncoder.read(part.path()).getFirst().payload());
    }
  }

  /**
   * Splitting charges each record's exact stabilized stored size, frame included, which for an
   * encoded record is known only after stabilization. The sizes come from an unsplit pack's table,
   * and a target of exactly their sum keeps both records together while one byte less splits them.
   */
  @Test
  void perPartRecordSplitChargesTheExactStabilizedStoredSize() throws Exception {
    byte[] zeros = new byte[4096];
    byte[] pattern = new byte[4096];
    for (int index = 0; index < pattern.length; index++) pattern[index] = (byte) (index % 7);

    Path whole = temporary.resolve("measured.mem");
    PackPipeline.pack(
        request(
            whole,
            options(0, false, Map.of()),
            ResourceLimits.standard(),
            1,
            source("a.z", zeros),
            source("b.z", pattern)),
        OperationControl.standard(),
        MemoryEncoder.perPartRecords(Long.MAX_VALUE));
    var records = MemoryEncoder.read(whole);
    assertEquals(List.of("b.z", "a.z"), records.stream().map(Record::name).toList());
    long first = records.get(0).payload().length;
    long second = records.get(1).payload().length;
    for (Record record : records) {
      assertEquals(3, record.payload()[0], "each record starts with its frame");
      assertTrue(record.payload().length < 4096, "a zlib record is smaller than its source");
    }

    var together =
        PackPipeline.pack(
            request(
                temporary.resolve("exact.mem"),
                options(first + second, false, Map.of()),
                ResourceLimits.standard(),
                1,
                source("a.z", zeros),
                source("b.z", pattern)),
            OperationControl.standard(),
            MemoryEncoder.perPartRecords(Long.MAX_VALUE));
    assertEquals(1, together.archiveParts().size());

    var split =
        PackPipeline.pack(
            request(
                temporary.resolve("short.mem"),
                options(first + second - 1, false, Map.of()),
                ResourceLimits.standard(),
                1,
                source("a.z", zeros),
                source("b.z", pattern)),
            OperationControl.standard(),
            MemoryEncoder.perPartRecords(Long.MAX_VALUE));
    assertEquals(2, split.archiveParts().size());
    assertEquals(8 + 16 + first, split.archiveParts().get(0).byteSize());
    assertEquals(8 + 16 + second, split.archiveParts().get(1).byteSize());
  }

  /**
   * A record {@code checkStored} rejects fails at its own PROCESSING ordinal, as soon as it is
   * stabilized: no later entry's source is opened, and nothing is published.
   */
  @Test
  void perPartRecordCheckStoredFailureKeepsItsProcessingOrdinal() {
    Path target = temporary.resolve("rejected.mem");
    // Output order is c, b, a; b's record is its one-byte frame plus three bytes, over the limit.
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PackPipeline.pack(
                    request(
                        target,
                        options(0, false, Map.of()),
                        ResourceLimits.standard(),
                        1,
                        source("a", 1),
                        source("b", 3),
                        source("c", 1)),
                    OperationControl.standard(),
                    MemoryEncoder.perPartRecords(3)));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(
        Optional.of("memory.record-too-large"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertEquals(OptionalLong.of(1), failure.primaryFailure().ordinal());
    assertEquals(List.of("open c", "open b"), trace);
    assertFalse(Files.exists(target));
  }

  /**
   * Stabilization is processing work: progress enters PROCESSING before the first source is read,
   * and publication counts its entries in that same phase instead of entering it again.
   */
  @Test
  void perPartRecordsEnterProcessingBeforeReadingAndPublishInIt() throws Exception {
    var control = new OperationControl(trace::add, () -> false);
    PackPipeline.pack(
        request(
            temporary.resolve("progress.mem"),
            options(0, false, Map.of()),
            ResourceLimits.standard(),
            1,
            source("a", 1),
            source("b", 1)),
        control,
        MemoryEncoder.perPartRecords(Long.MAX_VALUE));
    var none = OptionalLong.empty();
    assertEquals(
        List.of(
            snapshot(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 0, none),
            snapshot(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 1, none),
            snapshot(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 2, none),
            snapshot(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 2, OptionalLong.of(2)),
            snapshot(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 0, none),
            snapshot(OperationPhase.PROCESSING, ProgressMetric.BYTES, 0, none),
            // Stabilization reads every source in output order (b, a) before any part stages.
            "open b",
            "open a",
            snapshot(OperationPhase.PROCESSING, ProgressMetric.BYTES, 1, none),
            snapshot(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 1, none),
            snapshot(OperationPhase.PROCESSING, ProgressMetric.BYTES, 2, none),
            snapshot(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 2, none),
            snapshot(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 2, OptionalLong.of(2)),
            snapshot(OperationPhase.PROCESSING, ProgressMetric.BYTES, 2, OptionalLong.of(2)),
            snapshot(OperationPhase.PUBLISHING, ProgressMetric.ARTIFACTS, 0, none),
            snapshot(OperationPhase.PUBLISHING, ProgressMetric.ARTIFACTS, 1, none),
            snapshot(OperationPhase.PUBLISHING, ProgressMetric.ARTIFACTS, 1, OptionalLong.of(1)),
            snapshot(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0, none),
            snapshot(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 1, OptionalLong.of(1))),
        trace);
  }

  /**
   * The stable spool outlives every part's staging and closes before the Publication Commit: it is
   * still on disk while the last part counts its last entry, and gone, with nothing published yet,
   * once publication enters PUBLISHING.
   */
  @Test
  void perPartRecordSpoolClosesAfterTheLastPartStagesAndBeforeCommit() throws Throwable {
    Path spool = Files.createDirectory(temporary.resolve("spool"));
    Path target = temporary.resolve("spooled.mem");
    var observed = new ArrayList<SpoolObservation>();
    var control =
        new OperationControl(
            snapshot ->
                observed.add(
                    new SpoolObservation(snapshot, holdsSpool(spool), Files.exists(target))),
            () -> false);
    // Three 40 001-byte records outgrow the spool's 64 KiB heap window, so it spills to a file.
    // The 80 002-byte target puts c and b in the first part and a in the second.
    var report =
        withSpoolDirectory(
            spool,
            () ->
                PackPipeline.pack(
                    request(
                        target,
                        options(80_002, false, Map.of()),
                        ResourceLimits.standard(),
                        1,
                        source("a", 40_000),
                        source("b", 40_000),
                        source("c", 40_000)),
                    control,
                    MemoryEncoder.perPartRecords(Long.MAX_VALUE)));
    assertEquals(2, report.archiveParts().size());

    var lastEntry =
        observed.stream()
            .filter(
                seen ->
                    seen.snapshot().phase() == OperationPhase.PROCESSING
                        && seen.snapshot().metric() == ProgressMetric.ENTRIES
                        && seen.snapshot().completed() == 3
                        && seen.snapshot().total().isEmpty())
            .findFirst()
            .orElseThrow();
    assertTrue(lastEntry.spool(), "the spool closed before the last part staged");
    var publishing =
        observed.stream()
            .filter(seen -> seen.snapshot().phase() == OperationPhase.PUBLISHING)
            .findFirst()
            .orElseThrow();
    assertFalse(publishing.spool(), "the spool was still open entering PUBLISHING");
    assertFalse(publishing.published(), "a part was published before the spool closed");
    assertFalse(holdsSpool(spool));
    assertTrue(Files.exists(target));
  }

  /**
   * A failure during stabilization leaves the spool to the operation template, which closes it with
   * every other registered resource before CLEANUP begins; the failure stays primary with no
   * cleanup evidence behind it.
   */
  @Test
  void perPartRecordSpoolClosesThroughTheTemplateAfterAStabilizationFailure() throws Throwable {
    Path spool = Files.createDirectory(temporary.resolve("spool"));
    Path target = temporary.resolve("failed.mem");
    var spoolAtFailure = new AtomicBoolean();
    PackSource failing =
        new PackSource.GeneratedEntry(
            "f",
            1,
            () -> {
              spoolAtFailure.set(holdsSpool(spool));
              return new ReadableByteChannel() {
                public int read(ByteBuffer bytes) throws IOException {
                  throw new IOException("read");
                }

                public boolean isOpen() {
                  return true;
                }

                public void close() {
                  // Nothing to release.
                }
              };
            });
    var cleanup = new ArrayList<Boolean>();
    var control =
        new OperationControl(
            snapshot -> {
              if (snapshot.phase() == OperationPhase.CLEANUP) cleanup.add(holdsSpool(spool));
            },
            () -> false);
    // Output order is a, b, f: a and b spill the spool to a file before f's read fails.
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                withSpoolDirectory(
                    spool,
                    () ->
                        PackPipeline.pack(
                            request(
                                target,
                                options(0, false, Map.of()),
                                ResourceLimits.standard(),
                                1,
                                failing,
                                source("b", 40_000),
                                source("a", 40_000)),
                            control,
                            MemoryEncoder.perPartRecords(Long.MAX_VALUE))));
    assertEquals(FailureKind.SOURCE, failure.kind());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertEquals(OptionalLong.of(2), failure.primaryFailure().ordinal());
    assertEquals(List.of(), failure.secondaryFailures());
    assertTrue(spoolAtFailure.get(), "the spool had not spilled when the failing source opened");
    assertEquals(List.of(false, false), cleanup, "the spool was open when CLEANUP began");
    assertFalse(holdsSpool(spool));
    assertFalse(Files.exists(target));
  }

  /** Asserts a rejection before any source opens or output exists, returning the failure. */
  private ArchiveException assertRejected(
      MemoryEncoder encoder, PackRequest request, FailureKind kind, String identifier) {
    var failure = assertRejected(encoder, request);
    assertEquals(Optional.of(identifier), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(kind, failure.kind(), identifier);
    return failure;
  }

  /** Asserts any rejection before a source opens or an output exists. */
  private ArchiveException assertRejected(MemoryEncoder encoder, PackRequest request) {
    int before = opens.get();
    var failure =
        assertThrows(
            ArchiveException.class,
            () -> PackPipeline.pack(request, OperationControl.standard(), encoder));
    assertEquals(before, opens.get(), "a source was opened");
    assertFalse(Files.exists(request.destination()), "an output was left");
    return failure;
  }

  /**
   * Asserts an INTERNAL {@code operation.internal-failure} primary at the given phase, with no
   * ordinal, no Secondary Failures and no artifacts.
   */
  private static void assertInternal(ArchiveException failure, OperationPhase phase) {
    var primary = failure.primaryFailure();
    assertEquals(FailureKind.INTERNAL, primary.kind());
    assertEquals(Optional.of("operation.internal-failure"), primary.diagnosticIdentifier());
    assertEquals(phase, primary.phase());
    assertEquals(OptionalLong.empty(), primary.ordinal());
    assertEquals(List.of(), failure.secondaryFailures());
    assertEquals(List.of(), failure.artifacts());
  }

  /**
   * Runs {@code body} with {@code java.io.tmpdir} pointed at {@code scratch}, the directory where
   * the pipeline opens its stable spool, so a test can watch the spool's spill file. Test classes
   * run one at a time in one JVM, so nothing else reads the property meanwhile.
   */
  private static <T> T withSpoolDirectory(Path scratch, ThrowingSupplier<T> body) throws Throwable {
    String previous = System.getProperty("java.io.tmpdir");
    System.setProperty("java.io.tmpdir", scratch.toString());
    try {
      return body.get();
    } finally {
      System.setProperty("java.io.tmpdir", previous);
    }
  }

  /** Returns whether the spool directory holds a spill file. */
  private static boolean holdsSpool(Path spool) {
    try (var files = Files.list(spool)) {
      return files.findAny().isPresent();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /**
   * One Progress Snapshot with what the file system held when it was delivered.
   *
   * @param spool whether the stable spool's spill file existed
   * @param published whether the first part was visible at its destination
   */
  private record SpoolObservation(ProgressSnapshot snapshot, boolean spool, boolean published) {}

  /** One pack Progress Snapshot. */
  private static ProgressSnapshot snapshot(
      OperationPhase phase, ProgressMetric metric, long completed, OptionalLong total) {
    return new ProgressSnapshot(Operation.PACK, phase, metric, completed, total);
  }

  /** Builds a request; the family and encoding are ignored by the memory adapter. */
  private static PackRequest request(
      Path target,
      PackOptions options,
      ResourceLimits limits,
      long workers,
      PackSource... sources) {
    return new PackRequest(
        target,
        ArchiveFamily.TES3_BSA,
        ArchiveEncoding.tes3(),
        Optional.empty(),
        List.of(sources),
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        limits,
        new WorkerSelection.UpTo(workers),
        options,
        Optional.empty());
  }

  /** Options with an explicit split target (0 never splits), sharing, and entry overrides. */
  private static PackOptions options(
      long splitTarget,
      boolean sharing,
      Map<NormalizedNameIdentity, PackOptions.Compression> entryCompression) {
    return new PackOptions(
        List.of(),
        PackOptions.Compression.FAMILY_DEFAULT,
        sharing,
        new PackOptions.Splitting.UpToBytes(splitTarget),
        FlagSelection.AUTOMATIC,
        FlagSelection.AUTOMATIC,
        entryCompression);
  }

  /** Standard limits with only the decoded-byte ceiling lowered. */
  private static ResourceLimits limits(long decodedBytes) {
    var standard = ResourceLimits.standard();
    return new ResourceLimits(
        standard.maxEntries(),
        standard.maxMetadataBytes(),
        decodedBytes,
        standard.maxScratchBytes(),
        standard.maxOutputs(),
        standard.maxDiagnostics(),
        standard.maxSecondaryFailures());
  }

  /** A counted zero-filled source of the declared length. */
  private PackSource source(String name, long length) {
    return source(name, new byte[(int) length]);
  }

  /** A counted source with fixed bytes, which also traces {@code "open <name>"} when opened. */
  private PackSource source(String name, byte[] bytes) {
    return new PackSource.GeneratedEntry(
        name,
        bytes.length,
        () -> {
          opens.incrementAndGet();
          trace.add("open " + name);
          return Channels.newChannel(new ByteArrayInputStream(bytes));
        });
  }

  /** A one-byte source whose read fails, and whose close also fails when requested. */
  private static PackSource failing(String name, boolean closeFails) {
    return new PackSource.GeneratedEntry(
        name,
        1,
        () ->
            new ReadableByteChannel() {
              public int read(ByteBuffer bytes) throws IOException {
                throw new IOException("read");
              }

              public boolean isOpen() {
                return true;
              }

              public void close() throws IOException {
                if (closeFails) throw new IOException("close");
              }
            });
  }

  /** One decoded MemoryEncoder table record and its payload. */
  private record Record(String name, long offset, byte[] payload) {}

  /**
   * The {@link Admitted.Emission} case a {@link MemoryEncoder} declares, one per strategy the
   * pipeline runs; each case fixes the Content Sharing scope and basis and the split payload cost.
   */
  private enum Strategy {
    /** Streamed emission, per-part sharing of raw bytes, decoded split cost (TES3). */
    STREAMED,
    /**
     * Stabilized emission, per-part sharing of complete stored records, stored split cost
     * (versioned BSA).
     */
    PER_PART_RECORDS,
    /** Stabilized emission, archive-set sharing of raw owners, first-owner split cost (BA2). */
    SET_OWNERS
  }

  /**
   * A test-only family: "MEM1", a u32 count, then per entry a u32 payload-relative offset, u32
   * size, and an 8-byte NUL-padded name; payloads follow. Output order reverses Logical Plan Order,
   * so tests can tell the two apart, and the name "bad" breaks its only per-entry rule. The name
   * "boom" makes {@code plan} throw an {@link AssertionError}, standing in for a programmer error.
   * Under streamed emission, a name ending in {@code .z} is planned zlib-encoded and one ending in
   * {@code .f} is planned with a one-byte frame, both of which streamed emission rejects.
   *
   * @param defaultSplitTarget the FamilyDefault split target
   * @param refuse whether admission fails with UNSUPPORTED {@code memory.admit-refused}
   * @param events the adapter calls made, in order
   * @param fixedCost the fixed per-entry split cost
   * @param strategy which Emission case the adapter declares
   * @param readback whether every staged part is read back; no reader recognizes MEM1, so a
   *     readback always fails
   * @param maxRecord the largest stabilized stored record {@code checkStored} accepts; a larger one
   *     fails with POLICY {@code memory.record-too-large}
   */
  private record MemoryEncoder(
      long defaultSplitTarget,
      boolean refuse,
      List<String> events,
      long fixedCost,
      Strategy strategy,
      boolean readback,
      long maxRecord)
      implements FamilyAdapter<String> {
    private static final int HEADER = 8;
    private static final int RECORD = 16;

    MemoryEncoder(long defaultSplitTarget) {
      this(defaultSplitTarget, false, Collections.synchronizedList(new ArrayList<>()));
    }

    MemoryEncoder(long defaultSplitTarget, boolean refuse, List<String> events) {
      this(defaultSplitTarget, refuse, events, 0);
    }

    MemoryEncoder(long defaultSplitTarget, boolean refuse, List<String> events, long fixedCost) {
      this(defaultSplitTarget, refuse, events, fixedCost, Strategy.STREAMED, false, Long.MAX_VALUE);
    }

    /** A set-wide stabilized encoder, optionally reading every part back. */
    static MemoryEncoder setWide(boolean readback) {
      return new MemoryEncoder(
          0,
          false,
          Collections.synchronizedList(new ArrayList<>()),
          0,
          Strategy.SET_OWNERS,
          readback,
          Long.MAX_VALUE);
    }

    /**
     * A per-part stored-record encoder, the versioned BSA settings. Each record is framed by one
     * byte holding its name's length, so equal payloads under names of different lengths are
     * distinct stored records; a name ending in {@code .z} is zlib-encoded, so its stored size is
     * known only once stabilized.
     *
     * @param maxRecord the largest stored record, frame included, that {@code checkStored} accepts
     */
    static MemoryEncoder perPartRecords(long maxRecord) {
      return new MemoryEncoder(
          0,
          false,
          Collections.synchronizedList(new ArrayList<>()),
          0,
          Strategy.PER_PART_RECORDS,
          false,
          maxRecord);
    }

    @Override
    public Admitted<String> admit(PackRequest request, IoContext context) throws ArchiveException {
      events.add("admit");
      if (refuse) throw context.failure(FailureKind.UNSUPPORTED, "memory.admit-refused", null);
      return new Admitted<>() {
        @Override
        public Charset nameCharset() {
          return StandardCharsets.US_ASCII;
        }

        @Override
        public String diagnosticPrefix() {
          return "memory";
        }

        @Override
        public long defaultSplitTarget() {
          return defaultSplitTarget;
        }

        @Override
        public Emission<String> emission() {
          return switch (strategy) {
            case STREAMED -> new Emission.Streamed<>();
            case PER_PART_RECORDS -> new Emission.PerPartRecords<>();
            case SET_OWNERS -> new Emission.SetOwners<>(Optional.empty());
          };
        }

        @Override
        public SplitCost<String> splitCost() {
          return new SplitCost<>(fixedCost, name -> 0);
        }

        @Override
        public void checkStored(Planned<String> entry, IoContext processing)
            throws ArchiveException {
          // Stands in for a wire field too narrow for the record's exact stabilized size.
          if (entry.storedSize() > maxRecord)
            throw processing.failure(FailureKind.POLICY, "memory.record-too-large", null);
        }

        @Override
        public Readback readback(List<Planned<String>> part, Layout layout) {
          return readback
              ? new Readback("memory.noncanonical-staged-output", 0, 0, 1, Set.of(), 0, 0)
              : null;
        }

        @Override
        public List<Planned<String>> plan(List<PackSources.Entry> sources, IoContext context)
            throws ArchiveException {
          events.add("plan");
          var planned = new ArrayList<Planned<String>>();
          for (var source : sources) {
            if (source.displayName().equals("bad"))
              throw context.failure(FailureKind.POLICY, "memory.bad-name", null);
            // Stands in for a programmer error inside an adapter, which no family rule describes.
            if (source.displayName().equals("boom")) throw new AssertionError("plan bug");
            String name = source.displayName();
            if (strategy == Strategy.PER_PART_RECORDS)
              planned.add(
                  new Planned<>(
                      source,
                      name,
                      name.endsWith(".z") ? PayloadCodec.ZLIB : PayloadCodec.STORED,
                      new byte[] {(byte) name.length()}));
            // A streamed plan of a compressed or framed entry stands in for an adapter bug: streamed
            // emission carries stored, unframed payloads only.
            else if (strategy == Strategy.STREAMED && name.endsWith(".z"))
              planned.add(new Planned<>(source, name, PayloadCodec.ZLIB, new byte[0]));
            else if (strategy == Strategy.STREAMED && name.endsWith(".f"))
              planned.add(new Planned<>(source, name, PayloadCodec.STORED, new byte[] {1}));
            else planned.add(new Planned<>(source, name, PayloadCodec.STORED, new byte[0]));
          }
          Collections.reverse(planned);
          return planned;
        }

        @Override
        public Layout layout(List<Planned<String>> part, IoContext context) {
          long start = HEADER + (long) RECORD * part.size();
          return new Layout(start, start);
        }

        @Override
        public List<Patch> tables(List<Placed<String>> part, Layout layout) {
          var table =
              ByteBuffer.allocate(HEADER + RECORD * part.size()).order(ByteOrder.LITTLE_ENDIAN);
          table.put("MEM1".getBytes(StandardCharsets.US_ASCII)).putInt(part.size());
          for (var entry : part)
            table
                .putInt((int) (entry.offset() - layout.payloadStart()))
                .putInt((int) entry.storedSize())
                .put(Arrays.copyOf(entry.planned().key().getBytes(StandardCharsets.US_ASCII), 8));
          return List.of(new Patch(0, table.array()));
        }
      };
    }

    /** Decodes a published part back into its records. */
    static List<Record> read(Path path) throws IOException {
      var bytes = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
      byte[] magic = new byte[4];
      bytes.get(magic);
      assertEquals("MEM1", new String(magic, StandardCharsets.US_ASCII));
      int count = bytes.getInt();
      long start = HEADER + (long) RECORD * count;
      var records = new ArrayList<Record>();
      for (int index = 0; index < count; index++) {
        int offset = bytes.getInt();
        byte[] payload = new byte[bytes.getInt()];
        byte[] name = new byte[8];
        bytes.get(name);
        bytes.slice((int) start + offset, payload.length).get(payload);
        records.add(
            new Record(
                new String(name, StandardCharsets.US_ASCII).replace("\0", ""), offset, payload));
      }
      return records;
    }
  }
}
