package dev.vspex.wiki.mark.sync;

/** Article-layout wrapper. Port of {@code mark.cli.apply_layout}. */
public final class Layout {
  private Layout() {}

  /**
   * Wraps {@code storage} in the two-column article layout; any other layout name (including the
   * empty default) returns the storage untouched.
   *
   * @param storage the page body
   * @param layout the layout name
   * @param sidebar the sidebar body
   * @return the wrapped body
   */
  public static String applyLayout(String storage, String layout, String sidebar) {
    if (!"article".equals(layout)) {
      return storage;
    }
    return "<ac:layout>"
        + "<ac:layout-section ac:type=\"two_right_sidebar\">"
        + "<ac:layout-cell>"
        + storage
        + "</ac:layout-cell>"
        + "<ac:layout-cell>"
        + sidebar
        + "</ac:layout-cell>"
        + "</ac:layout-section>"
        + "</ac:layout>";
  }
}
