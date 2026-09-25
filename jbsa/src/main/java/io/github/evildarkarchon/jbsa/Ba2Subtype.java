package io.github.evildarkarchon.jbsa;

import java.util.Objects;

/**
 * Four BA2 selector octets represented losslessly as ISO-8859-1 characters, including unknown tags.
 */
public record Ba2Subtype(String value) {
  public static final Ba2Subtype GNRL = new Ba2Subtype("GNRL");
  public static final Ba2Subtype DX10 = new Ba2Subtype("DX10");

  /**
   * Rejects text that is not exactly four octets; unsupported four-octet tags remain representable.
   */
  public Ba2Subtype {
    Objects.requireNonNull(value, "value");
    if (value.length() != 4 || value.chars().anyMatch(character -> character > 255)) {
      throw new IllegalArgumentException("BA2 subtype must contain exactly four octets");
    }
  }
}
