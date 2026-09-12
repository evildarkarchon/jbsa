package io.github.evildarkarchon.jbsa.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Interprets retained Conformance Case evidence only after the process has finished writing it. */
@DisableCachingByDefault(because = "This lifecycle gate has no reusable output beyond the captured evidence it validates.")
abstract class VerifyAutomatedConformance : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val exitCodeFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val evidenceDirectory: DirectoryProperty

    /** Preserves exit 0/1 evidence semantics and propagates every other exit as infrastructure failure. */
    @TaskAction
    fun verifyOutcome() {
        val exitCode =
            exitCodeFile.get().asFile.readText().trim().toIntOrNull()
                ?: throw GradleException("Automated Conformance did not record a valid process exit code.")
        when (ConformanceExitPolicy.classify(exitCode)) {
            ConformanceProcessOutcome.PASSED -> {
                requireReport()
                logger.lifecycle("Automated Conformance produced complete passing Conformance Case evidence.")
            }
            ConformanceProcessOutcome.NON_PASSING_CASES -> {
                requireReport()
                logger.lifecycle("Automated Conformance retained trustworthy non-passing Conformance Case evidence.")
            }
            ConformanceProcessOutcome.INFRASTRUCTURE_FAILURE ->
                throw GradleException("Automated Conformance infrastructure failure with exit code $exitCode.")
        }
    }

    /** Rejects a claimed evidence outcome whose deterministic aggregate report is absent. */
    private fun requireReport() {
        val report = evidenceDirectory.file("report.json").get().asFile
        if (!report.isFile) {
            throw GradleException("Automated Conformance returned an evidence outcome without ${report.path}.")
        }
    }
}
