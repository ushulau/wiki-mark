package com.wiki.mark.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Changes-only hash helpers. Port of {@code content_hash} and friends in {@code mark.cli}. */
public final class ContentHash {
  private static final Pattern HASH_LEADING = Pattern.compile("^\\[v([a-f0-9]{40})\\]");
  private static final Pattern HASH_TRAILING = Pattern.compile("\\[v([a-f0-9]{40})\\]$");

  private ContentHash() {}

  /**
   * SHA-1 hex of the storage bytes (UTF-8), the content tag {@code changesOnly} compares.
   *
   * @param storage the storage XHTML
   * @return the hex digest
   */
  public static String contentHash(String storage) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-1").digest(storage.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(40);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-1 missing from the JDK", impossible);
    }
  }

  /**
   * Prefixes the version message with the {@code [v<digest>]} tag.
   *
   * @param message the version message, may be empty
   * @param digest the content digest
   * @return the tagged message
   */
  public static String formatVersionMessage(String message, String digest) {
    String tag = "[v" + digest + "]";
    return message.isEmpty() ? tag : tag + " " + message;
  }

  /**
   * Reads the content tag back from a version message: a leading tag wins, else a trailing tag,
   * else {@code ""}. Null-safe (a missing message simply has no tag).
   *
   * @param message the version message, may be null
   * @return the digest, or {@code ""} when untagged
   */
  public static String readContentHash(String message) {
    if (message == null) {
      return "";
    }
    Matcher leading = HASH_LEADING.matcher(message);
    if (leading.find()) {
      return leading.group(1);
    }
    Matcher trailing = HASH_TRAILING.matcher(message);
    return trailing.find() ? trailing.group(1) : "";
  }
}
