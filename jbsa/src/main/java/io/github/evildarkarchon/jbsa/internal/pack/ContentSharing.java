package io.github.evildarkarchon.jbsa.internal.pack;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The pipeline's one Content Sharing index. A SHA-256 {@code size:hex} key only shortlists
 * candidates; a match is established solely by an exact bounded-window byte comparison, so digest
 * collisions can never alias different payloads. The family's scope decides how long one index
 * lives (one part or the whole set); its basis decides which bytes were digested.
 *
 * @param <T> the location of one stored copy, such as its absolute staged offset
 */
final class ContentSharing<T> {
  private final Map<String, List<T>> owners = new HashMap<>();

  /** Tests one shortlisted stored copy for exact byte equality. */
  @FunctionalInterface
  interface Match<T> {
    /** Returns whether the candidate's stored bytes equal the payload being placed. */
    boolean equal(T candidate) throws IOException;
  }

  /**
   * Builds the shortlist key. The discriminator separates records whose bytes may coincide while
   * meaning different things, such as a BSA stored record and a compressed one.
   */
  static String key(long size, byte[] digest, boolean discriminator) {
    return (discriminator ? "1:" : "0:") + size + ":" + HexFormat.of().formatHex(digest);
  }

  /** Obtains the mandatory JDK SHA-256 implementation used only to shortlist byte comparisons. */
  static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /**
   * Returns the first byte-identical earlier owner under this key, or null when none matches.
   * Candidates are tested in insertion order, so the earliest owner always wins.
   */
  T find(String key, Match<T> match) throws IOException {
    for (T candidate : owners.getOrDefault(key, List.of()))
      if (match.equal(candidate)) return candidate;
    return null;
  }

  /** Records a byte-distinct owner; callers add only payloads {@link #find} did not match. */
  void add(String key, T owner) {
    owners.computeIfAbsent(key, ignored -> new ArrayList<>()).add(owner);
  }
}
