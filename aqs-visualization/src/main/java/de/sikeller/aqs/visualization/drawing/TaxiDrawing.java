package de.sikeller.aqs.visualization.drawing;

import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.defaultFont;
import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.taxiColor;
import static java.lang.Math.round;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.Taxi;

import java.awt.*;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public class TaxiDrawing extends EntityDrawing {
  private static final int DEFAULT_MARKER_SIZE = 5;
  private final int markerSize;
  private final Taxi taxi;
  private final TaxiDrawingProperties properties;

  public TaxiDrawing(Taxi taxi, TaxiDrawingProperties properties) {
    this.taxi = taxi;
    this.properties = properties;
    this.markerSize = DEFAULT_MARKER_SIZE * properties.getScale();
  }

  public interface TaxiDrawingProperties extends DrawingProperties {
    boolean isShowTaxiPaths();

    boolean isShowTaxiPositions();

    boolean isShowTaxiNames();
  }

  public static List<TaxiDrawing> of(
      Collection<Taxi> collection, TaxiDrawingProperties properties) {
    return collection.stream()
        .sorted(Comparator.comparing(Taxi::getName))
        .map((Taxi t) -> new TaxiDrawing(t, properties))
        .toList();
  }

  @Override
  public void printBackgroundShape(
      Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    if (properties.isShowTaxiPaths()) {
      printTarget(g, canvasWidthRatio, canvasHeightRatio);
    }
    if (properties.isShowTaxiNames()) {
      printName(g, canvasWidthRatio, canvasHeightRatio);
    }
  }

  @Override
  public void printForegroundShape(
      Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    if (properties.isShowTaxiPositions()) {
      printPosition(g, canvasWidthRatio, canvasHeightRatio);
      printPassengers(g, canvasWidthRatio, canvasHeightRatio);
    }
  }

  private void printTarget(Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    g.setColor(Color.GRAY);
    g.setStroke(
        new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] {9}, 0));
    var targets = taxi.getTargets().toList();
    for (int i = 0; i < targets.size(); i++) {
      Position prev = i - 1 < 0 ? taxi.getPosition() : targets.get(i - 1).getPosition();
      Position target = targets.get(i).getPosition();
      if (i == 0) {
        g.setColor(Color.GRAY);
      } else {
        g.setColor(Color.LIGHT_GRAY);
      }
      g.drawLine(
          (int) round(prev.getX() * canvasWidthRatio),
          (int) round(prev.getY() * canvasHeightRatio),
          (int) round(target.getX() * canvasWidthRatio),
          (int) round(target.getY() * canvasHeightRatio));
    }
  }

  private void printPassengers(Graphics g, double canvasWidthRatio, double canvasHeightRatio) {
    g.setColor(Color.black);
    g.setFont(defaultFont());
    String text = "%d".formatted(taxi.getContainedPassengers().size());
    FontMetrics metrics = g.getFontMetrics(defaultFont());
    double textWidth = metrics.stringWidth(text);
    double textHeight = metrics.getHeight();
    double textAscent = metrics.getAscent();
    g.drawString(
        text,
        (int) round(taxi.getPosition().getX() * canvasWidthRatio - textWidth / 2),
        (int) round(taxi.getPosition().getY() * canvasHeightRatio - textHeight / 2 + textAscent));
  }

  private void printPosition(Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    g.setColor(taxiColor());
    g.fillOval(
        (int) round(taxi.getPosition().getX() * canvasWidthRatio) - markerSize / 2,
        (int) round(taxi.getPosition().getY() * canvasHeightRatio) - markerSize / 2,
        markerSize,
        markerSize);
  }

  private void printName(Graphics g, double canvasWidthRatio, double canvasHeightRatio) {
    g.setColor(Color.black);
    g.setFont(defaultFont());
    g.drawString(
        taxi.getName(),
        (int) round(taxi.getPosition().getX() * canvasWidthRatio - ((double) markerSize / 2)),
        (int)
            round(taxi.getPosition().getY() * canvasHeightRatio + ((double) markerSize / 2) + 15));
  }
}
