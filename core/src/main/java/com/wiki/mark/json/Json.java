package com.wiki.mark.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer over plain JDK types (the JDK ships no JSON support, and this project
 * takes no runtime dependencies).
 *
 * <p>Model: {@code Map<String, Object>} / {@code List<Object>} / {@code String} / {@code Long} and
 * {@code Double} / {@code Boolean} / {@code null}. Integral JSON numbers parse to {@code Long}
 * (integral literals that overflow a long fall back to {@code Double}); numbers with a fraction or
 * exponent parse to {@code Double}. The writer accepts any {@code Number}.
 *
 * <p>The parser is strict RFC 8259 (no {@code NaN}/{@code Infinity} literals, no leading zeros, no
 * trailing commas), duplicate object keys are last-wins, and trailing garbage after the top-level
 * value is an error. Nesting deeper than {@value #MAX_DEPTH} levels is rejected as hostile input.
 *
 * <p>The writer emits compact JSON (no whitespace), escaping the double quote, the backslash, the
 * short control escapes, and other C0 controls as U+00XX; every other character (including
 * non-ASCII) is written raw as UTF-8.
 */
public final class Json {
  /** Deepest nesting the parser accepts; deeper input throws {@link JsonParseException}. */
  static final int MAX_DEPTH = 100;

  private Json() {}

  /**
   * Parses one JSON document; throws {@link JsonParseException} on any malformation.
   *
   * @param text the JSON document
   * @return the model value
   */
  public static Object parse(String text) {
    Parser parser = new Parser(text);
    Object value = parser.parseValue(0);
    parser.skipWs();
    if (!parser.atEnd()) {
      throw new JsonParseException("trailing characters after JSON value");
    }
    return value;
  }

  /**
   * Writes a model value as compact JSON.
   *
   * @param value the model value
   * @return the compact JSON
   */
  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    writeInto(out, value);
    return out.toString();
  }

  private static void writeInto(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      out.append('"').append(escape(s)).append('"');
    } else if (value instanceof Boolean b) {
      out.append(b ? "true" : "false");
    } else if (value instanceof Double || value instanceof Float) {
      out.append(Double.toString(((Number) value).doubleValue()));
    } else if (value instanceof Number n) {
      out.append(n.toString());
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) {
          out.append(',');
        }
        first = false;
        out.append('"').append(escape(String.valueOf(entry.getKey()))).append('"');
        out.append(':');
        writeInto(out, entry.getValue());
      }
      out.append('}');
    } else if (value instanceof List<?> list) {
      out.append('[');
      boolean first = true;
      for (Object item : list) {
        if (!first) {
          out.append(',');
        }
        first = false;
        writeInto(out, item);
      }
      out.append(']');
    } else {
      // Class name only: never echo an arbitrary value into an exception.
      throw new IllegalArgumentException("cannot write " + value.getClass().getName() + " as JSON");
    }
  }

  private static String escape(String text) {
    StringBuilder out = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20) {
            out.append("\\u00").append(Character.forDigit(c >> 4, 16));
            out.append(Character.forDigit(c & 0xF, 16));
          } else {
            out.append(c);
          }
      }
    }
    return out.toString();
  }

  private static final class Parser {
    private final String text;
    private int pos;

    Parser(String text) {
      this.text = text;
    }

    boolean atEnd() {
      return pos >= text.length();
    }

    void skipWs() {
      while (!atEnd()) {
        char c = text.charAt(pos);
        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
          return;
        }
        pos++;
      }
    }

    Object parseValue(int depth) {
      if (depth > MAX_DEPTH) {
        throw new JsonParseException("JSON nests too deep (over " + MAX_DEPTH + " levels)");
      }
      skipWs();
      if (atEnd()) {
        throw fail("unexpected end of JSON input");
      }
      switch (text.charAt(pos)) {
        case '{':
          return parseObject(depth);
        case '[':
          return parseArray(depth);
        case '"':
          return parseString();
        case 't':
          expect("true");
          return Boolean.TRUE;
        case 'f':
          expect("false");
          return Boolean.FALSE;
        case 'n':
          expect("null");
          return null;
        default:
          return parseNumber();
      }
    }

    Map<String, Object> parseObject(int depth) {
      pos++; // '{'
      Map<String, Object> out = new LinkedHashMap<>();
      skipWs();
      if (consume('}')) {
        return out;
      }
      while (true) {
        skipWs();
        if (atEnd() || text.charAt(pos) != '"') {
          throw fail("expected a string key");
        }
        String key = parseString();
        skipWs();
        if (!consume(':')) {
          throw fail("expected ':'");
        }
        out.put(key, parseValue(depth + 1));
        skipWs();
        if (consume(',')) {
          continue;
        }
        if (consume('}')) {
          return out;
        }
        throw fail("expected ',' or '}'");
      }
    }

    List<Object> parseArray(int depth) {
      pos++; // '['
      List<Object> out = new ArrayList<>();
      skipWs();
      if (consume(']')) {
        return out;
      }
      while (true) {
        out.add(parseValue(depth + 1));
        skipWs();
        if (consume(',')) {
          continue;
        }
        if (consume(']')) {
          return out;
        }
        throw fail("expected ',' or ']'");
      }
    }

    String parseString() {
      pos++; // opening quote
      StringBuilder out = new StringBuilder();
      while (true) {
        if (atEnd()) {
          throw fail("unterminated string");
        }
        char c = text.charAt(pos++);
        if (c == '"') {
          return out.toString();
        }
        if (c == '\\') {
          if (atEnd()) {
            throw fail("unterminated escape");
          }
          char e = text.charAt(pos++);
          switch (e) {
            case '"':
              out.append('"');
              break;
            case '\\':
              out.append('\\');
              break;
            case '/':
              out.append('/');
              break;
            case 'b':
              out.append('\b');
              break;
            case 'f':
              out.append('\f');
              break;
            case 'n':
              out.append('\n');
              break;
            case 'r':
              out.append('\r');
              break;
            case 't':
              out.append('\t');
              break;
            case 'u':
              out.append(parseHex4());
              break;
            default:
              throw fail("bad escape '\\" + e + "'");
          }
        } else if (c < 0x20) {
          throw fail("unescaped control character in string");
        } else {
          out.append(c);
        }
      }
    }

    char parseHex4() {
      if (pos + 4 > text.length()) {
        throw fail("bad unicode escape");
      }
      int code = 0;
      for (int i = 0; i < 4; i++) {
        int digit = Character.digit(text.charAt(pos++), 16);
        if (digit < 0) {
          throw fail("bad unicode escape");
        }
        code = code * 16 + digit;
      }
      return (char) code;
    }

    Object parseNumber() {
      int start = pos;
      if (peek() == '-') {
        pos++;
      }
      if (atEnd()) {
        throw fail("bad number");
      }
      if (peek() == '0') {
        pos++;
      } else if (isDigit(peek())) {
        while (!atEnd() && isDigit(peek())) {
          pos++;
        }
      } else {
        throw fail("bad number");
      }
      boolean decimal = false;
      if (peek() == '.') {
        decimal = true;
        pos++;
        if (atEnd() || !isDigit(peek())) {
          throw fail("bad number");
        }
        while (!atEnd() && isDigit(peek())) {
          pos++;
        }
      }
      if (peek() == 'e' || peek() == 'E') {
        decimal = true;
        pos++;
        if (peek() == '+' || peek() == '-') {
          pos++;
        }
        if (atEnd() || !isDigit(peek())) {
          throw fail("bad number");
        }
        while (!atEnd() && isDigit(peek())) {
          pos++;
        }
      }
      String raw = text.substring(start, pos);
      if (!decimal) {
        try {
          return Long.parseLong(raw);
        } catch (NumberFormatException overflow) {
          // Fall through to Double: integral literals wider than a long.
        }
      }
      try {
        return Double.parseDouble(raw);
      } catch (NumberFormatException bad) {
        throw fail("bad number '" + raw + "'");
      }
    }

    void expect(String literal) {
      if (text.startsWith(literal, pos)) {
        pos += literal.length();
      } else {
        throw fail("expected '" + literal + "'");
      }
    }

    boolean consume(char c) {
      if (!atEnd() && text.charAt(pos) == c) {
        pos++;
        return true;
      }
      return false;
    }

    char peek() {
      return atEnd() ? 0 : text.charAt(pos);
    }

    JsonParseException fail(String message) {
      return new JsonParseException(message + " at offset " + pos);
    }

    static boolean isDigit(char c) {
      return c >= '0' && c <= '9';
    }
  }
}
