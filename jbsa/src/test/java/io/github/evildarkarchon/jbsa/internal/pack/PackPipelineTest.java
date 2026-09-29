package io.github.evildarkarchon.jbsa.internal.pack;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import io.github.evildarkarchon.jbsa.internal.io.PublicationTransaction;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link PackPipeline} directly through the test-only {@link MemoryEncoder} adapter, so the
 * pipeline's own lifecycle is observable without any real Archive Family's wire rules.
 */
class PackPipelineTest {
  @TempDir Path temporary;

  /** Counts every payload factory invocation made by the current test's sources. */
  private final AtomicInteger opens = new AtomicInteger();

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

  /** The three Splitting arms, driven by the adapter's declarative split cost. */
  @Test
  void splitArmsFormWholeEntryParts() throws Exception {
    List<Admitted.Planned<String>> planned =
        List.of(planned("a", 3), planned("b", 3), planned("c", 1));
    assertEquals(List.of(3), sizes(planned, 0, new PackOptions.Splitting.FamilyDefault()));
    assertEquals(List.of(1, 2), sizes(planned, 4, new PackOptions.Splitting.FamilyDefault()));
    assertEquals(List.of(3), sizes(planned, 4, new PackOptions.Splitting.UpToBytes(0)));
    assertEquals(List.of(2, 1), sizes(planned, 0, new PackOptions.Splitting.UpToBytes(6)));
    assertEquals(List.of(1, 1, 1), sizes(planned, 0, new PackOptions.Splitting.LegacyPerEntry()));

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

  /**
   * D9: a stabilized plan's preflight counts exactly the parts it is certain to publish. Predicted
   * stored sizes split exactly; with an unknown encoded size only "never splits" and "no two
   * entries fit one part" are certain, and any other plan is certain only of its first part.
   */
  @Test
  void knownPartCountCoversOnlyCertainParts() throws Exception {
    var context = IoContext.of(Path.of("x").toAbsolutePath(), Operation.PACK);
    var stored = List.of(planned("a", 3), planned("b", 3), planned("c", 1));
    var cheap = new MemoryEncoder(4, false, new ArrayList<>(), 0).admit(null, context);
    assertEquals(
        2,
        PackPipeline.knownPartCount(
            stored, cheap, new PackOptions.Splitting.FamilyDefault(), context));

    var encoded = List.of(encoded("a", 3), encoded("b", 3), encoded("c", 1));
    assertEquals(
        1,
        PackPipeline.knownPartCount(
            encoded, cheap, new PackOptions.Splitting.UpToBytes(0), context));
    // A zero minimum cost lets entries share a part, so only the first part is certain.
    assertEquals(
        1,
        PackPipeline.knownPartCount(
            encoded, cheap, new PackOptions.Splitting.LegacyPerEntry(), context));
    var costly = new MemoryEncoder(0, false, new ArrayList<>(), 200).admit(null, context);
    assertEquals(
        3,
        PackPipeline.knownPartCount(
            encoded, costly, new PackOptions.Splitting.LegacyPerEntry(), context));
    assertEquals(
        3,
        PackPipeline.knownPartCount(
            encoded, costly, new PackOptions.Splitting.UpToBytes(200), context));
    assertEquals(
        1,
        PackPipeline.knownPartCount(
            encoded, costly, new PackOptions.Splitting.UpToBytes(201), context));
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

  /** Returns part sizes for a split under the given family default target. */
  private static List<Integer> sizes(
      List<Admitted.Planned<String>> planned, long familyDefault, PackOptions.Splitting splitting)
      throws ArchiveException {
    var admitted =
        new MemoryEncoder(familyDefault)
            .admit(null, IoContext.of(Path.of("x").toAbsolutePath(), Operation.PACK));
    return PackPipeline.split(planned, admitted, splitting).stream().map(List::size).toList();
  }

  /** A planned stored entry of a given declared size, whose payload is never read. */
  private static Admitted.Planned<String> planned(String name, long size) {
    var entry =
        new PackSources.Entry(
            name,
            name,
            name.getBytes(StandardCharsets.US_ASCII),
            0,
            size,
            reader -> {
              throw new AssertionError("Splitting must not read payloads");
            });
    return new Admitted.Planned<>(entry, name, Codec.STORED, new byte[0]);
  }

  /** A planned zlib entry, whose stored size stays unknown until stabilization. */
  private static Admitted.Planned<String> encoded(String name, long size) {
    var stored = planned(name, size);
    return new Admitted.Planned<>(stored.source(), name, Codec.ZLIB, new byte[0]);
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

  /** A counted source with fixed bytes. */
  private PackSource source(String name, byte[] bytes) {
    return new PackSource.GeneratedEntry(
        name,
        bytes.length,
        () -> {
          opens.incrementAndGet();
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
   * A test-only family: "MEM1", a u32 count, then per entry a u32 payload-relative offset, u32
   * size, and an 8-byte NUL-padded name; payloads follow. Output order reverses Logical Plan Order,
   * so tests can tell the two apart, and the name "bad" breaks its only per-entry rule.
   *
   * @param defaultSplitTarget the FamilyDefault split target
   * @param refuse whether admission fails with UNSUPPORTED {@code memory.admit-refused}
   * @param events the adapter calls made, in order
   * @param fixedCost the fixed per-entry split cost
   */
  private record MemoryEncoder(
      long defaultSplitTarget, boolean refuse, List<String> events, long fixedCost)
      implements FamilyAdapter<String> {
    private static final int HEADER = 8;
    private static final int RECORD = 16;

    MemoryEncoder(long defaultSplitTarget) {
      this(defaultSplitTarget, false, Collections.synchronizedList(new ArrayList<>()));
    }

    MemoryEncoder(long defaultSplitTarget, boolean refuse, List<String> events) {
      this(defaultSplitTarget, refuse, events, 0);
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
        public Emission emission() {
          return Emission.STREAMING;
        }

        @Override
        public SplitCost<String> splitCost() {
          return new SplitCost<>(fixedCost, name -> 0, PayloadCost.DECODED);
        }

        @Override
        public Sharing sharing() {
          return new Sharing(Sharing.Scope.PER_PART, Sharing.Basis.RAW);
        }

        @Override
        public List<Planned<String>> plan(List<PackSources.Entry> sources, IoContext context)
            throws ArchiveException {
          events.add("plan");
          var planned = new ArrayList<Planned<String>>();
          for (var source : sources) {
            if (source.displayName().equals("bad"))
              throw context.failure(FailureKind.POLICY, "memory.bad-name", null);
            planned.add(new Planned<>(source, source.displayName(), Codec.STORED, new byte[0]));
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
