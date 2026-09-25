package io.github.evildarkarchon.jbsa.build

import java.util.jar.JarFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Inspects the standalone benchmark JAR through the same artifact boundary used by operators. */
@DisableCachingByDefault(because = "The verification task has no output and reads one complete archive.")
abstract class VerifyBenchmarkArtifact : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: RegularFileProperty

    @get:Input abstract val expectedMainClass: Property<String>

    @get:Input abstract val requiredProjectClasses: ListProperty<String>

    @get:Input abstract val requiredBenchmarkClasses: ListProperty<String>

    @get:Input abstract val requiredServiceProviders: MapProperty<String, List<String>>

    /**
     * Rejects missing JMH metadata, launch drift, unmerged services, duplicate entries, and JPMS descriptors.
     *
     * @throws GradleException when the standalone artifact violates its retained contract
     */
    @TaskAction
    fun verify() {
        val path = artifact.get().asFile
        JarFile(path).use { jar ->
            val names = jar.entries().asSequence().map { entry -> entry.name }.toList()
            if (names.size != names.toSet().size) {
                throw GradleException("Standalone benchmark contains duplicate JAR entries: $path")
            }
            val mainClass = jar.manifest?.mainAttributes?.getValue("Main-Class")
            if (mainClass != expectedMainClass.get()) {
                throw GradleException(
                    "Standalone benchmark Main-Class must be ${expectedMainClass.get()}, found $mainClass."
                )
            }
            val moduleDescriptors = names.filter { name -> name == "module-info.class" || name.endsWith("/module-info.class") }
            if (moduleDescriptors.isNotEmpty()) {
                throw GradleException("Standalone benchmark must not contain module descriptors: $moduleDescriptors")
            }
            requireEntries(names, listOf("META-INF/BenchmarkList", "META-INF/CompilerHints"))
            requireEntries(names, requiredProjectClasses.get())
            val benchmarkClasses = requiredBenchmarkClasses.get()
            requireEntries(names, benchmarkClasses)
            benchmarkClasses.forEach { benchmarkClass ->
                val packagePath = benchmarkClass.substringBeforeLast('/', "")
                val simpleName = benchmarkClass.substringAfterLast('/').removeSuffix(".class")
                val generatedPrefix = if (packagePath.isEmpty()) "jmh_generated/" else "$packagePath/jmh_generated/"
                val generated =
                    names.any { name ->
                        name.startsWith("$generatedPrefix${simpleName}_") && name.endsWith("_jmhTest.class")
                    }
                if (!generated) {
                    throw GradleException("Standalone benchmark is missing generated JMH classes for $benchmarkClass.")
                }
            }
            requiredServiceProviders.get().forEach { (servicePath, requiredProviders) ->
                val entry = jar.getJarEntry(servicePath)
                    ?: throw GradleException("Standalone benchmark is missing service metadata $servicePath.")
                val actualProviders =
                    jar.getInputStream(entry).bufferedReader().useLines { lines ->
                        lines
                            .map(String::trim)
                            .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
                            .toSet()
                    }
                val missing = requiredProviders.toSet() - actualProviders
                if (missing.isNotEmpty()) {
                    throw GradleException("Standalone benchmark service $servicePath is missing providers $missing.")
                }
            }
        }
    }

    /**
     * Requires every named archive entry and reports all missing entries together.
     *
     * @throws GradleException when any required entry is absent
     */
    private fun requireEntries(actual: List<String>, required: List<String>) {
        val missing = required.toSet() - actual.toSet()
        if (missing.isNotEmpty()) {
            throw GradleException("Standalone benchmark is missing required entries $missing.")
        }
    }
}
