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
  /** Creates the task. Gradle supplies the managed properties. */
  public ConfluenceResetStateTask() {}

  /**
   * Project directory; locates the change-tracking state file.
   *
   * @return the project directory property
   */
  @Internal
  public abstract DirectoryProperty getProjectDir();

  /**
   * Deletes the change-tracking state file.
   *
   * @throws IOException if the state file cannot be deleted
   */
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
