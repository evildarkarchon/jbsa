package io.github.evildarkarchon.jbsa.build

import groovy.json.JsonOutput
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Serializes insertion-ordered model data as stable UTF-8 JSON with one trailing LF. */
internal object DeterministicJson {
    /** Writes a model whose maps and collections have already been placed in contract order. */
    fun write(path: Path, value: Any) {
        Files.createDirectories(path.parent)
        val json = JsonOutput.prettyPrint(JsonOutput.toJson(value)).replace("\r\n", "\n") + "\n"
        Files.writeString(path, json, StandardCharsets.UTF_8)
    }
}
