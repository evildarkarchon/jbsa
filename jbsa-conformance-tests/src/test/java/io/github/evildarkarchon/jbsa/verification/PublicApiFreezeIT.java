package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Locks the caller-visible Java and JVM signatures of the exported library package. */
@Tag("contract")
@EnabledOnOs(OS.WINDOWS)
final class PublicApiFreezeIT {
  private static final String MODULE = "io.github.evildarkarchon.jbsa";

  /** Compares the compiled JAR with both reviewed 1.0 API baselines. */
  @Test
  void frozenPublicSourceAndBinarySignaturesMatch() throws Exception {
    assertEquals(25, Runtime.version().feature(), "API baselines use the Java 25 toolchain");
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path libraryJar = Path.of(System.getProperty("jbsa.library.jar"));
    ModuleDescriptor descriptor =
        ModuleFinder.of(libraryJar)
            .find(MODULE)
            .orElseThrow(() -> new AssertionError("Missing public library module"))
            .descriptor();
    Set<String> exports = new TreeSet<>();
    descriptor.exports().forEach(export -> exports.add(export.source()));
    List<String> visibleTypes = visibleTypes(libraryJar, exports);
    assertFalse(visibleTypes.isEmpty(), "No caller-visible library types found");

    String header = "module " + MODULE + "\nexports " + String.join(", ", exports) + "\n\n";
    for (String kind : List.of("source", "binary")) {
      String actual = header + javap(libraryJar, visibleTypes, kind.equals("binary"), root, kind);
      Path observed = root.resolve("jbsa-conformance-tests/target/public-api-freeze/" + kind + ".txt");
      Files.createDirectories(observed.getParent());
      Files.writeString(observed, actual);
      Path baseline = root.resolve("docs/development/interface-freeze-" + kind + "-api.txt");
      assertTrue(Files.isRegularFile(baseline), "Missing reviewed API baseline: " + baseline);
      assertEquals(Files.readString(baseline), actual, "Public " + kind + " API changed; inspect " + observed);
    }
  }

  /**
   * Finds all public or protected classes in exported packages, including nested types.
   *
   * @param libraryJar compiled library artifact to inspect
   * @param exports the module's exported package names
   * @return sorted binary names of caller-visible classes
   * @throws IOException if the artifact cannot be read
   * @throws ClassNotFoundException if an exported class cannot be loaded
   */
  private static List<String> visibleTypes(Path libraryJar, Set<String> exports)
      throws IOException, ClassNotFoundException {
    List<String> names = new ArrayList<>();
    try (JarFile jar = new JarFile(libraryJar.toFile());
        URLClassLoader loader =
            new URLClassLoader(
                new java.net.URL[] {libraryJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
      for (var entry : java.util.Collections.list(jar.entries())) {
        String path = entry.getName();
        if (entry.isDirectory()
            || !path.endsWith(".class")
            || path.equals("module-info.class")
            || path.endsWith("package-info.class")) {
          continue;
        }
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
          continue;
        }
        String packageName = path.substring(0, slash).replace('/', '.');
        if (!exports.contains(packageName)) {
          continue;
        }
        String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        int modifiers = Class.forName(name, false, loader).getModifiers();
        if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
          names.add(name);
        }
      }
    }
    names.sort(String::compareTo);
    return names;
  }

  /**
   * Captures Java declarations or JVM descriptors from the qualified JDK's javap tool.
   *
   * @param binary whether JVM descriptors should accompany the declarations
   * @param kind stable source or binary output label
   * @return UTF-8 tool output with LF line endings
   * @throws IOException if javap cannot start or its output cannot be read
   * @throws InterruptedException if the test thread is interrupted while waiting
   */
  private static String javap(
      Path libraryJar, List<String> types, boolean binary, Path root, String kind)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "javap.exe").toString());
    command.add("-protected");
    if (binary) {
      command.add("-s");
    }
    command.add("-classpath");
    command.add(libraryJar.toString());
    command.addAll(types);
    Path output = root.resolve("jbsa-conformance-tests/target/public-api-freeze/javap-" + kind + ".txt");
    Files.createDirectories(output.getParent());
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "javap did not finish");
      String result = Files.readString(output).replace("\r\n", "\n");
      assertEquals(0, process.exitValue(), result);
      return result;
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
