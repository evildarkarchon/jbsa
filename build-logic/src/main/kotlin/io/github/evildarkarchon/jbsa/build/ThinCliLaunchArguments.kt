package io.github.evildarkarchon.jbsa.build

/** Canonical JVM arguments retained by packaged CLI tests and the Gradle smoke launch. */
internal object ThinCliLaunchArguments {
    const val COUNT_SYSTEM_PROPERTY = "jbsa.cli.jvmArgument.count"
    val JVM_ARGUMENTS =
        buildList {
            add("--illegal-native-access=deny")
            add("-Dfile.encoding=UTF-8")
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                // The CLI's Win32 handler owns Ctrl+C without starting HotSpot shutdown first.
                add("-Xrs")
            }
            add("--enable-native-access=io.github.evildarkarchon.jbsa.cli,io.github.evildarkarchon.jbsa,org.lwjgl,org.lwjgl.lz4")
            add("--add-modules=org.lwjgl.natives,org.lwjgl.lz4.natives")
        }

    /** Returns the indexed test-process property that carries one JVM argument. */
    fun systemProperty(index: Int): String = "jbsa.cli.jvmArgument.$index"
}
