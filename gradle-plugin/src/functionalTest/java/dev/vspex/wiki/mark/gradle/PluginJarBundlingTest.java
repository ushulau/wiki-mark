package dev.vspex.wiki.mark.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/** The plugin jar must bundle core classes, so consumers resolve a single artifact. */
class PluginJarBundlingTest {

  @Test
  void pluginJarBundlesCoreClasses() throws Exception {
    String jarProperty = System.getProperty("plugin.jar");
    assertNotNull(jarProperty, "plugin.jar system property is set");
    Path jar = Path.of(jarProperty);
    assertTrue(Files.isRegularFile(jar), "plugin jar built: " + jar);
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      assertNotNull(
          jarFile.getEntry("dev/vspex/wiki/mark/model/Meta.class"),
          "plugin jar bundles core classes");
      assertNotNull(
          jarFile.getEntry("dev/vspex/wiki/mark/gradle/ConfluencePublishPlugin.class"),
          "plugin jar contains the implementation class");
      var descriptor =
          jarFile.getEntry("META-INF/gradle-plugins/dev.vspex.confluence-publish.properties");
      assertNotNull(descriptor, "plugin jar contains the published plugin descriptor");
      Properties properties = new Properties();
      try (var input = jarFile.getInputStream(descriptor)) {
        properties.load(input);
      }
      assertEquals(
          "dev.vspex.wiki.mark.gradle.ConfluencePublishPlugin",
          properties.getProperty("implementation-class"));
    }
  }
}
