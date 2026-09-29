package io.github.evildarkarchon.jbsa.internal.io;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

/**
 * Fresh-process jlibdeflate launch probe for the issue 52 native-loading and cross-process
 * repeatability evidence. It deliberately depends only on the JDK and the provider, so the parent
 * can observe the provider's own extraction, override and native-access behavior in isolation.
 */
public final class JlibdeflateLaunchProbe {
  private JlibdeflateLaunchProbe() {}

  /**
   * Encodes the named deterministic corpus once at the candidate level and prints its SHA-256 as
   * {@code sha256=<hex>}. Any provider loading failure propagates and makes the exit status
   * nonzero.
   *
   * @param arguments corpus name and decoded byte count
   */
  public static void main(String[] arguments) throws Exception {
    byte[] data = corpus(arguments[0], Integer.parseInt(arguments[1]));
    try (var compressor =
        new com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor(JlibdeflateZlib.LEVEL)) {
      byte[] stored = compressor.zlibCompress(data);
      System.out.println(
          "sha256="
              + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stored)));
    }
  }

  /**
   * Materializes one fixed-seed qualification corpus. The shapes span the archive payload classes
   * the zlib lanes care about: trivially repetitive, text-like, smooth binary geometry,
   * incompressible, and interleaved compressible/incompressible content.
   *
   * @throws IllegalArgumentException for an unknown corpus name
   */
  static byte[] corpus(String name, int size) {
    byte[] data = new byte[size];
    Random random = new Random(52);
    switch (name) {
      case "repeated-251" -> {
        for (int index = 0; index < size; index++) data[index] = (byte) (index % 251);
      }
      case "random-seed52" -> random.nextBytes(data);
      case "text-seed52" -> text(data, 0, size, random);
      case "float-mesh-seed52" -> {
        // Little-endian floats of a smooth surface plus small noise resemble vertex streams.
        for (int index = 0; index + 4 <= size; index += 4) {
          float value =
              (float) (Math.sin(index * 0.0007) * 512 + random.nextGaussian() * 0.01 + index % 3);
          int bits = Float.floatToRawIntBits(value);
          data[index] = (byte) bits;
          data[index + 1] = (byte) (bits >>> 8);
          data[index + 2] = (byte) (bits >>> 16);
          data[index + 3] = (byte) (bits >>> 24);
        }
      }
      case "mixed-seed52" -> {
        for (int offset = 0; offset < size; offset += 4096) {
          int end = Math.min(size, offset + 4096);
          if ((offset / 4096) % 2 == 0) text(data, offset, end, random);
          else for (int index = offset; index < end; index++) data[index] = (byte) random.nextInt();
        }
      }
      default -> throw new IllegalArgumentException("unknown corpus " + name);
    }
    return data;
  }

  /** Fills a region with space-separated words from a small fixed vocabulary. */
  private static void text(byte[] data, int start, int end, Random random) {
    String[] words = {
      "the", "archive", "texture", "mesh", "sound", "script", "record", "worldspace", "cell",
      "actor", "quest", "dialogue", "weapon", "armor", "static", "furniture", "light", "sky",
      "water", "navmesh", "0.5", "1024", "true", "false", "Data", "Meshes", "Textures",
      "Interface", "Scripts", "Strings", "Seq", "Vis"
    };
    int index = start;
    while (index < end) {
      String word = words[random.nextInt(words.length)];
      for (int character = 0; character < word.length() && index < end; character++)
        data[index++] = (byte) word.charAt(character);
      if (index < end) data[index++] = (byte) (random.nextInt(12) == 0 ? '\n' : ' ');
    }
  }
}
