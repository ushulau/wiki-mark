package com.wiki.mark.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code diagrams} block: mermaid-cli container rendering settings. All lazy; unset optionals
 * are simply omitted (never assign {@code null}).
 */
public abstract class ConfluenceDiagramsExtension {
  /**
   * Render {@code .mmd} diagrams to PNG. When neither this nor {@code convertToSvg} is set, {@code
   * confluenceRenderDiagrams} logs and skips.
   */
  public abstract Property<Boolean> getConvertToPng();

  /**
   * Render {@code .mmd} diagrams to SVG. Note Confluence serves SVG attachments for download rather
   * than inline, so PNG is the practical format for published pages.
   */
  public abstract Property<Boolean> getConvertToSvg();

  /**
   * Directory holding the {@code .mmd} sources and rendered PNGs. Defaults to pagesDir/diagrams.
   */
  public abstract DirectoryProperty getSourceDir();

  /** mermaid-cli container image; the tag is pinned, override to upgrade. */
  public abstract Property<String> getContainerImage();

  /** Container engine binary: {@code docker} or {@code podman}. */
  public abstract Property<String> getContainerEngine();

  /** Extra engine {@code run} flags before the image; the default depends on the engine. */
  public abstract ListProperty<String> getExtraRunArgs();

  /** {@code mmdc -s} scale factor. */
  public abstract Property<Integer> getScale();

  /** {@code mmdc -b} background color. */
  public abstract Property<String> getBackground();

  /** {@code mmdc -c} config blob; the default disables HTML labels Confluence cannot render. */
  public abstract Property<String> getMermaidJson();

  /** {@code mmdc -p} config blob; the default matches the reference {@code render.sh}. */
  public abstract Property<String> getPuppeteerJson();
}
