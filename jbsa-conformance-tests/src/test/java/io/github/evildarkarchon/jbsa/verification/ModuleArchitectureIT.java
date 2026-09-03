package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
final class ModuleArchitectureIT {
  private static final String CLI_ANCHOR = "io.github.evildarkarchon.jbsa.cli.Main";
  private static final String CLI_MODULE = "io.github.evildarkarchon.jbsa.cli";
  private static final String LIBRARY_ANCHOR = "io.github.evildarkarchon.jbsa.PackageAnchor";
  private static final String LIBRARY_MODULE = "io.github.evildarkarchon.jbsa";
  private static final Set<String> LIBRARY_EXPORTS = Set.of("io.github.evildarkarchon.jbsa");

  /** Verifies the JBSA-BUILD-004 and JBSA-BUILD-005 JPMS seams. */
  @Test
  void productionJarsExposeOnlyTheSpecifiedModules() {
    ModuleDescriptor library = descriptor(libraryJar(), LIBRARY_MODULE);
    ModuleDescriptor cli = descriptor(cliJar(), CLI_MODULE);

    assertFalse(library.isAutomatic(), "The library must be an explicit module");
    assertFalse(library.isOpen(), "The library module must not be open");
    assertEquals(
        LIBRARY_EXPORTS,
        library.exports().stream()
            .map(ModuleDescriptor.Exports::source)
            .collect(Collectors.toSet()));
    assertTrue(
        library.exports().stream().noneMatch(exported -> exported.source().contains(".internal")),
        "Implementation packages must not be exported");

    assertFalse(cli.isAutomatic(), "The CLI must be an explicit module");
    assertFalse(cli.isOpen(), "The CLI module must not be open");
    assertTrue(cli.exports().isEmpty(), "The CLI module must not export packages");

    ModuleDescriptor.Requires libraryRequirement =
        cli.requires().stream()
            .filter(requirement -> requirement.name().equals(LIBRARY_MODULE))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("The CLI module must require the library module"));
    assertFalse(
        libraryRequirement.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE),
        "The CLI must not expose the library as a transitive dependency");
    assertFalse(
        libraryRequirement.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC),
        "The library is a mandatory CLI runtime dependency");
  }

  /**
   * Verifies the public type-leakage prohibition in JBSA-BUILD-006.
   *
   * @throws Exception if a production JAR or an exported class cannot be inspected
   */
  @Test
  void exportedLibrarySignaturesContainNoThirdPartyOrInternalTypes() throws Exception {
    ModuleDescriptor descriptor = descriptor(libraryJar(), LIBRARY_MODULE);
    Set<String> exportedPackages =
        descriptor.exports().stream()
            .map(ModuleDescriptor.Exports::source)
            .collect(Collectors.toUnmodifiableSet());

    try (URLClassLoader loader = productionClassLoader();
        JarFile jar = new JarFile(libraryJar().toFile())) {
      for (JarEntry entry : Collections.list(jar.entries())) {
        if (!isClassInExportedPackage(entry, exportedPackages)) {
          continue;
        }
        Class<?> type = Class.forName(className(entry), false, loader);
        if (isCallerVisibleType(type)) {
          assertPublicSignatureUsesAllowedTypes(type, exportedPackages);
        }
      }
    }
  }

  /**
   * Verifies JBSA-BUILD-004 class-path usability for both explicit modules.
   *
   * @throws Exception if the production JARs cannot be loaded
   */
  @Test
  void productionJarsRemainUsableOnTheClassPath() throws Exception {
    try (URLClassLoader loader = productionClassLoader()) {
      assertDoesNotThrow(() -> Class.forName(LIBRARY_ANCHOR, true, loader));
      assertDoesNotThrow(() -> Class.forName(CLI_ANCHOR, true, loader));
    }
  }

  /** Returns the descriptor with the expected name from an explicit modular JAR. */
  private static ModuleDescriptor descriptor(Path jar, String expectedName) {
    assertTrue(jar.toFile().isFile(), () -> "Missing production JAR: " + jar);
    ModuleReference reference =
        ModuleFinder.of(jar)
            .find(expectedName)
            .orElseThrow(() -> new AssertionError("Missing module " + expectedName + " in " + jar));
    assertEquals(expectedName, reference.descriptor().name());
    return reference.descriptor();
  }

  /**
   * Creates an isolated class-path loader containing the production JARs and Java platform classes.
   *
   * @throws IOException if a production JAR path cannot be converted to a URL
   */
  private static URLClassLoader productionClassLoader() throws IOException {
    URL[] urls = {libraryJar().toUri().toURL(), cliJar().toUri().toURL()};
    return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
  }

  /** Checks every caller-visible part of an exported public or protected type's signature. */
  private static void assertPublicSignatureUsesAllowedTypes(
      Class<?> type, Set<String> exportedPackages) {
    SignatureBoundary boundary =
        new SignatureBoundary(exportedPackages, "caller-visible API " + type.getName());
    assertAllowedType(type.getGenericSuperclass(), boundary);
    for (Type interfaceType : type.getGenericInterfaces()) {
      assertAllowedType(interfaceType, boundary);
    }
    assertTypeParameters(type.getTypeParameters(), boundary);
    assertAnnotations(type, boundary);

    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (isCallerVisibleMember(constructor.getModifiers())) {
        assertExecutableTypes(constructor, boundary);
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (isCallerVisibleMember(method.getModifiers())) {
        assertAllowedType(method.getGenericReturnType(), boundary);
        assertExecutableTypes(method, boundary);
      }
    }
    for (Field field : type.getDeclaredFields()) {
      if (isCallerVisibleMember(field.getModifiers())) {
        assertAllowedType(field.getGenericType(), boundary);
        assertAnnotations(field, boundary);
      }
    }
    for (RecordComponent component :
        type.getRecordComponents() == null ? new RecordComponent[0] : type.getRecordComponents()) {
      assertAllowedType(component.getGenericType(), boundary);
      assertAnnotations(component, boundary);
    }
    for (Class<?> permittedSubclass :
        type.getPermittedSubclasses() == null ? new Class<?>[0] : type.getPermittedSubclasses()) {
      assertAllowedType(permittedSubclass, boundary);
    }
  }

  /** Returns whether an exported type is visible to callers or their subclasses. */
  private static boolean isCallerVisibleType(Class<?> type) {
    int modifiers = type.getModifiers();
    Class<?> enclosingType = type.getEnclosingClass();
    if (enclosingType == null) {
      return Modifier.isPublic(modifiers);
    }
    return isCallerVisibleMember(modifiers) && isCallerVisibleType(enclosingType);
  }

  /** Returns whether a member participates in a public type's caller-visible interface. */
  private static boolean isCallerVisibleMember(int modifiers) {
    return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
  }

  /** Checks public or protected executable parameters, failures, variables, and annotations. */
  private static void assertExecutableTypes(Executable executable, SignatureBoundary boundary) {
    for (Type parameter : executable.getGenericParameterTypes()) {
      assertAllowedType(parameter, boundary);
    }
    for (Type failure : executable.getGenericExceptionTypes()) {
      assertAllowedType(failure, boundary);
    }
    assertTypeParameters(executable.getTypeParameters(), boundary);
    assertAnnotations(executable, boundary);
  }

  /** Checks annotations because their types are caller-visible parts of an API declaration. */
  private static void assertAnnotations(AnnotatedElement element, SignatureBoundary boundary) {
    for (Annotation annotation : element.getAnnotations()) {
      assertAllowedType(annotation.annotationType(), boundary);
    }
  }

  /** Checks every bound declared by a generic type variable. */
  private static void assertTypeParameters(
      TypeVariable<?>[] variables, SignatureBoundary boundary) {
    for (TypeVariable<?> variable : variables) {
      assertAllowedType(variable, boundary);
    }
  }

  /** Recursively rejects types outside Java modules and the library's exported packages. */
  private static void assertAllowedType(Type type, SignatureBoundary boundary) {
    if (type == null || !boundary.visited().add(type)) {
      return;
    }
    if (type instanceof Class<?> classType) {
      while (classType.isArray()) {
        classType = classType.getComponentType();
      }
      if (classType.isPrimitive() || classType == Void.TYPE) {
        return;
      }
      Class<?> exposedType = classType;
      Module module = exposedType.getModule();
      boolean isJavaType = module.isNamed() && module.getName().startsWith("java.");
      assertTrue(
          isJavaType || boundary.exportedPackages().contains(exposedType.getPackageName()),
          () ->
              boundary.context()
                  + " leaks non-exported or third-party type "
                  + exposedType.getTypeName());
    } else if (type instanceof ParameterizedType parameterized) {
      assertAllowedType(parameterized.getRawType(), boundary);
      assertAllowedType(parameterized.getOwnerType(), boundary);
      for (Type argument : parameterized.getActualTypeArguments()) {
        assertAllowedType(argument, boundary);
      }
    } else if (type instanceof WildcardType wildcard) {
      for (Type bound : wildcard.getUpperBounds()) {
        assertAllowedType(bound, boundary);
      }
      for (Type bound : wildcard.getLowerBounds()) {
        assertAllowedType(bound, boundary);
      }
    } else if (type instanceof GenericArrayType array) {
      assertAllowedType(array.getGenericComponentType(), boundary);
    } else if (type instanceof TypeVariable<?> variable) {
      for (Type bound : variable.getBounds()) {
        assertAllowedType(bound, boundary);
      }
    } else {
      assertNotNull(type, boundary.context());
    }
  }

  /** Carries the stable exported-package boundary and cycle guard through signature traversal. */
  private record SignatureBoundary(
      Set<String> exportedPackages, Set<Type> visited, String context) {
    /** Creates an independent traversal boundary for one exported API type. */
    private SignatureBoundary(Set<String> exportedPackages, String context) {
      this(exportedPackages, Collections.newSetFromMap(new IdentityHashMap<>()), context);
    }
  }

  /** Returns whether a JAR entry is a class directly contained in an exported package. */
  private static boolean isClassInExportedPackage(JarEntry entry, Set<String> exportedPackages) {
    if (entry.isDirectory()
        || !entry.getName().endsWith(".class")
        || entry.getName().equals("module-info.class")) {
      return false;
    }
    int separator = entry.getName().lastIndexOf('/');
    String packageName =
        separator < 0 ? "" : entry.getName().substring(0, separator).replace('/', '.');
    return exportedPackages.contains(packageName);
  }

  private static String className(JarEntry entry) {
    return entry
        .getName()
        .substring(0, entry.getName().length() - ".class".length())
        .replace('/', '.');
  }

  private static Path libraryJar() {
    return Path.of(System.getProperty("jbsa.library.jar"));
  }

  private static Path cliJar() {
    return Path.of(System.getProperty("jbsa.cli.jar"));
  }
}
