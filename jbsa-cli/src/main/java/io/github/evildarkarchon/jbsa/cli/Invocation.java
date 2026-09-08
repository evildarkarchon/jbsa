package io.github.evildarkarchon.jbsa.cli;

import io.github.evildarkarchon.jbsa.ArchiveFamily;
import io.github.evildarkarchon.jbsa.CompatibilityProfile;
import io.github.evildarkarchon.jbsa.FlagSelection;
import io.github.evildarkarchon.jbsa.PackOptions;
import io.github.evildarkarchon.jbsa.PackSource;
import io.github.evildarkarchon.jbsa.TargetPolicy;
import io.github.evildarkarchon.jbsa.WorkerSelection;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Parsed presentation choices; discovery and archive semantics belong exclusively to the library.
 */
record Invocation(
    String operation,
    Path archive,
    Path destination,
    List<PackSource> sources,
    Optional<CompatibilityProfile> profile,
    TargetPolicy targetPolicy,
    WorkerSelection workers,
    PackOptions packOptions,
    ArchiveFamily family,
    boolean list,
    boolean dump,
    boolean noProgress) {

  /** Parses implemented archive commands, rejecting invalid syntax before constructing paths. */
  static Invocation parse(String[] args) {
    if (args.length == 0) {
      return administrative("help");
    }
    String first = lower(args[0]);
    if (first.equals("--help") || first.equals("--version")) {
      require(args.length == 1, "Administrative commands take no operands");
      return administrative(first.substring(2));
    }
    int index = 0;
    Optional<CompatibilityProfile> profile = Optional.empty();
    if (first.startsWith("--compatibility-profile=")) {
      require(first.equals("--compatibility-profile=bsarch-1.0/v1"), "Unsupported profile");
      profile = Optional.of(CompatibilityProfile.BSARCH_1_0_V1);
      index++;
    }
    require(index < args.length && !args[index].startsWith("-"), "Missing command or archive");
    String operation = lower(args[index]);
    String archive;
    String destination = null;
    String[] sourceNames = new String[0];
    if (operation.equals("pack")) {
      require(index + 2 < args.length, "Pack requires sources and archive");
      String operand = args[++index];
      archive = args[++index];
      require(!operand.startsWith("-") && !archive.startsWith("-"), "Operands precede switches");
      sourceNames = operand.split("\\+", -1);
      // Validate every component together: an empty one must not turn into the working directory.
      require(Arrays.stream(sourceNames).noneMatch(String::isEmpty), "Empty pack source component");
      index++;
    } else if (operation.equals("unpack")) {
      require(++index < args.length && !args[index].startsWith("-"), "Unpack requires an archive");
      archive = args[index++];
      if (index < args.length && !args[index].startsWith("-")) {
        destination = args[index++];
      }
    } else {
      operation = "inspect";
      archive = args[index++];
    }
    boolean pack = operation.equals("pack");
    boolean mutation = pack || operation.equals("unpack");
    ArchiveFamily family = null;
    PackOptions.Compression compression = PackOptions.Compression.STORED;
    FlagSelection archiveFlags = FlagSelection.AUTOMATIC;
    FlagSelection fileFlags = FlagSelection.AUTOMATIC;
    boolean list = false;
    boolean dump = false;
    boolean noProgress = false;
    boolean sharing = true;
    TargetPolicy target = profile.isPresent() ? TargetPolicy.REPLACE : TargetPolicy.FAIL;
    WorkerSelection workers = WorkerSelection.AUTOMATIC;
    PackOptions.Splitting splitting = new PackOptions.Splitting.FamilyDefault();
    List<String> masks = List.of();
    Set<String> seen = new HashSet<>();
    for (; index < args.length; index++) {
      String original = args[index];
      String option = lower(original);
      String key = option.split(":", 2)[0];
      if (!seen.add(key)) {
        if (profile.isPresent()
            && Set.of("-share", "-mt", "-split", "-f", "-af", "-ff", "-z").contains(key)) {
          continue;
        }
        require(false, "Duplicate switch: " + key);
      }
      switch (key) {
        case "-tes3", "-tes4", "-fo4", "-fo4dds" -> {
          require(
              pack && option.equals(key) && (family == null || profile.isPresent()),
              "Inapplicable or duplicate family");
          // The explicit profile uses fixed family priority, independent of switch order.
          ArchiveFamily selected =
              switch (key) {
                case "-tes3" -> ArchiveFamily.TES3_BSA;
                case "-tes4" -> ArchiveFamily.TES4_BSA;
                case "-fo4dds" -> ArchiveFamily.FO4_DDS_BA2;
                default -> ArchiveFamily.FO4_GENERAL_BA2;
              };
          if (family == null
              || selected == ArchiveFamily.TES3_BSA
              || (selected == ArchiveFamily.TES4_BSA && family != ArchiveFamily.TES3_BSA)
              || (selected == ArchiveFamily.FO4_GENERAL_BA2 && family == ArchiveFamily.FO4_DDS_BA2))
            family = selected;
        }
        case "-z" -> {
          require(pack && (option.equals("-z") || option.equals("-z:zlib")), "Unsupported codec");
          compression = PackOptions.Compression.ZLIB;
        }
        case "-af", "-ff" -> {
          require(pack, "Flags apply only to versioned BSA pack");
          FlagSelection flags = flagValue(option, key, profile.isPresent());
          if (key.equals("-af")) archiveFlags = flags;
          else fileFlags = flags;
        }
        case "-list" -> {
          require(!mutation && option.equals(key), "Inapplicable list");
          list = true;
        }
        case "-dump" -> {
          require(!mutation && option.equals(key), "Inapplicable dump");
          dump = true;
        }
        case "--replace" -> {
          require(mutation && option.equals(key), "Inapplicable replacement");
          target = TargetPolicy.REPLACE;
        }
        case "--no-progress" -> {
          require(mutation && option.equals(key), "Inapplicable progress option");
          noProgress = true;
        }
        case "-share" -> {
          require(pack, "Sharing applies only to pack");
          sharing = booleanValue(option, key, profile.isPresent());
        }
        case "-mt" -> {
          require(mutation, "Workers apply only to mutations");
          workers =
              booleanValue(option, key, profile.isPresent())
                  ? WorkerSelection.AUTOMATIC
                  : new WorkerSelection.UpTo(1);
        }
        case "-split" -> {
          require(pack, "Splitting applies only to pack");
          splitting = splitValue(option, profile.isPresent());
        }
        case "-f" -> {
          require(
              pack && original.length() > 3 && original.charAt(2) == ':',
              "Missing inclusion masks");
          masks = List.of(original.substring(3).split(",", -1));
          require(masks.stream().noneMatch(String::isEmpty), "Empty inclusion mask");
        }
        default -> {
          // Profiles cannot activate safety options at another position.
          require(
              profile.isPresent()
                  && !option.startsWith("--")
                  && !key.equals("-z")
                  && !key.equals("-af")
                  && !key.equals("-ff"),
              "Unsupported or inapplicable switch: " + original);
        }
      }
    }
    require(!pack || family != null, "Pack requires one supported family selector");
    require(
        (family != ArchiveFamily.FO4_GENERAL_BA2 && family != ArchiveFamily.FO4_DDS_BA2)
            || (!seen.contains("-af") && !seen.contains("-ff")),
        "BA2 does not accept BSA flag switches");
    require(
        family != ArchiveFamily.TES3_BSA
            || (!seen.contains("-z") && !seen.contains("-af") && !seen.contains("-ff")),
        "TES3 does not accept compression or flag switches");
    Path archivePath = Path.of(archive);
    // The immutable compatibility bundle also prohibits unsafe stored DDS output.
    if (family == ArchiveFamily.FO4_DDS_BA2) compression = PackOptions.Compression.ZLIB;
    Path destinationPath =
        destination == null ? archivePath.toAbsolutePath().getParent() : Path.of(destination);
    List<PackSource> sources =
        Arrays.stream(sourceNames)
            .<PackSource>map(name -> new PackSource.DetectedPath(Path.of(name)))
            .toList();
    return new Invocation(
        operation,
        archivePath,
        destinationPath,
        sources,
        profile,
        target,
        workers,
        new PackOptions(masks, compression, sharing, splitting, archiveFlags, fileFlags),
        family,
        list || dump,
        dump,
        noProgress);
  }

  /** Builds an operand-free administrative invocation. */
  private static Invocation administrative(String operation) {
    return new Invocation(
        operation,
        null,
        null,
        List.of(),
        Optional.empty(),
        TargetPolicy.FAIL,
        WorkerSelection.AUTOMATIC,
        PackOptions.standard(),
        null,
        false,
        false,
        true);
  }

  /** Reads the two supported boolean spellings without folding any path operands. */
  private static boolean booleanValue(String option, String key, boolean profile) {
    if (profile) {
      return !option.equals(key + ":no");
    }
    require(option.equals(key + ":yes") || option.equals(key + ":no"), "Expected yes or no");
    return option.endsWith(":yes");
  }

  /**
   * Parses a complete unsigned hexadecimal override; profile zero retains legacy automatic
   * selection.
   */
  private static FlagSelection flagValue(String option, String key, boolean profile) {
    require(option.startsWith(key + ":"), "Missing flag value");
    String digits = option.substring(key.length() + 1);
    if (digits.startsWith("0x")) digits = digits.substring(2);
    require(digits.matches("[0-9a-f]+"), "Flags require unsigned hexadecimal digits");
    java.math.BigInteger value = new java.math.BigInteger(digits, 16);
    require(value.bitLength() <= 32, "Flags exceed u32");
    return profile && value.signum() == 0
        ? FlagSelection.AUTOMATIC
        : new FlagSelection.Explicit(value.longValue());
  }

  /** Maps the explicit profile's legacy integer rules to public whole-entry splitting choices. */
  private static PackOptions.Splitting splitValue(String option, boolean profile) {
    if (!profile) {
      require(option.matches("-split:[0-9]+"), "Split must be 0 through 8 GiB");
      java.math.BigInteger number = new java.math.BigInteger(option.substring(7));
      require(
          number.compareTo(java.math.BigInteger.valueOf(8)) <= 0, "Split must be 0 through 8 GiB");
      return new PackOptions.Splitting.UpToBytes(number.longValue() << 30);
    }
    String value = option.length() > 7 ? option.substring(7) : "";
    if (value.isEmpty()) {
      return new PackOptions.Splitting.FamilyDefault();
    }
    if (!value.matches("[+-]?[0-9]+")) {
      return new PackOptions.Splitting.UpToBytes(0);
    }
    java.math.BigInteger number = new java.math.BigInteger(value);
    if (number.signum() < 0) {
      return new PackOptions.Splitting.LegacyPerEntry();
    }
    return new PackOptions.Splitting.UpToBytes(
        number.min(java.math.BigInteger.valueOf(8)).longValue() << 30);
  }

  private static String lower(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  private static void require(boolean valid, String reason) {
    if (!valid) {
      throw new IllegalArgumentException(reason);
    }
  }
}
