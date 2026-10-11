package com.wiki.mark.model;

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
  public List<String> parents = new ArrayList<>();
  public List<String> folders = new ArrayList<>();
  public String space = "";
  public String type = "page";
  public String title = "";
  public String layout = "";
  public String sidebar = "";
  public String emoji = "";
  public List<String> attachments = new ArrayList<>();
  public List<String> labels = new ArrayList<>();
  public String contentAppearance = "";
  public boolean declaredParents = false;

  /** Tri-state like Python ({@code None} = not given). */
  public Boolean synchronizedFlag = null;

  /** Insertion-ordered like the Python dict. Values are String/Long/Double/Boolean/null. */
  public Map<String, Object> properties = new LinkedHashMap<>();

  public Integer order = null;
  public String imageAlign = "";
  public String imageWidth = "";
  public String imageHeight = "";
}
