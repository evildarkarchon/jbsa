package io.github.evildarkarchon.jbsa.benchmarks;

import io.github.evildarkarchon.jbsa.ArchiveFamily;
import io.github.evildarkarchon.jbsa.BethesdaArchives;
import io.github.evildarkarchon.jbsa.OpenArchive;
import io.github.evildarkarchon.jbsa.OpenOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Build-only JMH adapter for the qualified DDS public archive and entry-content capabilities. */
public final class DdsArchiveAccessProvider implements ArchiveAccessProvider {
  private static final String PROFILE_SHA256 =
      "b9515f305ba223111b790ad06c98580360ac85c40235258316aad2ba001a3fda";

  /** Returns this adapter's exact shipped profile token; it never impersonates another profile. */
  @Override
  public String identity() {
    return "zlib-jdk-p" + PROFILE_SHA256;
  }

  /**
   * Verifies manifest entries and every canonical byte outside timing, then owns one public
   * archive. The caller must close the returned trial capability. Setup failure closes it
   * immediately.
   */
  @Override
  public ArchiveAccess open(Path archive, Path manifest) throws IOException {
    try (InputStream profile =
        BethesdaArchives.class.getResourceAsStream("/META-INF/jbsa-codec-profile.json")) {
      if (profile == null || !hash(profile).equals(PROFILE_SHA256))
        throw new IOException("Benchmark provider differs from the packaged codec profile");
    }
    Map<?, ?> document = BenchmarkManifest.read(manifest);
    if (!(document.get("files") instanceof List<?> files) || files.isEmpty())
      throw new IOException("Missing external manifest entries");
    OpenArchive opened = BethesdaArchives.standard().open(archive, OpenOptions.standard());
    try {
      Map<String, Long> ordinals = new HashMap<>();
      for (long ordinal = 0; ordinal < opened.entryCount(); ordinal++) {
        var metadata = opened.entry(ordinal).metadata();
        if (metadata.family() != ArchiveFamily.FO4_DDS_BA2)
          throw new IOException("DDS benchmark received another archive family");
        if (ordinals.put(metadata.displayName().replace('\\', '/'), ordinal) != null)
          throw new IOException("Duplicate archive manifest name");
      }
      List<Entry> entries = new ArrayList<>();
      Map<String, Long> selected = new HashMap<>();
      for (Object item : files) {
        if (!(item instanceof Map<?, ?> file)
            || !(file.get("path") instanceof String path)
            || !(file.get("length") instanceof Long length)
            || length < 0
            || !(file.get("sha256") instanceof String expected)
            || !expected.matches("[a-f0-9]{64}"))
          throw new IOException("Invalid manifest entry fields");
        Long ordinal = ordinals.get(path);
        if (ordinal == null || selected.put(path, ordinal) != null)
          throw new IOException("Absent or duplicate manifest entry: " + path);
        var entry = opened.entry(ordinal);
        if (entry.metadata().decodedSize() != length)
          throw new IOException("Canonical DDS length differs from the external manifest");
        try (InputStream content = Channels.newInputStream(entry.openContent())) {
          if (!hash(content).equals(expected))
            throw new IOException("Manifest payload digest mismatch");
        }
        entries.add(new Entry(path, length));
      }
      if (selected.size() != ordinals.size())
        throw new IOException("Archive has entries outside the selected manifest");
      return new Access(opened, List.copyOf(entries), Map.copyOf(selected));
    } catch (IOException | RuntimeException failure) {
      try {
        opened.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  /**
   * Hashes verified trial inputs with bounded storage; no digest work enters measured operations.
   */
  private static String hash(InputStream input) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = new byte[65536];
      for (int count; (count = input.read(bytes)) != -1; ) digest.update(bytes, 0, count);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /** One thread-confined trial capability with immutable manifest-to-public-ordinal lookup. */
  private record Access(OpenArchive archive, List<Entry> entries, Map<String, Long> ordinals)
      implements ArchiveAccess {
    /** Performs the public ordinal lookup after the manifest's stable name-to-ordinal mapping. */
    @Override
    public Object lookup(String key) throws IOException {
      Long ordinal = ordinals.get(key);
      if (ordinal == null) throw new IOException("Unknown manifest key");
      return archive.entry(ordinal).metadata();
    }

    /** Opens only the public entry channel; the benchmark owns and closes the returned stream. */
    @Override
    public InputStream read(String key) throws IOException {
      Long ordinal = ordinals.get(key);
      if (ordinal == null) throw new IOException("Unknown manifest key");
      return Channels.newInputStream(archive.entry(ordinal).openContent());
    }

    /** Releases the underlying archive and all child capabilities at trial teardown. */
    @Override
    public void close() throws IOException {
      archive.close();
    }
  }
}
