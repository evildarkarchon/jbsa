package io.github.evildarkarchon.jbsa;

import java.util.List;

/** Published immutable compatibility bundles; selecting one activates its entire deviation set. */
public enum CompatibilityProfile {
  /** The initial BSArch 1.0 bundle, revision 1, owned by JBSA-COMPAT-002. */
  BSARCH_1_0_V1;

  /** Returns the exact registered profile identifier. */
  public String identifier() {
    return "bsarch-1.0/v1";
  }

  /** Returns the immutable published revision. */
  public long revision() {
    return 1L;
  }

  /** Returns the uppercase SHA-256 digest of the normative UTF-8/LF profile payload. */
  public String contentDigest() {
    return "9577D821C40982E7F988D311D5BA7CF55B0F098AF2C3F5FD5C5A531360DDE1C4";
  }

  /** Returns deviation identifiers in the canonical profile payload order. */
  public List<String> deviations() {
    return List.of(
        "BSARCH-1.0-V1-CLI-REPEATED-VALUE",
        "BSARCH-1.0-V1-CLI-FAMILY-PRIORITY",
        "BSARCH-1.0-V1-CLI-IGNORED-ARGUMENT",
        "BSARCH-1.0-V1-CLI-BOOLEAN-NO",
        "BSARCH-1.0-V1-CLI-SPLIT-PARSE",
        "BSARCH-1.0-V1-CLI-ZERO-FLAGS",
        "BSARCH-1.0-V1-CLI-UNUSABLE-SOURCE",
        "BSARCH-1.0-V1-CLI-STDOUT",
        "BSARCH-1.0-V1-CLI-INFO-ZERO",
        "BSARCH-1.0-V1-CLI-LEGACY-REPLACE",
        "BSARCH-1.0-V1-NAME-ACTIVE-ANSI",
        "BSARCH-1.0-V1-DDS-XBOX-NAME",
        "BSARCH-1.0-V1-SF3-ZLIB-FALLBACK");
  }
}
