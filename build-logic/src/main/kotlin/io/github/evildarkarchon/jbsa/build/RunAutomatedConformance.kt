package io.github.evildarkarchon.jbsa.build

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.work.DisableCachingByDefault

/** Executes the retained PowerShell Conformance Case algorithm and records its exact exit status. */
@DisableCachingByDefault(because = "Conformance evidence records the current process environment and wall-clock observations.")
abstract class RunAutomatedConformance
@Inject
constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runnerScript: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val algorithmInputs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractInputs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val candidateArtifacts: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val codecProfile: RegularFileProperty

    @get:Input abstract val powershellExecutable: Property<String>

    @get:Input abstract val mode: Property<String>

    @get:OutputDirectory abstract val evidenceDirectory: DirectoryProperty

    @get:OutputFile abstract val exitCodeFile: RegularFileProperty

    /** Runs PowerShell without immediate nonzero failure so Gradle can retain evidence before interpretation. */
    @TaskAction
    fun captureEvidence() {
        val evidence = evidenceDirectory.get().asFile
        fileSystemOperations.delete { delete(evidence) }
        val candidates = candidateArtifacts.files.sortedBy { it.name }
        require(candidates.size == 2) {
            "Automated Conformance requires exactly the built library and CLI candidate artifacts."
        }
        val library = candidates.single { it.name.startsWith("jbsa-") && !it.name.startsWith("jbsa-cli-") }
        val cli = candidates.single { it.name.startsWith("jbsa-cli-") }
        val result =
            execOperations.exec {
                executable = powershellExecutable.get()
                args(
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-File",
                    runnerScript.get().asFile.absolutePath,
                    "-RepositoryRoot",
                    project.rootDir.absolutePath,
                    "-OutputDirectory",
                    evidence.absolutePath,
                    "-Mode",
                    mode.get(),
                    "-LibraryArtifactPath",
                    library.absolutePath,
                    "-CliArtifactPath",
                    cli.absolutePath,
                    "-CodecProfilePath",
                    codecProfile.get().asFile.absolutePath,
                )
                isIgnoreExitValue = true
            }
        val resultFile = exitCodeFile.get().asFile
        resultFile.parentFile.mkdirs()
        resultFile.writeText("${result.exitValue}\n")
    }
}
