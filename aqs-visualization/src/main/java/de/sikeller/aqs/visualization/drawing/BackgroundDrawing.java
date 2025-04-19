package de.sikeller.aqs.visualization.drawing;

import java.awt.*;

public class BackgroundDrawing {
  private final BackgroundDrawingProperties properties;
  private final long currentTime;

  public BackgroundDrawing(BackgroundDrawingProperties properties, long currentTime) {
    this.properties = properties;
    this.currentTime = currentTime;
  }

  public interface BackgroundDrawingProperties extends DrawingProperties {
    boolean isShowScale();

    boolean isShowTime();
  }

  public void printBackgroundShape(
      Graphics2D g,
      double canvasWidthRatio,
      double canvasHeightRatio,
      int canvasWidth,
      int canvasHeight) {
    if (properties.isShowScale()) {
      printScale(g, canvasWidthRatio, canvasHeight);
    }
    if (properties.isShowTime()) {
      printTime(g, canvasHeight);
    }
  }

  private void printScale(Graphics2D g, double canvasWidthRatio, int canvasHeight) {
    int scaleLengthMeters = 1000;
    int pixelLength = (int) Math.round(scaleLengthMeters * canvasWidthRatio);
    int x = 40;
    int y = canvasHeight - 40;
    g.setColor(Color.BLACK);
    g.drawLine(x, y, x + pixelLength, y);
    g.drawLine(x, y - 5, x, y + 5); // left marker
    g.drawLine(x + pixelLength, y - 5, x + pixelLength, y + 5); // right marker
    g.drawString(scaleLengthMeters + " m", x + pixelLength + 5, y + 5);
  }

  private void printTime(Graphics2D g, int canvasHeight) {
    int x = 40;
    int y = canvasHeight - 60;
    g.setColor(Color.BLACK);
    long hours = currentTime / 3600;
    long minutes = (currentTime % 3600) / 60;
    long seconds = currentTime % 60;

    g.drawString("%02d:%02d:%02d".formatted(hours, minutes, seconds), x, y);
  }
}
