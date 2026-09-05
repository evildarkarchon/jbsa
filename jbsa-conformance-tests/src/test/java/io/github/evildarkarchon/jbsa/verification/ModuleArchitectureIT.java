package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.verification.thirdparty.ThirdPartyClassAnnotations;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.constant.ClassDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.*;
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

  /**
   * Asserts that inspecting one fixture reports the precise third-party type carried in its API
   * metadata.
   */
  private static void assertRejectedAnnotation(Class<?> fixtureType, Class<?> leakedType) {
    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () ->
                assertPublicSignatureUsesAllowedTypes(
                    fixtureType, Set.of(ModuleArchitectureIT.class.getPackageName())));
    assertFailureNamesType(failure, leakedType);
  }

  /** Asserts that an architecture rejection names the exact leaked binary type. */
  private static void assertFailureNamesType(AssertionError failure, Class<?> leakedType) {
    assertTrue(
        failure.getMessage().contains(leakedType.getName()),
        () ->
            "Expected rejection to name "
                + leakedType.getName()
                + ", but was: "
                + failure.getMessage());
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

  /** Checks a class when it contributes caller-visible type or package API metadata. */
  private static void assertExportedApiClassUsesAllowedTypes(
      Class<?> type, Set<String> exportedPackages) {
    if (isCallerVisibleType(type) || type.getName().endsWith(".package-info")) {
      assertPublicSignatureUsesAllowedTypes(type, exportedPackages);
    }
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
    assertClassFileAnnotations(type, boundary);

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

  /**
   * Checks every declaration, parameter, and signature type annotation retained in a class file.
   * Method and field attributes are limited to caller-visible members so implementation metadata
   * remains outside the API boundary.
   */
  private static void assertClassFileAnnotations(Class<?> type, SignatureBoundary boundary) {
    String resourceName = "/" + type.getName().replace('.', '/') + ".class";
    try (InputStream input = type.getResourceAsStream(resourceName)) {
      assertNotNull(input, () -> "Missing class resource " + resourceName);
      ClassModel classModel = ClassFile.of().parse(input.readAllBytes());
      assertAnnotationAttributes(classModel, boundary);
      for (FieldModel field : classModel.fields()) {
        if (isCallerVisibleMember(field.flags().flagsMask())) {
          assertAnnotationAttributes(field, boundary);
        }
      }
      for (MethodModel method : classModel.methods()) {
        if (isCallerVisibleMember(method.flags().flagsMask())) {
          assertAnnotationAttributes(method, boundary);
        }
      }
      for (Attribute<?> attribute : classModel.attributes()) {
        if (attribute instanceof RecordAttribute record) {
          record.components().forEach(component -> assertAnnotationAttributes(component, boundary));
        }
      }
    } catch (IOException exception) {
      throw new AssertionError("Cannot inspect class resource " + resourceName, exception);
    }
  }

  /** Checks every standard annotation attribute supported on an API class-file element. */
  private static void assertAnnotationAttributes(
      AttributedElement element, SignatureBoundary boundary) {
    for (Attribute<?> attribute : element.attributes()) {
      if (attribute instanceof RuntimeVisibleAnnotationsAttribute annotations) {
        assertAnnotationTypes(annotations.annotations(), boundary);
      } else if (attribute instanceof RuntimeInvisibleAnnotationsAttribute annotations) {
        assertAnnotationTypes(annotations.annotations(), boundary);
      } else if (attribute
          instanceof RuntimeVisibleParameterAnnotationsAttribute parameterAnnotations) {
        parameterAnnotations
            .parameterAnnotations()
            .forEach(annotations -> assertAnnotationTypes(annotations, boundary));
      } else if (attribute
          instanceof RuntimeInvisibleParameterAnnotationsAttribute parameterAnnotations) {
        parameterAnnotations
            .parameterAnnotations()
            .forEach(annotations -> assertAnnotationTypes(annotations, boundary));
      } else if (attribute instanceof RuntimeVisibleTypeAnnotationsAttribute typeAnnotations) {
        assertTypeAnnotationTypes(typeAnnotations.annotations(), boundary);
      } else if (attribute instanceof RuntimeInvisibleTypeAnnotationsAttribute typeAnnotations) {
        assertTypeAnnotationTypes(typeAnnotations.annotations(), boundary);
      } else if (attribute instanceof AnnotationDefaultAttribute annotationDefault) {
        assertAllowedAnnotationValue(annotationDefault.defaultValue(), boundary);
      }
    }
  }

  /** Checks the type named by each declaration or parameter annotation. */
  private static void assertAnnotationTypes(
      Iterable<java.lang.classfile.Annotation> annotations, SignatureBoundary boundary) {
    for (java.lang.classfile.Annotation annotation : annotations) {
      assertAllowedAnnotationType(annotation, boundary);
    }
  }

  /** Checks the annotation type named by each signature type-use annotation. */
  private static void assertTypeAnnotationTypes(
      Iterable<TypeAnnotation> annotations, SignatureBoundary boundary) {
    for (TypeAnnotation annotation : annotations) {
      assertAllowedAnnotationType(annotation.annotation(), boundary);
    }
  }

  /** Rejects an annotation type whose package is outside Java modules and library exports. */
  private static void assertAllowedAnnotationType(
      java.lang.classfile.Annotation annotation, SignatureBoundary boundary) {
    assertAllowedClassDescriptor(annotation.classSymbol(), boundary, "annotation type");
    annotation
        .elements()
        .forEach(element -> assertAllowedAnnotationValue(element.value(), boundary));
  }

  /** Recursively checks every type reference carried by an annotation element value. */
  private static void assertAllowedAnnotationValue(
      AnnotationValue value, SignatureBoundary boundary) {
    if (value instanceof AnnotationValue.OfClass classValue) {
      assertAllowedClassDescriptor(classValue.classSymbol(), boundary, "annotation value type");
    } else if (value instanceof AnnotationValue.OfEnum enumValue) {
      assertAllowedClassDescriptor(enumValue.classSymbol(), boundary, "annotation value type");
    } else if (value instanceof AnnotationValue.OfAnnotation annotationValue) {
      assertAllowedAnnotationType(annotationValue.annotation(), boundary);
    } else if (value instanceof AnnotationValue.OfArray arrayValue) {
      arrayValue.values().forEach(element -> assertAllowedAnnotationValue(element, boundary));
    }
  }

  /** Rejects one class descriptor whose package is outside Java modules and library exports. */
  private static void assertAllowedClassDescriptor(
      ClassDesc classDescriptor, SignatureBoundary boundary, String exposure) {
    while (classDescriptor.isArray()) {
      classDescriptor = classDescriptor.componentType();
    }
    if (classDescriptor.isPrimitive()) {
      return;
    }
    ClassDesc exposedType = classDescriptor;
    String packageName = exposedType.packageName();
    boolean isJavaType =
        ModuleFinder.ofSystem().findAll().stream()
            .map(ModuleReference::descriptor)
            .filter(descriptor -> descriptor.name().startsWith("java."))
            .anyMatch(descriptor -> descriptor.packages().contains(packageName));
    assertTrue(
        isJavaType || boundary.exportedPackages().contains(packageName),
        () ->
            boundary.context()
                + " leaks non-exported or third-party "
                + exposure
                + " "
                + packageName
                + "."
                + exposedType.displayName());
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
        assertExportedApiClassUsesAllowedTypes(type, exportedPackages);
      }
    }
  }

  /** Verifies that CLASS-retained annotations on exported declarations are rejected. */
  @Test
  void classFileScanRejectsClassRetentionDeclarationAnnotations() {
    assertRejectedAnnotation(
        ClassRetentionDeclarationFixture.class, ThirdPartyClassAnnotations.Declaration.class);
  }

  /** Verifies that annotations on caller-visible parameters are rejected. */
  @Test
  void classFileScanRejectsClassRetentionParameterAnnotations() {
    assertRejectedAnnotation(
        ClassRetentionParameterFixture.class, ThirdPartyClassAnnotations.Parameter.class);
  }

  /** Verifies that annotations on caller-visible signature type uses are rejected. */
  @Test
  void classFileScanRejectsClassRetentionTypeUseAnnotations() {
    assertRejectedAnnotation(
        ClassRetentionTypeUseFixture.class, ThirdPartyClassAnnotations.TypeUse.class);
  }

  /** Verifies that runtime-visible annotations on caller-visible parameters are rejected. */
  @Test
  void classFileScanRejectsRuntimeParameterAnnotations() {
    assertRejectedAnnotation(
        RuntimeParameterFixture.class, ThirdPartyClassAnnotations.RuntimeParameter.class);
  }

  /** Verifies that runtime-visible annotations on caller-visible type uses are rejected. */
  @Test
  void classFileScanRejectsRuntimeTypeUseAnnotations() {
    assertRejectedAnnotation(
        RuntimeTypeUseFixture.class, ThirdPartyClassAnnotations.RuntimeTypeUse.class);
  }

  /** Verifies that CLASS-retained annotations on caller-visible record components are rejected. */
  @Test
  void classFileScanRejectsClassRetentionRecordComponentAnnotations() {
    assertRejectedAnnotation(
        ClassRetentionRecordComponentFixture.class,
        ThirdPartyClassAnnotations.RecordComponentDeclaration.class);
  }

  /** Verifies that class literals carried by otherwise allowed annotations are rejected. */
  @Test
  void classFileScanRejectsThirdPartyClassAnnotationValues() {
    assertRejectedAnnotation(ClassValueFixture.class, ThirdPartyClassAnnotations.class);
  }

  /** Verifies that class literals nested in annotation arrays are rejected. */
  @Test
  void classFileScanRejectsThirdPartyTypesInAnnotationArrays() {
    assertRejectedAnnotation(ClassArrayValueFixture.class, ThirdPartyClassAnnotations.class);
  }

  /** Verifies that enum types carried by otherwise allowed annotations are rejected. */
  @Test
  void classFileScanRejectsThirdPartyEnumAnnotationValues() {
    assertRejectedAnnotation(EnumValueFixture.class, ThirdPartyClassAnnotations.LeakedEnum.class);
  }

  /** Verifies that nested third-party annotations carried as values are rejected. */
  @Test
  void classFileScanRejectsNestedThirdPartyAnnotationValues() {
    assertRejectedAnnotation(
        NestedValueFixture.class, ThirdPartyClassAnnotations.NestedValue.class);
  }

  /** Verifies that third-party types carried by annotation defaults are rejected. */
  @Test
  void classFileScanRejectsThirdPartyAnnotationDefaults() {
    assertRejectedAnnotation(AnnotationDefaultFixture.class, ThirdPartyClassAnnotations.class);
  }

  /**
   * Verifies that CLASS-retained third-party annotations on exported packages are rejected.
   *
   * @throws ClassNotFoundException if the package metadata fixture was not compiled
   */
  @Test
  void classFileScanRejectsExportedPackageAnnotations() throws ClassNotFoundException {
    Class<?> packageInfo =
        Class.forName(
            "io.github.evildarkarchon.jbsa.verification.fixturepackage.package-info",
            false,
            ModuleArchitectureIT.class.getClassLoader());
    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () ->
                assertExportedApiClassUsesAllowedTypes(
                    packageInfo, Set.of(packageInfo.getPackageName())));
    assertFailureNamesType(failure, ThirdPartyClassAnnotations.PackageDeclaration.class);
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

  /** Carries a class literal through an otherwise allowed annotation type. */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
  @interface ClassValueCarrier {
    /** Returns the carried class literal. */
    Class<?> value();
  }

  /** Carries class literals in an array through an otherwise allowed annotation type. */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
  @interface ClassArrayValueCarrier {
    /** Returns the carried class literals. */
    Class<?>[] value();
  }

  /** Carries an enum constant through an otherwise allowed annotation type. */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
  @interface EnumValueCarrier {
    /** Returns the carried enum constant. */
    ThirdPartyClassAnnotations.LeakedEnum value();
  }

  /** Carries a nested annotation through an otherwise allowed annotation type. */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
  @interface NestedValueCarrier {
    /** Returns the carried nested annotation. */
    ThirdPartyClassAnnotations.NestedValue value();
  }

  /** Fixture whose default annotation value leaks a third-party class literal. */
  @interface AnnotationDefaultFixture {
    /** Returns a third-party class literal unless an annotation use overrides it. */
    Class<?> value() default ThirdPartyClassAnnotations.class;
  }

  /** Fixture whose annotation value leaks a third-party class literal. */
  @ClassValueCarrier(ThirdPartyClassAnnotations.class)
  static final class ClassValueFixture {}

  /** Fixture whose annotation array value leaks a third-party class literal. */
  @ClassArrayValueCarrier({String.class, ThirdPartyClassAnnotations.class})
  static final class ClassArrayValueFixture {}

  /** Fixture whose annotation value leaks a third-party enum type. */
  @EnumValueCarrier(ThirdPartyClassAnnotations.LeakedEnum.VALUE)
  static final class EnumValueFixture {}

  /** Fixture whose annotation value leaks a nested third-party annotation type. */
  @NestedValueCarrier(@ThirdPartyClassAnnotations.NestedValue)
  static final class NestedValueFixture {}

  /** Fixture whose class declaration leaks a CLASS-retained third-party annotation. */
  @ThirdPartyClassAnnotations.Declaration
  static final class ClassRetentionDeclarationFixture {}

  /** Fixture whose public parameter leaks a CLASS-retained third-party annotation. */
  static final class ClassRetentionParameterFixture {
    /** Accepts an annotated parameter solely to expose it as caller-visible API metadata. */
    public void accept(@ThirdPartyClassAnnotations.Parameter String value) {}
  }

  /** Fixture whose public return type leaks a CLASS-retained third-party type-use annotation. */
  static final class ClassRetentionTypeUseFixture {
    /** Returns an annotated type solely to expose it as caller-visible API metadata. */
    public @ThirdPartyClassAnnotations.TypeUse String value() {
      return "fixture";
    }
  }

  /** Fixture whose public parameter leaks a runtime-visible third-party annotation. */
  static final class RuntimeParameterFixture {
    /** Accepts an annotated parameter solely to expose it as caller-visible API metadata. */
    public void accept(@ThirdPartyClassAnnotations.RuntimeParameter String value) {}
  }

  /** Fixture whose public return type leaks a runtime-visible third-party type-use annotation. */
  static final class RuntimeTypeUseFixture {
    /** Returns an annotated type solely to expose it as caller-visible API metadata. */
    public @ThirdPartyClassAnnotations.RuntimeTypeUse String value() {
      return "fixture";
    }
  }

  /** Fixture whose record component leaks a CLASS-retained third-party annotation. */
  record ClassRetentionRecordComponentFixture(
      @ThirdPartyClassAnnotations.RecordComponentDeclaration String value) {}

  /** Carries the stable exported-package boundary and cycle guard through signature traversal. */
  private record SignatureBoundary(
      Set<String> exportedPackages, Set<Type> visited, String context) {
    /** Creates an independent traversal boundary for one exported API type. */
    private SignatureBoundary(Set<String> exportedPackages, String context) {
      this(exportedPackages, Collections.newSetFromMap(new IdentityHashMap<>()), context);
    }
  }
}
