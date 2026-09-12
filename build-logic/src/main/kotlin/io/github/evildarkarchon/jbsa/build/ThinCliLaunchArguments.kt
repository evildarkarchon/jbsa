package io.github.evildarkarchon.jbsa.build

/** Canonical JVM arguments retained by packaged CLI tests and the Gradle smoke launch. */
internal object ThinCliLaunchArguments {
    const val COUNT_SYSTEM_PROPERTY = "jbsa.cli.jvmArgument.count"
    val JVM_ARGUMENTS =
        listOf(
            "--illegal-native-access=deny",
            "-Dfile.encoding=UTF-8",
            "--enable-native-access=io.github.evildarkarchon.jbsa,org.lwjgl,org.lwjgl.lz4",
            "--add-modules=org.lwjgl.natives,org.lwjgl.lz4.natives",
        )

    /** Returns the indexed test-process property that carries one JVM argument. */
    fun systemProperty(index: Int): String = "jbsa.cli.jvmArgument.$index"
}
