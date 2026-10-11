package com.wiki.mark.gradle;

import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/**
 * Publishes Markdown pages to Confluence. Registers {@code confluenceRenderDiagrams}, {@code
 * confluenceCompile}, {@code confluencePublish}, and {@code confluenceResetState}. No dependency on
 * the {@code java} plugin; safe to apply to the root or any project.
 */
public class ConfluencePublishPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    ConfluenceExtension extension =
        project.getExtensions().create("confluence", ConfluenceExtension.class);
    defaults(project, extension);

    TaskProvider<ConfluenceRenderDiagramsTask> render =
        project
            .getTasks()
            .register(
                "confluenceRenderDiagrams",
                ConfluenceRenderDiagramsTask.class,
                task -> {
                  ConfluenceDiagramsExtension diagrams = extension.getDiagrams();
                  task.getConvertToPng().convention(diagrams.getConvertToPng());
                  task.getConvertToSvg().convention(diagrams.getConvertToSvg());
                  task.getSourceDir().convention(diagrams.getSourceDir());
                  task.getOutputDir().convention(task.getSourceDir());
                  task.getMmdSources()
                      .from(
                          task.getSourceDir()
                              .map(dir -> dir.getAsFileTree().matching(p -> p.include("*.mmd"))));
                  task.getContainerImage().convention(diagrams.getContainerImage());
                  task.getContainerEngine().convention(diagrams.getContainerEngine());
                  task.getExtraRunArgs().convention(diagrams.getExtraRunArgs());
                  task.getScale().convention(diagrams.getScale());
                  task.getBackground().convention(diagrams.getBackground());
                  task.getMermaidJson().convention(diagrams.getMermaidJson());
                  task.getPuppeteerJson().convention(diagrams.getPuppeteerJson());
                  task.getProjectDir().convention(project.getLayout().getProjectDirectory());
                  task.getPathEnvironment()
                      .convention(project.getProviders().environmentVariable("PATH"));
                  task.getOnlyDiagrams().convention(List.of());
                });

    TaskProvider<ConfluenceCompileTask> compile =
        project
            .getTasks()
            .register(
                "confluenceCompile",
                ConfluenceCompileTask.class,
                task -> {
                  // Images must be fresh before compile validates them (and Gradle
                  // requires the edge: pagesDir input overlaps the render output).
                  task.dependsOn(render);
                  task.getPagesDir().convention(extension.getPagesDir());
                  task.getPageIncludes().convention(extension.getPageIncludes());
                  task.getSpace().convention(extension.getSpace());
                  task.getImageHeight().convention(extension.getImageHeight());
                  task.getImageWidth().convention(extension.getImageWidth());
                  task.getImageAlign().convention(extension.getImageAlign());
                  task.getContentAppearance().convention(extension.getContentAppearance());
                  task.getTitleFromH1().convention(extension.getTitleFromH1());
                  task.getTitleFromFilename().convention(extension.getTitleFromFilename());
                  task.getTitleAppendHash().convention(extension.getTitleAppendHash());
                  task.getDropH1().convention(extension.getDropH1());
                  task.getParents().convention(extension.getParents());
                  task.getParentsFromPath().convention(extension.getParentsFromPath());
                  task.getParentsFromPathRoot().convention(extension.getParentsFromPathRoot());
                  task.getAppendLabels().convention(extension.getAppendLabels());
                  task.getMinorEdit().convention(extension.getMinorEdit());
                  task.getVersionMessage().convention(extension.getVersionMessage());
                  task.getChangesOnly().convention(extension.getChangesOnly());
                  task.getAttachReferenced().convention(extension.getAttachReferenced());
                  task.getContinueOnError().convention(extension.getContinueOnError());
                  task.getOutputDir()
                      .convention(
                          project.getLayout().getBuildDirectory().dir("confluence/storage"));
                });

    project
        .getTasks()
        .register(
            "confluencePublish",
            ConfluencePublishTask.class,
            task -> {
              task.dependsOn(compile);
              task.dependsOn(render);
              task.getBaseUrl().convention(extension.getBaseUrl());
              task.getAuthToken().convention(extension.getAuthToken());
              task.getUsername().convention(extension.getUsername());
              task.getTrustStore().convention(extension.getTrustStore());
              task.getTrustStorePassword().convention(extension.getTrustStorePassword());
              task.getInsecureSkipTlsVerify().convention(extension.getInsecureSkipTlsVerify());
              task.getPagesDir().convention(extension.getPagesDir());
              task.getProjectDir().convention(project.getLayout().getProjectDirectory());
              task.getPageIncludes().convention(extension.getPageIncludes());
              task.getSpace().convention(extension.getSpace());
              task.getImageHeight().convention(extension.getImageHeight());
              task.getImageWidth().convention(extension.getImageWidth());
              task.getImageAlign().convention(extension.getImageAlign());
              task.getContentAppearance().convention(extension.getContentAppearance());
              task.getTitleFromH1().convention(extension.getTitleFromH1());
              task.getTitleFromFilename().convention(extension.getTitleFromFilename());
              task.getTitleAppendHash().convention(extension.getTitleAppendHash());
              task.getDropH1().convention(extension.getDropH1());
              task.getParents().convention(extension.getParents());
              task.getParentsFromPath().convention(extension.getParentsFromPath());
              task.getParentsFromPathRoot().convention(extension.getParentsFromPathRoot());
              task.getAppendLabels().convention(extension.getAppendLabels());
              task.getMinorEdit().convention(extension.getMinorEdit());
              task.getVersionMessage().convention(extension.getVersionMessage());
              task.getChangesOnly().convention(extension.getChangesOnly());
              task.getAttachReferenced().convention(extension.getAttachReferenced());
              task.getContinueOnError().convention(extension.getContinueOnError());
              task.getPreview().convention(false);
              task.getChanged().convention(extension.getOnlyChanged());
              task.getOnlyFiles().convention(List.of());
            });

    project
        .getTasks()
        .register(
            "confluenceResetState",
            ConfluenceResetStateTask.class,
            task -> task.getProjectDir().convention(project.getLayout().getProjectDirectory()));

    project.afterEvaluate(
        ignored -> {
          if (extension.getVerifyOnCheck().getOrElse(false)) {
            project
                .getPlugins()
                .withId(
                    "base",
                    plugin -> project.getTasks().named("check", check -> check.dependsOn(compile)));
          }
        });
  }

  private static void defaults(Project project, ConfluenceExtension extension) {
    extension
        .getAuthToken()
        .convention(
            project
                .getProviders()
                .gradleProperty("confluence.token")
                .orElse(project.getProviders().environmentVariable("CONFLUENCE_TOKEN")));
    extension
        .getPagesDir()
        .convention(project.getLayout().getProjectDirectory().dir("documentation/wiki"));
    extension.getPageIncludes().convention(List.of("**/*.md"));
    extension.getParents().convention(List.of());
    extension.getBaseUrl().convention("");
    extension.getUsername().convention("");
    extension.getSpace().convention("");
    extension.getImageHeight().convention("");
    extension.getImageWidth().convention("");
    extension.getImageAlign().convention("");
    extension.getContentAppearance().convention("");
    extension.getTitleFromH1().convention(false);
    extension.getTitleFromFilename().convention(false);
    extension.getTitleAppendHash().convention(false);
    extension.getDropH1().convention(false);
    extension.getParentsFromPath().convention(false);
    extension.getParentsFromPathRoot().convention("");
    extension.getAppendLabels().convention(false);
    extension.getMinorEdit().convention(false);
    extension.getVersionMessage().convention("");
    extension.getChangesOnly().convention(false);
    extension.getAttachReferenced().convention(false);
    extension.getContinueOnError().convention(false);
    extension.getOnlyChanged().convention(false);
    extension.getVerifyOnCheck().convention(false);
    extension.getTrustStorePassword().convention("");
    extension.getInsecureSkipTlsVerify().convention(false);

    ConfluenceDiagramsExtension diagrams = extension.getDiagrams();
    diagrams.getConvertToPng().convention(false);
    diagrams.getConvertToSvg().convention(false);
    diagrams.getSourceDir().convention(extension.getPagesDir().map(pages -> pages.dir("diagrams")));
    diagrams.getContainerImage().convention(RenderSupport.DEFAULT_CONTAINER_IMAGE);
    diagrams.getContainerEngine().convention("docker");
    diagrams
        .getExtraRunArgs()
        .convention(diagrams.getContainerEngine().map(RenderSupport::defaultExtraRunArgs));
    diagrams.getScale().convention(2);
    diagrams.getBackground().convention("white");
    diagrams.getMermaidJson().convention(RenderSupport.MERMAID_JSON);
    diagrams.getPuppeteerJson().convention(RenderSupport.PUPPETEER_JSON);
  }
}
