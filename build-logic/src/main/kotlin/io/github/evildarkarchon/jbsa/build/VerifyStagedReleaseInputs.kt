package io.github.evildarkarchon.jbsa.build

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/** Performs the retained compliance audit against the completed Gradle staging transaction. */
@DisableCachingByDefault(because = "The compliance process audits current tracked and generated repository bytes.")
abstract class VerifyStagedReleaseInputs @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verificationScript: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildLayoutManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resolvedProductionDependencies: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val consumerPom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedSbom: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val algorithmInputs: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseInputDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseInputManifest: RegularFileProperty

    @get:Input abstract val reactorVersion: Property<String>

    @get:Input abstract val powershellExecutable: Property<String>

    /**
     * Executes the post-staging audit with every generated compliance and release-input contract explicit.
     *
     * @throws org.gradle.api.GradleException when PowerShell cannot run or the audit rejects staged inputs
     */
    @TaskAction
    fun verify() {
        execOperations
            .exec {
                workingDir(project.rootDir)
                executable = powershellExecutable.get()
                args(
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-File",
                    verificationScript.get().asFile.absolutePath,
                    "-ReactorVersion",
                    reactorVersion.get(),
                    "-BuildLayoutManifest",
                    buildLayoutManifest.get().asFile.absolutePath,
                    "-ResolvedProductionDependencies",
                    resolvedProductionDependencies.get().asFile.absolutePath,
                    "-ConsumerPomPath",
                    consumerPom.get().asFile.absolutePath,
                    "-GeneratedSbomPath",
                    generatedSbom.get().asFile.absolutePath,
                    "-ReleaseInputRoot",
                    releaseInputDirectory.get().asFile.absolutePath,
                    "-ReleaseInputManifest",
                    releaseInputManifest.get().asFile.absolutePath,
                    "-RequireGeneratedArtifacts",
                    "-VerifyGeneratedComplianceOutputs",
                )
            }
            .assertNormalExitValue()
    }
}
