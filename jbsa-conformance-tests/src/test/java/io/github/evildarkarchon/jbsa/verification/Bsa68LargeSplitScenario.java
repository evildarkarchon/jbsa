package io.github.evildarkarchon.jbsa.verification;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded-memory real4.25GiB split verification; bulk output is consumed then removed. */
public final class Bsa68LargeSplitScenario {
  private static final List<String> NAMES =
      List.of("data/a.bin", "data/b.bin", "data/c.bin", "data/z.bin");
  private static final List<Long> SIZES = List.of(805306368L, 805306368L, 805306368L, 2147483648L);
  private static final List<String> PREFIXES =
      List.of(
          "a96b03131b6a693eee9e3268c01e565a6c45ffac0f0c702b23f6949348a22662eaf102e5f73138a1ec4714df8b0523d364986a7ed53b9fc417bda8e567d4e6ae",
          "7b22a56974d68c0e150a3dd27499bf8607d365f4ead8b2471dba194016417ba940e76b0594f4b124fe3c4fcf0e9f7114f517a2a85b7bcc02abf6f79c0ce10af0",
          "8fd3bfefe7c9621879522f5487774949714d9c50c8770defd7ddca00388d69df53652fd36008721bf015b092a060cc803ab32b2b6ded207695e928c225cf51a6",
          "5977f89bd1dc07497bdd9ee6d004a6d16bdfe996403cf38cc45e8c7084198b4f81a1dd9754f596e9984982f9ff81419e361fa2da8556de8a284d7e769a1a85d6");

  private Bsa68LargeSplitScenario() {}

  /**
   * Checks original-recipe rejection and real valid splitting, then removes owned bulk artifacts.
   */
  public static Map<String, Object> observe(Path recipe) throws Exception {
    require(
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(recipe)))
            .equals("a4227e62a3e43f5c1c35042996da168d80273d83d9f64dd10423780de7b12c29"),
        "Wrong split recipe");
    AtomicInteger factories = new AtomicInteger();
    Path invalid = Path.of("invalid.bsa");
    List<PackSource> original = new ArrayList<>();
    for (var value :
        Map.of(
                "data/near.bin",
                2147483400L,
                "data/crossing.bin",
                64L,
                "data/oversized.bin",
                2147483648L)
            .entrySet()) {
      original.add(
          new PackSource.GeneratedEntry(
              value.getKey(),
              value.getValue(),
              () -> {
                factories.incrementAndGet();
                throw new IOException(
                    "Invalid stored record must be rejected before payload access");
              }));
    }
    String failure = "NONE";
    try {
      BethesdaArchives.standard().pack(request(invalid, original), OperationControl.standard());
    } catch (ArchiveException expected) {
      failure = expected.kind().name();
    }
    require(
        failure.equals("POLICY") && factories.get() == 0 && !Files.exists(invalid),
        "Original bit30-invalid recipe was not rejected in preflight");

    Path bulk = Files.createDirectory(Path.of("bulk"));
    Path scratch = Files.createDirectory(Path.of("split-scratch"));
    String previousScratch = System.getProperty("java.io.tmpdir");
    Map<String, String> sourceDigests = new LinkedHashMap<>();
    List<PackSource> sources = new ArrayList<>();
    for (int index = 0; index < NAMES.size(); index++) {
      String name = NAMES.get(index);
      long size = SIZES.get(index);
      sources.add(
          new PackSource.GeneratedEntry(
              name, size, () -> new CounterContent(name, size, sourceDigests)));
    }
    List<Long> counts = new ArrayList<>();
    List<Long> lengths = new ArrayList<>();
    long scratchFiles;
    int parts;
    try {
      // Isolate the pack spool so its lifetime and cleanup are asserted independently of the host.
      System.setProperty("java.io.tmpdir", scratch.toAbsolutePath().toString());
      var result =
          BethesdaArchives.standard()
              .pack(request(bulk.resolve("split.bsa"), sources), OperationControl.standard());
      parts = result.archiveParts().size();
      try (var files = Files.list(scratch)) {
        scratchFiles = files.count();
      }
      require(scratchFiles == 0, "Pack scratch survived completion");
      for (var part : result.archiveParts()) {
        try (OpenArchive archive =
            BethesdaArchives.standard().open(part.path(), OpenOptions.standard())) {
          counts.add(archive.entryCount());
          for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
            var entry = archive.entry(ordinal);
            String name = entry.metadata().displayName().replace('\\', '/');
            int index = NAMES.indexOf(name);
            require(index >= 0, "Unexpected split entry");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(1 << 20);
            byte[] prefix = new byte[64];
            long read = 0;
            try (EntryContent content = entry.openContent()) {
              while (content.read(buffer) != -1) {
                buffer.flip();
                if (read < prefix.length) {
                  int amount = (int) Math.min(prefix.length - read, buffer.remaining());
                  buffer.duplicate().get(prefix, (int) read, amount);
                }
                read += buffer.remaining();
                digest.update(buffer);
                buffer.clear();
              }
            }
            require(read == SIZES.get(index), "Split decoded size mismatch");
            require(
                HexFormat.of().formatHex(prefix).equals(PREFIXES.get(index)),
                "Independent split recipe prefix mismatch");
            require(
                HexFormat.of().formatHex(digest.digest()).equals(sourceDigests.get(name)),
                "Full source/decoded payload digest mismatch");
            lengths.add(read);
          }
        }
      }
      require(
          parts == 3 && counts.equals(List.of(2L, 1L, 1L)) && lengths.equals(SIZES),
          "Default split or oversized entry grouping changed");
    } finally {
      System.setProperty("java.io.tmpdir", previousScratch);
      // The isolated directory contains only this scenario's bulk artifacts; remove it on all
      // exits.
      removeOwnedTree(bulk);
      removeOwnedTree(scratch);
    }
    return Map.of(
        "original_recipe_failure",
        failure,
        "original_factory_calls",
        factories.get(),
        "logical_bytes",
        lengths.stream().mapToLong(Long::longValue).sum(),
        "part_count",
        parts,
        "part_entry_counts",
        counts,
        "entry_sizes",
        lengths,
        "payload_digests_match",
        true,
        "scratch_files_after_pack",
        scratchFiles,
        "bulk_files_after_cleanup",
        Files.exists(bulk) ? 1 : 0);
  }

  /** Builds a default-splitting stored request with bounded sources and no sharing. */
  private static PackRequest request(Path output, List<PackSource> sources) {
    return new PackRequest(
        output,
        ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA,
        new ArchiveEncoding(
            Optional.of(new WireVersion(104)), Optional.empty(), OptionalLong.empty()),
        Optional.empty(),
        sources,
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        ResourceLimits.standard(),
        new WorkerSelection.UpTo(1),
        new PackOptions(
            List.of(),
            PackOptions.Compression.STORED,
            false,
            new PackOptions.Splitting.FamilyDefault(),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC),
        Optional.empty());
  }

  /** Deletes only an isolated scenario-owned subtree, without following directory links. */
  private static void removeOwnedTree(Path directory) throws IOException {
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  /**
   * Generates SHA256(seed||u64le(counter)) blocks with fixed32-byte state and an aggregate digest.
   */
  private static final class CounterContent implements ReadableByteChannel {
    private final String name;
    private final byte[] seed;
    private final long size;
    private final Map<String, String> completed;
    private final MessageDigest blockDigest;
    private final MessageDigest aggregate;
    private final byte[] block = new byte[32];
    private final byte[] counterBytes = new byte[8];
    private long position;
    private long counter;
    private int blockPosition = 32;
    private boolean open = true;
    private boolean recorded;

    /**
     * Creates fresh repeatable source state; completion records the exact generated source digest.
     */
    CounterContent(String name, long size, Map<String, String> completed) throws IOException {
      this.name = name;
      this.seed = ("jbsa-split-boundaries-v1/" + name).getBytes(StandardCharsets.UTF_8);
      this.size = size;
      this.completed = completed;
      try {
        blockDigest = MessageDigest.getInstance("SHA-256");
        aggregate = MessageDigest.getInstance("SHA-256");
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new IOException("Required JDK SHA256 unavailable", impossible);
      }
    }

    /**
     * Fills a bounded caller buffer and records a digest only after the complete declared stream.
     */
    @Override
    public int read(ByteBuffer destination) throws IOException {
      if (!open) throw new java.nio.channels.ClosedChannelException();
      if (!destination.hasRemaining()) return 0;
      if (position == size) {
        if (!recorded) {
          completed.put(name, HexFormat.of().formatHex(aggregate.digest()));
          recorded = true;
        }
        return -1;
      }
      int start = destination.position();
      while (destination.hasRemaining() && position < size) {
        if (blockPosition == block.length) {
          for (int index = 0; index < 8; index++)
            counterBytes[index] = (byte) (counter >>> (8 * index));
          counter++;
          blockDigest.update(seed);
          blockDigest.update(counterBytes);
          try {
            blockDigest.digest(block, 0, block.length);
          } catch (java.security.DigestException impossible) {
            throw new IOException(impossible);
          }
          blockPosition = 0;
        }
        int amount =
            (int)
                Math.min(
                    size - position,
                    Math.min(destination.remaining(), block.length - blockPosition));
        destination.put(block, blockPosition, amount);
        aggregate.update(block, blockPosition, amount);
        blockPosition += amount;
        position += amount;
      }
      return destination.position() - start;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    /**
     * Releases logical ownership; a partial stream never records a misleading full-payload digest.
     */
    @Override
    public void close() {
      open = false;
    }
  }
}
