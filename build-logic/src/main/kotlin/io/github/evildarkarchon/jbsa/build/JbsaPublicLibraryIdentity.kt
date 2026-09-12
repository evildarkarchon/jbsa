package io.github.evildarkarchon.jbsa.build

/** Stable Gradle model identities owned by the sole public library project. */
internal object JbsaPublicLibraryIdentity {
    const val PROJECT_PATH = ":jbsa"
    const val PUBLICATION_NAME = "library"
    const val ASSEMBLE_PUBLICATION_TASK = "assembleLibraryPublication"
    const val VERIFY_ARTIFACT_TASK = "verifyPublicLibraryArtifact"

    /** Returns the Gradle-generated POM task for the single named publication. */
    fun generatePomTaskName(): String = "generatePomFileFor${PUBLICATION_NAME.replaceFirstChar(Char::uppercase)}Publication"

    /** Returns a path below the project output root for the single named publication. */
    fun publicationPath(relativePath: String): String = "publications/$PUBLICATION_NAME/$relativePath"
}
