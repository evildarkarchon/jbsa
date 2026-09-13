package io.github.evildarkarchon.jbsa.build

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/** Runs the retained atomic PowerShell algorithm over Gradle's declared canonical release inputs. */
@DisableCachingByDefault(because = "Staging atomically replaces operator-facing release inputs on local disk.")
abstract class StageReleaseInputs @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stagingScript: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildLayoutManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencyInventory: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val canonicalFiles: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeDependencies: DirectoryProperty

    @get:Input abstract val reactorVersion: Property<String>

    @get:Input abstract val powershellExecutable: Property<String>

    @get:OutputDirectory abstract val releaseInputDirectory: DirectoryProperty

    @get:OutputFile abstract val releaseInputManifest: RegularFileProperty

    /**
     * Executes staging and rejects a successful process that failed to materialize either declared output.
     *
     * @throws GradleException when PowerShell fails or the declared staging outputs are absent
     */
    @TaskAction
    fun stage() {
        execOperations
            .exec {
                workingDir(project.rootDir)
                executable = powershellExecutable.get()
                args(
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-File",
                    stagingScript.get().asFile.absolutePath,
                    "-ReactorVersion",
                    reactorVersion.get(),
                    "-BuildLayoutManifest",
                    buildLayoutManifest.get().asFile.absolutePath,
                )
            }
            .assertNormalExitValue()
        if (!releaseInputDirectory.get().asFile.isDirectory || !releaseInputManifest.get().asFile.isFile) {
            throw GradleException("Release staging completed without its declared directory and manifest outputs.")
        }
    }
}
