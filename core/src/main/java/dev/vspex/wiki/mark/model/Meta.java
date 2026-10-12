package dev.vspex.wiki.mark.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Document metadata parsed from HTML-comment headers and YAML front matter.
 *
 * <p>Direct port of {@code mark.metadata.Meta}. Fields are mutable and public by design: the parser
 * fills them incrementally exactly like the Python dataclass.
 */
public class Meta {
  /** Creates empty metadata. */
  public Meta() {}

  /** Parent pages from the {@code Parent} headers. */
  public List<String> parents = new ArrayList<>();

  /** Folders from the {@code Folder} headers. */
  public List<String> folders = new ArrayList<>();

  /** Space key from the {@code Space} header. */
  public String space = "";

  /** Page type from the {@code Type} header; defaults to {@code page}. */
  public String type = "page";

  /** Page title from the {@code Title} header. */
  public String title = "";

  /** Layout from the {@code Layout} header. */
  public String layout = "";

  /** Sidebar from the {@code Sidebar} header. */
  public String sidebar = "";

  /** Emoji from the {@code Emoji} header. */
  public String emoji = "";

  /** Attachments from the {@code Attachment} headers. */
  public List<String> attachments = new ArrayList<>();

  /** Labels from the {@code Label} headers. */
  public List<String> labels = new ArrayList<>();

  /** Content appearance from the {@code Content-Appearance} header. */
  public String contentAppearance = "";

  /** True when {@code Parent} headers declared the parents. */
  public boolean declaredParents = false;

  /** Tri-state like Python ({@code None} = not given). */
  public Boolean synchronizedFlag = null;

  /** Insertion-ordered like the Python dict. Values are String/Long/Double/Boolean/null. */
  public Map<String, Object> properties = new LinkedHashMap<>();

  /** Position from the {@code Order} header, or null when unset. */
  public Integer order = null;

  /** Image alignment from the per-file header. */
  public String imageAlign = "";

  /** Max image width in pixels from the per-file header. */
  public String imageWidth = "";

  /** Max image height in pixels from the per-file header. */
  public String imageHeight = "";
}
