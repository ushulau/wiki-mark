package dev.vspex.wiki.mark.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** MiniJson round-trip and error tests over Confluence-shaped fixtures. */
class JsonTest {

  private static final String ROCKET = new String(Character.toChars(0x1F680));

  private static void assertMalformed(String text) {
    assertThrows(JsonParseException.class, () -> Json.parse(text), "input: " + text);
  }

  @Test
  void parseLiterals() {
    assertEquals(null, Json.parse("null"));
    assertEquals(Boolean.TRUE, Json.parse("true"));
    assertEquals(Boolean.FALSE, Json.parse("false"));
    assertEquals("text", Json.parse("\"text\""));
    assertEquals("", Json.parse("\"\""));
    assertEquals(Long.valueOf(3), Json.parse("3"));
    assertEquals("a\nb", Json.parse("\"a\\nb\""));
  }

  @Test
  void parseIgnoresSurroundingWhitespace() {
    assertEquals(List.of(Long.valueOf(1)), Json.parse("  [1] \t\r\n"));
    assertEquals(Map.of(), Json.parse("\n{}"));
  }

  @Test
  void parseEmptyContainers() {
    assertEquals(Map.of(), Json.parse("{}"));
    assertEquals(List.of(), Json.parse("[]"));
  }

  @Test
  void integralNumbersAreLongAndDecimalsAreDouble() {
    assertEquals(Long.valueOf(3), Json.parse("3"));
    assertEquals(Long.valueOf(-5), Json.parse("-5"));
    assertEquals(Long.valueOf(0), Json.parse("0"));
    assertEquals(Long.valueOf(Long.MAX_VALUE), Json.parse("9223372036854775807"));
    assertEquals(Double.valueOf(3.0), Json.parse("3.0"));
    assertEquals(Double.valueOf(100.0), Json.parse("1e2"));
    assertEquals(Double.valueOf(-0.0025), Json.parse("-2.5E-3"));
  }

  @Test
  void integralOverflowFallsBackToDouble() {
    Object value = Json.parse("9223372036854775808");
    assertTrue(value instanceof Double);
    assertEquals(Double.valueOf(9223372036854775808.0), value);
  }

  @Test
  void duplicateKeysAreLastWins() {
    assertEquals(Map.of("a", Long.valueOf(2)), Json.parse("{\"a\":1,\"a\":2}"));
  }

  @Test
  void trailingGarbageIsAnError() {
    JsonParseException e = assertThrows(JsonParseException.class, () -> Json.parse("{} x"));
    assertTrue(e.getMessage().contains("trailing"));
    assertMalformed("[1] [2]");
    assertMalformed("1 2");
  }

  @Test
  void malformedInputsThrow() {
    assertMalformed("");
    assertMalformed("{");
    assertMalformed("}");
    assertMalformed("[");
    assertMalformed("[1");
    assertMalformed("{\"a\":}");
    assertMalformed("{'a':1}");
    assertMalformed("[01]");
    assertMalformed("00");
    assertMalformed("01");
    assertMalformed("tru");
    assertMalformed("nul");
    assertMalformed("n");
    assertMalformed("-");
    assertMalformed("+");
    assertMalformed("[1,]");
    assertMalformed("{\"a\":1,}");
    assertMalformed("{\"a\" 1}");
    assertMalformed("[1 2]");
    assertMalformed("\"\\x\"");
    assertMalformed("\"unterminated");
    assertMalformed("\"bad " + ((char) 1) + " control\"");
    assertMalformed("NaN");
    assertMalformed("Infinity");
    assertMalformed("[+1]");
    assertMalformed("1.");
    assertMalformed("1e");
    assertMalformed("\"\\u12\"");
    assertMalformed("\"\\ud80x\"");
  }

  @Test
  void depthLimitRejectsHostileNesting() {
    String deep = "[".repeat(105) + "]".repeat(105);
    JsonParseException e = assertThrows(JsonParseException.class, () -> Json.parse(deep));
    assertTrue(e.getMessage().contains("too deep"));
    String maxOk = "[".repeat(100) + "]".repeat(100);
    assertTrue(Json.parse(maxOk) instanceof List);
  }

  @Test
  void unicodeEscapesDecode() {
    assertEquals(((char) 0xE9) + ROCKET, Json.parse("\"\\u00e9\\uD83D\\uDE80\""));
    assertEquals("A", Json.parse("\"\\u0041\""));
    assertEquals("/", Json.parse("\"\\/\""));
  }

  @Test
  void writeIsCompact() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("a", List.of(Long.valueOf(1), Long.valueOf(2)));
    value.put("b", null);
    assertEquals("{\"a\":[1,2],\"b\":null}", Json.write(value));
  }

  @Test
  void writeEscapesControls() {
    String text = "a\"b\\c\bd\fe\nf\rg\th" + ((char) 1) + "i" + ((char) 0x1F) + "j";
    String plain = "\"a\\\"b\\\\c\\bd\\fe\\nf\\rg\\th";
    assertEquals(plain + "\\u0001i\\u001fj\"", Json.write(text));
  }

  @Test
  void writePassesNonAsciiThrough() {
    String value = ((char) 0xE9) + ROCKET;
    assertEquals("\"" + value + "\"", Json.write(value));
  }

  @Test
  void writeNumbersAndLiterals() {
    assertEquals("3", Json.write(Long.valueOf(3)));
    assertEquals("-5", Json.write(Integer.valueOf(-5)));
    assertEquals("3.0", Json.write(Double.valueOf(3.0)));
    assertEquals("0.5", Json.write(Double.valueOf(0.5)));
    assertEquals("true", Json.write(Boolean.TRUE));
    assertEquals("null", Json.write(null));
  }

  @Test
  void roundTripConfluenceShape() {
    Map<String, Object> version = new LinkedHashMap<>();
    version.put("number", Long.valueOf(3));
    version.put("message", "");
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("id", "7");
    page.put("title", "T");
    page.put("version", version);
    page.put("labels", List.of("a", "b"));
    assertEquals(page, Json.parse(Json.write(page)));
  }

  @Test
  void writeRejectsUnsupportedValues() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
    assertTrue(e.getMessage().contains("java.lang.Object"));
  }
}
