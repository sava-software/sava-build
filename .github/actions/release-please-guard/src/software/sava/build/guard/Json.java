package software.sava.build.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the JSON the guard meets: release-please's config and manifest, and GitHub API
 * responses. Objects become insertion-ordered maps, arrays lists, strings strings, numbers
 * a Long when integral and a Double otherwise, booleans booleans, and null null.
 */
final class Json {

  private final String text;
  private int pos;

  private Json(String text) {
    this.text = text;
  }

  /** Parses one JSON value; anything else in the text is an error. */
  static Object parse(String text) {
    final Json json = new Json(text);
    json.skipWhitespace();
    final Object value = json.value();
    json.skipWhitespace();
    if (json.pos != text.length()) {
      throw json.error("text after the JSON value");
    }
    return value;
  }

  /** The value as an object, or an IllegalArgumentException naming what it was meant to be. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> asObject(Object value, String what) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw new IllegalArgumentException(what + " is not a JSON object");
  }

  @SuppressWarnings("unchecked")
  static List<Object> asArray(Object value, String what) {
    if (value instanceof List<?> list) {
      return (List<Object>) list;
    }
    throw new IllegalArgumentException(what + " is not a JSON array");
  }

  /** The JSON string literal for a text. */
  static String string(String text) {
    final StringBuilder out = new StringBuilder("\"");
    for (final char c : text.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  private IllegalArgumentException error(String what) {
    return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + what);
  }

  private void skipWhitespace() {
    while (pos < text.length()) {
      final char c = text.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        pos++;
      } else {
        break;
      }
    }
  }

  private char peek() {
    if (pos >= text.length()) {
      throw error("unexpected end of text");
    }
    return text.charAt(pos);
  }

  private void expect(char c) {
    if (peek() != c) {
      throw error("expected '" + c + "'");
    }
    pos++;
  }

  private Object value() {
    final char c = peek();
    return switch (c) {
      case '{' -> object();
      case '[' -> array();
      case '"' -> string();
      case 't' -> literal("true", Boolean.TRUE);
      case 'f' -> literal("false", Boolean.FALSE);
      case 'n' -> literal("null", null);
      default -> {
        if (c == '-' || (c >= '0' && c <= '9')) {
          yield number();
        }
        throw error("unexpected character '" + c + "'");
      }
    };
  }

  private Object literal(String word, Object value) {
    if (!text.startsWith(word, pos)) {
      throw error("expected " + word);
    }
    pos += word.length();
    return value;
  }

  private Map<String, Object> object() {
    expect('{');
    final Map<String, Object> map = new LinkedHashMap<>();
    skipWhitespace();
    if (peek() == '}') {
      pos++;
      return map;
    }
    while (true) {
      skipWhitespace();
      final String key = string();
      skipWhitespace();
      expect(':');
      skipWhitespace();
      map.put(key, value());
      skipWhitespace();
      final char c = peek();
      pos++;
      if (c == '}') {
        return map;
      }
      if (c != ',') {
        throw error("expected ',' or '}'");
      }
    }
  }

  private List<Object> array() {
    expect('[');
    final List<Object> list = new ArrayList<>();
    skipWhitespace();
    if (peek() == ']') {
      pos++;
      return list;
    }
    while (true) {
      skipWhitespace();
      list.add(value());
      skipWhitespace();
      final char c = peek();
      pos++;
      if (c == ']') {
        return list;
      }
      if (c != ',') {
        throw error("expected ',' or ']'");
      }
    }
  }

  private String string() {
    expect('"');
    final StringBuilder out = new StringBuilder();
    while (true) {
      final char c = peek();
      pos++;
      if (c == '"') {
        return out.toString();
      }
      if (c == '\\') {
        final char escaped = peek();
        pos++;
        switch (escaped) {
          case '"' -> out.append('"');
          case '\\' -> out.append('\\');
          case '/' -> out.append('/');
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> {
            if (pos + 4 > text.length()) {
              throw error("truncated \\u escape");
            }
            int code = 0;
            for (int i = 0; i < 4; i++) {
              final int digit = hexDigit(text.charAt(pos + i));
              if (digit < 0) {
                throw error("invalid \\u escape");
              }
              code = code * 16 + digit;
            }
            out.append((char) code);
            pos += 4;
          }
          default -> throw error("invalid escape '\\" + escaped + "'");
        }
      } else if (c < 0x20) {
        throw error("control character in string");
      } else {
        out.append(c);
      }
    }
  }

  // The four hex digits of a backslash-u escape, ASCII only.
  private static int hexDigit(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    return -1;
  }

  private Object number() {
    final int start = pos;
    if (peek() == '-') {
      pos++;
    }
    // A leading zero stands alone, as in JSON.
    if (peek() == '0' && pos + 1 < text.length() && text.charAt(pos + 1) >= '0' && text.charAt(pos + 1) <= '9') {
      throw error("leading zero");
    }
    digits();
    boolean integral = true;
    if (pos < text.length() && text.charAt(pos) == '.') {
      integral = false;
      pos++;
      digits();
    }
    if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
      integral = false;
      pos++;
      if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
        pos++;
      }
      digits();
    }
    final String literal = text.substring(start, pos);
    try {
      return integral ? (Object) Long.parseLong(literal) : (Object) Double.parseDouble(literal);
    } catch (NumberFormatException e) {
      return Double.parseDouble(literal);
    }
  }

  private void digits() {
    final int start = pos;
    while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
      pos++;
    }
    if (pos == start) {
      throw error("expected a digit");
    }
  }
}
