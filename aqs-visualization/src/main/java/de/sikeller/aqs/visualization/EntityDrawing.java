package de.sikeller.aqs.visualization;

import java.awt.*;

public interface EntityDrawing {
  void printBackgroundShape(Graphics2D g, double canvasWidthRatio, double canvasHeightRatio);
  void printForegroundShape(Graphics2D g, double canvasWidthRatio, double canvasHeightRatio);
}
