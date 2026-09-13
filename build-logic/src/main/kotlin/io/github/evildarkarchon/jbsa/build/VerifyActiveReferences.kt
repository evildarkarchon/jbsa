package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Rejects stale legacy build instructions from repository surfaces that remain operational. */
@DisableCachingByDefault(because = "The task has no outputs and exists solely as a verification gate.")
abstract class VerifyActiveReferences : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val activeFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allowlistFile: RegularFileProperty

    @get:Internal abstract val repositoryRoot: DirectoryProperty

    /** Scans active text one line at a time so failures identify an actionable source location. */
    @TaskAction
    fun verifyReferences() {
        val root = repositoryRoot.get().asFile.toPath().toAbsolutePath().normalize()
        val violations =
            activeFiles.files
                .asSequence()
                .map { it.toPath().toAbsolutePath().normalize() }
                .filter(Files::isRegularFile)
                .flatMap { path ->
                    Files.readAllLines(path).asSequence().mapIndexedNotNull { index, line ->
                        if (PROHIBITED_REFERENCES.any { it.containsMatchIn(line) }) {
                            val relative = root.relativize(path).toString().replace('\\', '/')
                            ReferenceViolation(
                                relative,
                                index + 1,
                                line.trim(),
                                sha256(line.toByteArray(Charsets.UTF_8)),
                            )
                        } else {
                            null
                        }
                    }
                }
                .sortedWith(compareBy(ReferenceViolation::path, ReferenceViolation::lineNumber))
                .toList()
        val remainingApprovals =
            loadAllowlist(root, violations)
                .groupingBy(AllowedReference::key)
                .eachCount()
                .toMutableMap()
        val unapproved =
            violations.filter { violation ->
                val approvedCount = remainingApprovals[violation.key] ?: 0
                if (approvedCount > 0) {
                    remainingApprovals[violation.key] = approvedCount - 1
                    false
                } else {
                    true
                }
            }

        if (unapproved.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Active legacy build instructions are prohibited:")
                    unapproved.forEach { appendLine("  ${it.diagnostic}") }
                }.trimEnd()
            )
        }
    }

    /** Loads and validates exact-line exceptions before any matching instruction is suppressed. */
    private fun loadAllowlist(root: Path, violations: List<ReferenceViolation>): List<AllowedReference> {
        val properties = Properties()
        Files.newInputStream(allowlistFile.get().asFile.toPath()).use(properties::load)
        try {
            require(properties.getProperty("allowlistVersion") == "1") {
                "The active-reference allowlist must declare allowlistVersion=1."
            }
            val entryCount = properties.required("entryCount").toInt()
            require(entryCount >= 0) { "The active-reference allowlist entryCount must not be negative." }
            val allowed =
                (0 until entryCount).map { index ->
                    val prefix = "entry.$index."
                    val pathText = properties.required(prefix + "path").replace('\\', '/')
                    val relative = Path.of(pathText)
                    require(!relative.isAbsolute && relative.none { it.toString() == ".." }) {
                        "Allowlist path must stay repository-relative: $pathText"
                    }
                    val lineSha256 = properties.required(prefix + "lineSha256")
                    require(lineSha256.matches(Regex("[0-9a-f]{64}"))) {
                        "Allowlist lineSha256 must be lowercase SHA-256 for $pathText."
                    }
                    val category = properties.required(prefix + "category")
                    require(category in ALLOWLIST_CATEGORIES) {
                        "Unsupported active-reference allowlist category '$category' for $pathText."
                    }
                    require(properties.required(prefix + "rationale").isNotBlank()) {
                        "Allowlist entry $pathText must explain why its reference remains."
                    }
                    val expirationPath =
                        properties.getProperty(prefix + "expiresWhenClosed")?.takeIf(String::isNotBlank)
                    if (category == "migration-comparison") {
                        require(expirationPath != null) {
                            "Migration comparison allowlist entry $pathText must declare expiresWhenClosed."
                        }
                        verifyOpenCutoverTicket(root, pathText, expirationPath)
                    } else {
                        require(expirationPath == null) {
                            "Only migration-comparison entries may declare expiresWhenClosed: $pathText"
                        }
                    }
                    AllowedReference(pathText, lineSha256)
                }
            val available = violations.groupingBy(ReferenceViolation::key).eachCount()
            val requested = allowed.groupingBy(AllowedReference::key).eachCount()
            requested.forEach { (key, count) ->
                require((available[key] ?: 0) >= count) {
                    "Allowlist entry ${key.path} with line SHA256 ${key.lineSha256} does not match any prohibited line."
                }
            }
            return allowed
        } catch (exception: IllegalArgumentException) {
            throw GradleException(exception.message ?: "Invalid active-reference allowlist.", exception)
        }
    }

    /** Rejects a temporary exception after the local issue that owns its removal has closed. */
    private fun verifyOpenCutoverTicket(root: Path, referencePath: String, expirationPath: String) {
        val relative = Path.of(expirationPath.replace('\\', '/'))
        require(!relative.isAbsolute && relative.none { it.toString() == ".." }) {
            "Cutover ticket path must stay repository-relative: $expirationPath"
        }
        val ticket = root.resolve(relative).normalize()
        require(ticket.startsWith(root) && Files.isRegularFile(ticket)) {
            "Migration comparison allowlist entry $referencePath names a missing cutover ticket: $expirationPath"
        }
        val state = Files.readString(ticket)
        // The tracker state is the durable removal signal; dates and branch names can drift independently.
        require(!Regex("(?m)^State:\\s*closed\\s*$").containsMatchIn(state)) {
            "Migration comparison allowlist entry $referencePath expired because its cutover ticket is closed."
        }
    }

    /** Returns the required nonblank property value for one allowlist field. */
    private fun Properties.required(name: String): String =
        getProperty(name)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing active-reference allowlist property '$name'.")

    /** Returns a lowercase SHA-256 digest for exact reviewed line bytes. */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class ReferenceKey(val path: String, val lineSha256: String)

    private data class ReferenceViolation(
        val path: String,
        val lineNumber: Int,
        val lineText: String,
        val lineSha256: String,
    ) {
        val key = ReferenceKey(path, lineSha256)
        val diagnostic = "$path:$lineNumber: [lineSha256=$lineSha256] $lineText"
    }

    private data class AllowedReference(val path: String, val lineSha256: String) {
        val key = ReferenceKey(path, lineSha256)
    }

    private companion object {
        val ALLOWLIST_CATEGORIES = setOf("historical-provenance", "migration-comparison", "scanner-test-fixture")
        // Match actionable build syntax and tool-specific instructions, while leaving consumer-POM
        // requirements, package URLs, publication APIs, and the Maven Central proper name intact.
        val PROHIBITED_REFERENCES =
            listOf(
                Regex("(?i)(?<![A-Za-z0-9_-])(?:[.][\\\\/])?mvnw(?:[.]cmd)?(?=[\\s`'\"]|$)"),
                Regex("(?i)(?<![A-Za-z0-9_-])mvn(?:[.]cmd|[.]exe)?(?=\\s)"),
                Regex("(?i)(?:^|[\\s`'\"/\\\\])pom[.]xml\\b"),
                Regex("(?i)[.]mvn[\\\\/]"),
                Regex("(?i)\\bmaven-wrapper\\b"),
                Regex(
                    "(?i)\\bMaven(?:-specific)?\\s+(?:build|reactor|wrapper|lifecycle|gate|command|invocation|verification|tests?|testing|Surefire|Failsafe|plugins?)\\b"
                ),
            )
    }
}
