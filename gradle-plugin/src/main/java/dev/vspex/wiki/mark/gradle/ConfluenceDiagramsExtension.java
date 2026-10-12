package dev.vspex.wiki.mark.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code diagrams} block: mermaid-cli container rendering settings. All lazy; unset optionals
 * are simply omitted (never assign {@code null}).
 */
public abstract class ConfluenceDiagramsExtension {
  /** Creates the extension. Gradle supplies the managed properties. */
  public ConfluenceDiagramsExtension() {}

  /**
   * Render {@code .mmd} diagrams to PNG. When neither this nor {@code convertToSvg} is set, {@code
   * confluenceRenderDiagrams} logs and skips.
   *
   * @return the convert-to-PNG flag property
   */
  public abstract Property<Boolean> getConvertToPng();

  /**
   * Render {@code .mmd} diagrams to SVG. Note Confluence serves SVG attachments for download rather
   * than inline, so PNG is the practical format for published pages.
   *
   * @return the convert-to-SVG flag property
   */
  public abstract Property<Boolean> getConvertToSvg();

  /**
   * Directory holding the {@code .mmd} sources and rendered PNGs. Defaults to pagesDir/diagrams.
   *
   * @return the source directory property
   */
  public abstract DirectoryProperty getSourceDir();

  /**
   * mermaid-cli container image; the tag is pinned, override to upgrade.
   *
   * @return the container image property
   */
  public abstract Property<String> getContainerImage();

  /**
   * Container engine binary: {@code docker} or {@code podman}.
   *
   * @return the container engine property
   */
  public abstract Property<String> getContainerEngine();

  /**
   * Extra engine {@code run} flags before the image; the default depends on the engine.
   *
   * @return the extra engine run-arguments property
   */
  public abstract ListProperty<String> getExtraRunArgs();

  /**
   * {@code mmdc -s} scale factor.
   *
   * @return the scale property
   */
  public abstract Property<Integer> getScale();

  /**
   * {@code mmdc -b} background color.
   *
   * @return the background property
   */
  public abstract Property<String> getBackground();

  /**
   * {@code mmdc -c} config blob; the default disables HTML labels Confluence cannot render.
   *
   * @return the Mermaid config property
   */
  public abstract Property<String> getMermaidJson();

  /**
   * {@code mmdc -p} config blob; the default matches the reference {@code render.sh}.
   *
   * @return the Puppeteer config property
   */
  public abstract Property<String> getPuppeteerJson();
}
