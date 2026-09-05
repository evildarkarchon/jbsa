package io.github.evildarkarchon.jbsa;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalLong;

/** Bounded selector recognition; no index validation or payload dispatch occurs here. */
final class Detection {
  private Detection() {}

  /** Retains all observed selector bytes, including incomplete and unknown selector components. */
  static ArchiveDetection recognize(byte[] bytes) {
    int[][] magics = {{0, 1, 0, 0}, {66, 83, 65, 0}, {66, 84, 68, 88}};
    int magic = -1;
    for (int candidate = 0; candidate < magics.length; candidate++) {
      boolean matches = bytes.length > 0;
      for (int i = 0; i < Math.min(4, bytes.length); i++) {
        matches &= Byte.toUnsignedInt(bytes[i]) == magics[candidate][i];
      }
      if (matches) {
        magic = candidate;
        break;
      }
    }
    DetectionStatus status = DetectionStatus.INDETERMINATE;
    ArchiveFamily family = null;
    WireVersion version = null;
    Ba2Subtype subtype = null;
    OptionalLong method = OptionalLong.empty();
    if (magic == -1) {
      status = DetectionStatus.UNRECOGNIZED;
    } else if (bytes.length >= 4 && magic == 0) {
      family = ArchiveFamily.TES3_BSA;
    } else if (bytes.length >= 8) {
      ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      long wire = Integer.toUnsignedLong(data.getInt(4));
      version = new WireVersion(wire);
      if (magic == 1) {
        family =
            switch ((int) wire) {
              case 0x67 -> ArchiveFamily.TES4_BSA;
              case 0x68 -> ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA;
              case 0x69 -> ArchiveFamily.SSE_BSA;
              default -> null;
            };
        status = DetectionStatus.UNSUPPORTED_VARIANT;
      } else if (bytes.length >= 12) {
        subtype = new Ba2Subtype(new String(bytes, 8, 4, StandardCharsets.ISO_8859_1));
        if (wire == 3 && bytes.length >= 36) {
          method = OptionalLong.of(Integer.toUnsignedLong(data.getInt(32)));
        }
        boolean general = subtype.equals(Ba2Subtype.GNRL);
        boolean dds = subtype.equals(Ba2Subtype.DX10);
        status = DetectionStatus.UNSUPPORTED_VARIANT;
        if (general || dds) {
          if (wire == 1 || wire == 7 || wire == 8) {
            family = general ? ArchiveFamily.FO4_GENERAL_BA2 : ArchiveFamily.FO4_DDS_BA2;
          } else if (wire == 2 || (wire == 3 && method.orElse(3) == 3)) {
            // The tuple identifies the family at 12 bytes. A present v3 method refines support.
            family =
                general ? ArchiveFamily.STARFIELD_GENERAL_BA2 : ArchiveFamily.STARFIELD_DDS_BA2;
          }
        }
      }
    }
    if (family != null) {
      status = DetectionStatus.SUPPORTED_FAMILY;
    }
    return new ArchiveDetection(
        status,
        new WireName(bytes),
        Optional.ofNullable(family),
        Optional.ofNullable(version),
        Optional.ofNullable(subtype),
        method);
  }
}
