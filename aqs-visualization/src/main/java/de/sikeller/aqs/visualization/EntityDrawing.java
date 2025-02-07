package de.sikeller.aqs.visualization;

import java.awt.*;

public abstract class EntityDrawing {
  public abstract void printBackgroundShape(
      Graphics2D g, double canvasWidthRatio, double canvasHeightRatio);

  public abstract void printForegroundShape(
      Graphics2D g, double canvasWidthRatio, double canvasHeightRatio);
}
