package io.github.evildarkarchon.jbsa.build

import java.lang.module.ModuleDescriptor
import java.lang.module.ModuleFinder
import java.lang.classfile.Annotation as ClassFileAnnotation
import java.lang.classfile.AnnotationValue
import java.lang.classfile.AttributedElement
import java.lang.classfile.ClassFile
import java.lang.classfile.TypeAnnotation
import java.lang.classfile.attribute.AnnotationDefaultAttribute
import java.lang.classfile.attribute.RecordAttribute
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute
import java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute
import java.lang.constant.ClassDesc
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.net.URLClassLoader
import java.util.jar.JarFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Verifies the produced public library as a module-path and class-path consumer would observe it. */
@CacheableTask
abstract class VerifyPublicLibraryArtifact : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryJar: RegularFileProperty

    @get:Classpath abstract val runtimeClasspath: ConfigurableFileCollection

    /** Checks descriptor identity, exported signatures, and class-path usability from packaged bytes. */
    @TaskAction
    fun verifyArtifact() {
        val jar = libraryJar.get().asFile.toPath()
        val descriptor =
            ModuleFinder.of(jar).find(MODULE_NAME).orElseThrow {
                GradleException("Library JAR does not expose module $MODULE_NAME.")
            }.descriptor()
        verifyDescriptor(descriptor)

        val urls = (listOf(jar.toFile()) + runtimeClasspath.files).map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            verifyPublicSignatures(jar, loader)
            try {
                Class.forName(PACKAGE_ANCHOR, true, loader)
            } catch (exception: ReflectiveOperationException) {
                throw GradleException("Library JAR is not usable on the class path.", exception)
            }
        }
    }

    /** Requires the exact explicit, closed module boundary and dependency modifiers. */
    private fun verifyDescriptor(descriptor: ModuleDescriptor) {
        requireArtifact(!descriptor.isAutomatic, "Library must remain an explicit module.")
        requireArtifact(!descriptor.isOpen, "Library module must not be open.")
        requireArtifact(
            descriptor.exports().map { it.source() }.toSet() == setOf(EXPORTED_PACKAGE),
            "Library exports must remain exactly $EXPORTED_PACKAGE.",
        )
        val requirements = descriptor.requires().associateBy { it.name() }
        requireArtifact(
            requirements.keys == setOf("java.base", "jdk.unsupported", "org.lwjgl", "org.lwjgl.lz4"),
            "Library requirements changed: ${requirements.keys}.",
        )
        listOf("org.lwjgl", "org.lwjgl.lz4").forEach { dependency ->
            requireArtifact(
                requirements.getValue(dependency).modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC),
                "Library requirement $dependency must remain static.",
            )
        }
    }

    /** Loads every public class in the exported package and checks each caller-visible type. */
    private fun verifyPublicSignatures(jar: java.nio.file.Path, loader: ClassLoader) {
        JarFile(jar.toFile()).use { archive ->
            archive.entries().asSequence()
                .filter { entry -> isExportedClass(entry.name) }
                .map { entry -> entry.name.removeSuffix(".class").replace('/', '.') }
                .forEach { className ->
                    val type = Class.forName(className, false, loader)
                    if (isCallerVisibleType(type) || className.endsWith(".package-info")) {
                        if (isCallerVisibleType(type)) verifyPublicType(type)
                        val resource = className.replace('.', '/') + ".class"
                        val bytes = archive.getInputStream(archive.getJarEntry(resource)).readAllBytes()
                        verifyClassFileAnnotations(bytes)
                    }
                }
        }
    }

    /** Checks the complete reflection-visible signature surface of one exported public class. */
    private fun verifyPublicType(type: Class<*>) {
        val visited = mutableSetOf<Type>()
        type.genericSuperclass?.let { verifyAllowedType(it, visited) }
        type.genericInterfaces.forEach { verifyAllowedType(it, visited) }
        type.typeParameters.forEach { verifyAllowedType(it, visited) }
        verifyAnnotations(type, visited)

        type.declaredFields.filter { isCallerVisible(it.modifiers) }.forEach { field ->
            verifyAllowedType(field.genericType, visited)
            verifyAnnotations(field, visited)
        }
        type.declaredConstructors.filter { isCallerVisible(it.modifiers) }.forEach { constructor ->
            constructor.genericParameterTypes.forEach { verifyAllowedType(it, visited) }
            constructor.genericExceptionTypes.forEach { verifyAllowedType(it, visited) }
            constructor.typeParameters.forEach { verifyAllowedType(it, visited) }
            verifyAnnotations(constructor, visited)
        }
        type.declaredMethods.filter { isCallerVisible(it.modifiers) }.forEach { method ->
            verifyAllowedType(method.genericReturnType, visited)
            method.genericParameterTypes.forEach { verifyAllowedType(it, visited) }
            method.genericExceptionTypes.forEach { verifyAllowedType(it, visited) }
            method.typeParameters.forEach { verifyAllowedType(it, visited) }
            verifyAnnotations(method, visited)
            method.parameterAnnotations.flatten().forEach { verifyAllowedType(it.annotationClass.java, visited) }
        }
        type.recordComponents?.forEach { component ->
            verifyAllowedType(component.genericType, visited)
            verifyAnnotations(component, visited)
        }
        type.permittedSubclasses?.forEach { verifyAllowedType(it, visited) }
    }

    /** Traverses a generic signature and rejects types outside the JDK or exported library package. */
    private fun verifyAllowedType(type: Type, visited: MutableSet<Type>) {
        if (!visited.add(type)) return
        when (type) {
            is Class<*> -> {
                if (type.isArray) return verifyAllowedType(type.componentType, visited)
                if (type.isPrimitive || type == Void.TYPE) return
                val isJavaType = type.module.isNamed && type.module.name.startsWith("java.")
                if (isJavaType) {
                    requireArtifact(
                        type.name in PUBLIC_JDK_TYPES,
                        "Public signature exposes unreviewed JDK mechanism ${type.name}.",
                    )
                }
                requireArtifact(
                    isJavaType || type.packageName == EXPORTED_PACKAGE,
                    "Public signature leaks prohibited type ${type.name}.",
                )
            }
            is ParameterizedType -> {
                verifyAllowedType(type.rawType, visited)
                type.ownerType?.let { verifyAllowedType(it, visited) }
                type.actualTypeArguments.forEach { verifyAllowedType(it, visited) }
            }
            is GenericArrayType -> verifyAllowedType(type.genericComponentType, visited)
            is WildcardType -> {
                type.upperBounds.forEach { verifyAllowedType(it, visited) }
                type.lowerBounds.forEach { verifyAllowedType(it, visited) }
            }
            is TypeVariable<*> -> type.bounds.forEach { verifyAllowedType(it, visited) }
            else -> throw GradleException("Unsupported public signature type $type.")
        }
    }

    /** Checks runtime-visible annotation types attached to a public declaration. */
    private fun verifyAnnotations(element: AnnotatedElement, visited: MutableSet<Type>) {
        element.annotations.forEach { verifyAllowedType(it.annotationClass.java, visited) }
    }

    /** Checks all declaration, parameter, type-use, default, and record annotation attributes. */
    private fun verifyClassFileAnnotations(bytes: ByteArray) {
        val model = ClassFile.of().parse(bytes)
        verifyAnnotationAttributes(model)
        model.fields().filter { isCallerVisible(it.flags().flagsMask()) }.forEach(::verifyAnnotationAttributes)
        model.methods().filter { isCallerVisible(it.flags().flagsMask()) }.forEach(::verifyAnnotationAttributes)
        model.attributes().filterIsInstance<RecordAttribute>().forEach { record ->
            record.components().forEach(::verifyAnnotationAttributes)
        }
    }

    /** Traverses every standard class-file annotation attribute on a caller-visible element. */
    private fun verifyAnnotationAttributes(element: AttributedElement) {
        element.attributes().forEach { attribute ->
            when (attribute) {
                is RuntimeVisibleAnnotationsAttribute -> verifyAnnotationTypes(attribute.annotations())
                is RuntimeInvisibleAnnotationsAttribute -> verifyAnnotationTypes(attribute.annotations())
                is RuntimeVisibleParameterAnnotationsAttribute ->
                    attribute.parameterAnnotations().forEach(::verifyAnnotationTypes)
                is RuntimeInvisibleParameterAnnotationsAttribute ->
                    attribute.parameterAnnotations().forEach(::verifyAnnotationTypes)
                is RuntimeVisibleTypeAnnotationsAttribute -> verifyTypeAnnotationTypes(attribute.annotations())
                is RuntimeInvisibleTypeAnnotationsAttribute -> verifyTypeAnnotationTypes(attribute.annotations())
                is AnnotationDefaultAttribute -> verifyAnnotationValue(attribute.defaultValue())
                else -> Unit
            }
        }
    }

    /** Checks annotation types and every value carried by their elements. */
    private fun verifyAnnotationTypes(annotations: Iterable<ClassFileAnnotation>) {
        annotations.forEach(::verifyAnnotation)
    }

    /** Checks the annotation wrapped by each class-file signature type-use entry. */
    private fun verifyTypeAnnotationTypes(annotations: Iterable<TypeAnnotation>) {
        annotations.forEach { verifyAnnotation(it.annotation()) }
    }

    /** Checks one annotation type and recursively traverses all element values. */
    private fun verifyAnnotation(annotation: ClassFileAnnotation) {
        verifyClassDescriptor(annotation.classSymbol(), "annotation type")
        annotation.elements().forEach { verifyAnnotationValue(it.value()) }
    }

    /** Checks all type-bearing forms of a class-file annotation element value. */
    private fun verifyAnnotationValue(value: AnnotationValue) {
        when (value) {
            is AnnotationValue.OfClass -> verifyClassDescriptor(value.classSymbol(), "annotation value")
            is AnnotationValue.OfEnum -> verifyClassDescriptor(value.classSymbol(), "annotation value")
            is AnnotationValue.OfAnnotation -> verifyAnnotation(value.annotation())
            is AnnotationValue.OfArray -> value.values().forEach(::verifyAnnotationValue)
            else -> Unit
        }
    }

    /** Rejects a class descriptor outside Java modules and the sole exported package. */
    private fun verifyClassDescriptor(source: ClassDesc, exposure: String) {
        var descriptor = source
        while (descriptor.isArray) descriptor = descriptor.componentType()
        if (descriptor.isPrimitive) return
        val packageName = descriptor.packageName()
        val qualifiedName = if (packageName.isEmpty()) descriptor.displayName() else "$packageName.${descriptor.displayName()}"
        requireArtifact(
            packageName in JAVA_MODULE_PACKAGES || packageName == EXPORTED_PACKAGE,
            "$exposure leaks prohibited type $qualifiedName.",
        )
    }

    /** Reports whether a class entry belongs directly to the sole exported package. */
    private fun isExportedClass(name: String): Boolean =
        name.endsWith(".class") &&
            !name.endsWith("module-info.class") &&
            name.substringBeforeLast('/', "").replace('/', '.') == EXPORTED_PACKAGE

    /** Reports whether a member is visible to callers or subclasses. */
    private fun isCallerVisible(modifiers: Int): Boolean =
        Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)

    /** Reports whether a nested exported type and every enclosing type are caller-visible. */
    private fun isCallerVisibleType(type: Class<*>): Boolean =
        if (type.enclosingClass == null) {
            Modifier.isPublic(type.modifiers)
        } else {
            isCallerVisible(type.modifiers) && isCallerVisibleType(type.enclosingClass)
        }

    /** Converts a failed artifact contract into one actionable Gradle diagnostic. */
    private fun requireArtifact(condition: Boolean, message: String) {
        if (!condition) throw GradleException(message)
    }

    private companion object {
        const val MODULE_NAME = "io.github.evildarkarchon.jbsa"
        const val EXPORTED_PACKAGE = "io.github.evildarkarchon.jbsa"
        const val PACKAGE_ANCHOR = "io.github.evildarkarchon.jbsa.PackageAnchor"
        val JAVA_MODULE_PACKAGES: Set<String> =
            ModuleFinder.ofSystem().findAll()
                .map { it.descriptor() }
                .filter { it.name().startsWith("java.") }
                .flatMap { it.packages() }
                .toSet()
        val PUBLIC_JDK_TYPES =
            setOf(
                "java.lang.Object",
                "java.lang.Record",
                "java.lang.Enum",
                "java.lang.String",
                "java.lang.Long",
                "java.lang.Class",
                "java.lang.annotation.Annotation",
                "java.lang.annotation.Retention",
                "java.lang.annotation.RetentionPolicy",
                "java.lang.annotation.Target",
                "java.lang.annotation.ElementType",
                "java.lang.Throwable",
                "java.lang.AutoCloseable",
                "java.lang.Comparable",
                "java.lang.FunctionalInterface",
                "java.io.IOException",
                "java.nio.ByteBuffer",
                "java.nio.channels.ReadableByteChannel",
                "java.nio.charset.Charset",
                "java.nio.file.Path",
                "java.util.List",
                "java.util.Map",
                "java.util.Set",
                "java.util.SortedMap",
                "java.util.Optional",
                "java.util.OptionalLong",
                "java.util.function.BooleanSupplier",
                "java.util.function.Consumer",
            )
    }
}
