package com.wiki.mark.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

/** Deletes the change-tracking state file, so the next {@code --changed} run sees every page. */
public abstract class ConfluenceResetStateTask extends DefaultTask {
  @Internal
  public abstract DirectoryProperty getProjectDir();

  @TaskAction
  public void resetState() throws IOException {
    Path state = SyncSupport.stateFile(getProjectDir().getAsFile().get().toPath());
    if (Files.deleteIfExists(state)) {
      getLogger().lifecycle("Deleted " + state);
    } else {
      getLogger().lifecycle("No state file at " + state);
    }
  }
}
