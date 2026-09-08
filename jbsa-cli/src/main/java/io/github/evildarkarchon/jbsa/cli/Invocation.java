package io.github.evildarkarchon.jbsa.cli;

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
    boolean list,
    boolean dump,
    boolean noProgress) {

  /**
   * Parses the TES3 command slice, rejecting invalid syntax before constructing any source paths.
   */
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
    boolean family = false;
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
        if (profile.isPresent() && Set.of("-share", "-mt", "-split", "-f").contains(key)) {
          continue;
        }
        require(false, "Duplicate switch: " + key);
      }
      switch (key) {
        case "-tes3" -> {
          require(pack && option.equals(key), "Inapplicable family");
          family = true;
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
          // Profiles cannot activate safety options at another position or enable TES3 compression.
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
    require(!pack || family, "Pack requires -tes3");
    Path archivePath = Path.of(archive);
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
        new PackOptions(
            masks,
            PackOptions.Compression.STORED,
            sharing,
            splitting,
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC),
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
