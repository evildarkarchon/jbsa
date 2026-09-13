package io.github.evildarkarchon.jbsa.build

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/** Smoke-launches the standalone benchmark with a fully declared Java process contract. */
@DisableCachingByDefault(because = "The launcher is a verification process with no generated output.")
abstract class SmokeTestBenchmarkLauncher @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Nested abstract val javaLauncher: Property<JavaLauncher>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: RegularFileProperty

    @get:Input abstract val launcherArguments: ListProperty<String>

    /**
     * Runs the exact `java -jar` operator seam and requires a successful launcher exit.
     *
     * @throws GradleException when Java cannot start or the standalone launcher exits unsuccessfully
     */
    @TaskAction
    fun launch() {
        execOperations
            .exec {
                executable(javaLauncher.get().executablePath.asFile)
                args("-jar", artifact.get().asFile.absolutePath)
                args(launcherArguments.get())
            }
            .assertNormalExitValue()
    }
}
