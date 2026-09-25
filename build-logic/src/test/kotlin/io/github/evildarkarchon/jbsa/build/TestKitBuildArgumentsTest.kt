package io.github.evildarkarchon.jbsa.build

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Guards the external cache lifetime and isolation required by concurrent TestKit fixtures. */
class TestKitBuildArgumentsTest {
    /** Keeps one fixture's nested builds together without sharing Windows daemon locks with another. */
    @Test
    fun `isolates project caches between fixtures`(@TempDir temporary: Path) {
        val first = TestKitBuildArguments.create(temporary.resolve("first"), arrayOf("help"))
        val repeated = TestKitBuildArguments.create(temporary.resolve("first"), arrayOf("tasks"))
        val second = TestKitBuildArguments.create(temporary.resolve("second"), arrayOf("help"))

        val firstCache = Path.of(first[first.indexOf("--project-cache-dir") + 1])
        val repeatedCache = Path.of(repeated[repeated.indexOf("--project-cache-dir") + 1])
        val secondCache = Path.of(second[second.indexOf("--project-cache-dir") + 1])
        assertEquals(firstCache, repeatedCache)
        assertNotEquals(firstCache, secondCache)
        assertEquals(firstCache.toFile().canonicalFile.toPath(), firstCache)
        assertEquals(secondCache.toFile().canonicalFile.toPath(), secondCache)
        assertFalse(firstCache.startsWith(temporary))
        assertFalse(secondCache.startsWith(temporary))
    }
}
