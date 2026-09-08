package io.github.evildarkarchon.jbsa.benchmarks;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Small strict JSON reader for externally digest-bound benchmark manifests. */
final class BenchmarkManifest {
  private final String text;
  private int position;

  private BenchmarkManifest(String text) {
    this.text = text;
  }

  /** Reads ordinary or gzip JSON and rejects trailing data and duplicate keys before setup. */
  static Map<?, ?> read(Path path) throws IOException {
    try (var file = Files.newInputStream(path);
        var input = path.toString().endsWith(".gz") ? new GZIPInputStream(file) : file) {
      byte[] bytes = input.readNBytes(256 * 1024 * 1024 + 1);
      if (bytes.length > 256 * 1024 * 1024) throw new IOException("Manifest exceeds 256 MiB");
      var decoder =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      var parser = new BenchmarkManifest(decoder.decode(ByteBuffer.wrap(bytes)).toString());
      Object value = parser.value(0);
      parser.whitespace();
      if (parser.position != parser.text.length() || !(value instanceof Map<?, ?> map))
        throw new IOException("Manifest must be one complete JSON object");
      return map;
    }
  }

  /**
   * Parses bounded nesting and integral manifest values without accepting duplicate object keys.
   */
  private Object value(int depth) throws IOException {
    whitespace();
    if (depth > 32 || position >= text.length()) throw new IOException("Truncated or nested JSON");
    char first = text.charAt(position);
    if (first == '"') return string();
    if (first == '{') {
      position++;
      Map<String, Object> map = new LinkedHashMap<>();
      whitespace();
      if (take('}')) return map;
      do {
        whitespace();
        String key = string();
        whitespace();
        require(':');
        if (map.containsKey(key)) throw new IOException("Duplicate manifest key");
        map.put(key, value(depth + 1));
        whitespace();
        if (take('}')) return map;
        require(',');
      } while (true);
    }
    if (first == '[') {
      position++;
      List<Object> values = new ArrayList<>();
      whitespace();
      if (take(']')) return values;
      do {
        values.add(value(depth + 1));
        whitespace();
        if (take(']')) return values;
        require(',');
      } while (true);
    }
    for (String literal : List.of("true", "false", "null")) {
      if (text.startsWith(literal, position)) {
        position += literal.length();
        return literal.equals("null") ? null : Boolean.valueOf(literal);
      }
    }
    int start = position;
    if (take('-') && position == text.length()) throw new IOException("Truncated JSON number");
    while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
    String number = text.substring(start, position);
    if (!number.matches("-?(0|[1-9][0-9]*)"))
      throw new IOException("Invalid integral manifest value");
    try {
      return Long.parseLong(number);
    } catch (NumberFormatException error) {
      throw new IOException("Manifest integer overflow", error);
    }
  }

  /** Reads JSON strings with escapes, rejecting raw controls and truncated escape sequences. */
  private String string() throws IOException {
    require('"');
    StringBuilder value = new StringBuilder();
    while (position < text.length()) {
      char c = text.charAt(position++);
      if (c == '"') return value.toString();
      if (c < 32) throw new IOException("Control character in JSON string");
      if (c != '\\') {
        value.append(c);
        continue;
      }
      if (position == text.length()) throw new IOException("Truncated JSON escape");
      char escape = text.charAt(position++);
      switch (escape) {
        case '"', '\\', '/' -> value.append(escape);
        case 'b' -> value.append('\b');
        case 'f' -> value.append('\f');
        case 'n' -> value.append('\n');
        case 'r' -> value.append('\r');
        case 't' -> value.append('\t');
        case 'u' -> {
          if (position + 4 > text.length()) throw new IOException("Truncated Unicode escape");
          try {
            value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
          } catch (NumberFormatException error) {
            throw new IOException("Invalid Unicode escape", error);
          }
          position += 4;
        }
        default -> throw new IOException("Invalid JSON escape");
      }
    }
    throw new IOException("Unterminated JSON string");
  }

  private void whitespace() {
    while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
  }

  private boolean take(char expected) {
    if (position < text.length() && text.charAt(position) == expected) {
      position++;
      return true;
    }
    return false;
  }

  private void require(char expected) throws IOException {
    if (!take(expected)) throw new IOException("Expected JSON delimiter: " + expected);
  }
}
