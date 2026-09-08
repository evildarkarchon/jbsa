package io.github.evildarkarchon.jbsa.benchmarks;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the benchmark adapter's production delegation and external-manifest authority. */
final class DdsArchiveAccessProviderTest {
  @TempDir Path directory;

  /** Reaches the public metadata/content seam and rejects incorrect external payload evidence. */
  @Test
  void validatesManifestBeforeDelegatingReads() throws Exception {
    byte[] dds = new byte[136];
    ByteBuffer header = ByteBuffer.wrap(dds).order(ByteOrder.LITTLE_ENDIAN);
    header
        .putInt(0, 0x20534444)
        .putInt(4, 124)
        .putInt(8, 0xa1007)
        .putInt(12, 1)
        .putInt(16, 1)
        .putInt(20, 8)
        .putInt(24, 1)
        .putInt(28, 1)
        .putInt(76, 32)
        .putInt(80, 4)
        .putInt(84, 0x31545844)
        .putInt(108, 0x1000);
    Path source = Files.createDirectories(directory.resolve("source/textures"));
    Files.write(source.resolve("a.dds"), dds);
    Path archive = directory.resolve("a.ba2");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                archive,
                ArchiveFamily.FO4_DDS_BA2,
                new ArchiveEncoding(
                    Optional.of(new WireVersion(1)),
                    Optional.of(Ba2Subtype.DX10),
                    OptionalLong.empty()),
                List.of(new PackSource.DetectedPath(source.getParent())),
                Optional.of(DdsTarget.PC)),
            OperationControl.standard());
    Path manifest = directory.resolve("manifest.json");
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dds));
    String json =
        "{\"files\":[{\"path\":\"textures/a.dds\",\"length\":136,\"sha256\":\"" + sha + "\"}]}";
    Files.writeString(manifest, json);
    var provider = new DdsArchiveAccessProvider();
    assertTrue(
        ServiceLoader.load(ArchiveAccessProvider.class).stream()
            .anyMatch(item -> item.get().identity().equals(provider.identity())));
    try (var opened = provider.open(archive, manifest)) {
      assertEquals(
          List.of(new ArchiveAccessProvider.Entry("textures/a.dds", 136)), opened.entries());
      assertInstanceOf(EntryMetadata.class, opened.lookup("textures/a.dds"));
      try (var input = opened.read("textures/a.dds")) {
        assertArrayEquals(dds, input.readAllBytes());
      }
      assertThrows(IOException.class, () -> opened.lookup("missing.dds"));
    }
    Files.writeString(manifest, json.replace(sha, "0".repeat(64)));
    assertThrows(IOException.class, () -> provider.open(archive, manifest));
  }

  /** Duplicate keys and trailing JSON cannot create ambiguous external length/hash authority. */
  @Test
  void rejectsAmbiguousManifestJson() throws Exception {
    Path manifest = directory.resolve("manifest.json");
    for (String invalid :
        List.of("{\"files\":[],\"files\":[]}", "{\"files\":[]}{}", "{\"n\":01}")) {
      Files.writeString(manifest, invalid);
      assertThrows(IOException.class, () -> BenchmarkManifest.read(manifest));
    }
    Files.write(manifest, new byte[] {'{', '"', (byte) 0xc3, '"', ':', '1', '}'});
    assertThrows(IOException.class, () -> BenchmarkManifest.read(manifest));
  }
}
