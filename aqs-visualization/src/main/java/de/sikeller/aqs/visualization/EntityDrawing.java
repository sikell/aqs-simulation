package de.sikeller.aqs.visualization;

import java.awt.*;

public abstract class EntityDrawing {

  private static final Font DEFAULT_FONT = new Font("Arial", Font.BOLD, 16);

  public abstract void printBackgroundShape(
      Graphics2D g, double canvasWidthRatio, double canvasHeightRatio);

  public abstract void printForegroundShape(
      Graphics2D g, double canvasWidthRatio, double canvasHeightRatio);

  protected Font defaultFont() {
    return DEFAULT_FONT;
  }
}
