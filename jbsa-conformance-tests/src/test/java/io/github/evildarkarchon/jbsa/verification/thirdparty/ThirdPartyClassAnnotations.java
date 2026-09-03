package io.github.evildarkarchon.jbsa.verification.thirdparty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Supplies non-exported annotation and annotation-value types for architecture-test fixtures. */
public final class ThirdPartyClassAnnotations {
  private ThirdPartyClassAnnotations() {}

  /** Supplies an enum type that an otherwise allowed annotation can expose as a value. */
  public enum LeakedEnum {
    VALUE
  }

  /** Supplies a nested annotation type that an otherwise allowed annotation can expose. */
  public @interface NestedValue {}

  /** Marks a declaration without remaining visible through runtime reflection. */
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.TYPE)
  public @interface Declaration {}

  /** Marks a parameter without remaining visible through runtime reflection. */
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.PARAMETER)
  public @interface Parameter {}

  /** Marks an exported package without remaining visible through runtime reflection. */
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.PACKAGE)
  public @interface PackageDeclaration {}

  /** Marks a parameter and remains visible through runtime reflection. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface RuntimeParameter {}

  /** Marks a signature type use and remains visible through runtime reflection. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE_USE)
  public @interface RuntimeTypeUse {}

  /** Marks a record component without propagating to its field, accessor, or constructor. */
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.RECORD_COMPONENT)
  public @interface RecordComponentDeclaration {}

  /** Marks a signature type use without remaining visible through runtime reflection. */
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.TYPE_USE)
  public @interface TypeUse {}
}
