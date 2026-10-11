package com.wiki.mark.sync;

import com.wiki.mark.model.SyncAction;
import com.wiki.mark.model.SyncResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-file outcomes of one run, in processing order. Replaces the Python {@code --output-format}
 * report: Gradle logging plus a thrown summary instead of JSON.
 *
 * @param results per-file outcomes, in processing order
 */
public record RunReport(List<SyncResult> results) {
  /**
   * Results with action {@code FAILED}, in processing order.
   *
   * @return the failed results
   */
  public List<SyncResult> failures() {
    List<SyncResult> failed = new ArrayList<>();
    for (SyncResult result : results) {
      if (result.action() == SyncAction.FAILED) {
        failed.add(result);
      }
    }
    return failed;
  }

  /**
   * Throws a concise per-file summary when any file failed; a no-op on a clean run. Callers (the
   * Gradle task, the CLI) fail the run with this after processing the batch.
   */
  public void throwIfFailed() {
    List<SyncResult> failed = failures();
    if (failed.isEmpty()) {
      return;
    }
    StringBuilder summary = new StringBuilder();
    summary.append(failed.size()).append(" of ").append(results.size()).append(" files failed:");
    for (SyncResult result : results) {
      summary.append("\n  ").append(result.file()).append(": ");
      if (result.action() == SyncAction.FAILED) {
        summary.append(result.reason());
      } else {
        summary.append(result.action().wireName());
      }
    }
    throw new SyncException(summary.toString());
  }
}
