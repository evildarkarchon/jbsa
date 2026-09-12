package io.github.evildarkarchon.jbsa.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Rejects incidental application-plugin distributions that are not canonical JBSA artifacts. */
@DisableCachingByDefault(because = "This policy gate has no output and inspects generated-file absence.")
abstract class VerifyThinApplicationOutputs : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prohibitedOutputs: ConfigurableFileCollection

    /** Fails when a launcher script, install tree, ZIP, or TAR exists under the CLI output root. */
    @TaskAction
    fun verifyAbsence() {
        val files = prohibitedOutputs.files.filter { it.isFile }.sortedBy { it.path }
        if (files.isNotEmpty()) {
            throw GradleException(
                "Incidental application outputs are prohibited: ${files.joinToString { it.path }}"
            )
        }
    }
}
