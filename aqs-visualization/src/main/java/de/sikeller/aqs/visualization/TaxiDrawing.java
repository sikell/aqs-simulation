package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.Taxi;
import lombok.Data;

import java.awt.*;
import java.util.Collection;
import java.util.stream.Collectors;

import static java.lang.Math.round;

@Data
public class TaxiDrawing implements EntityDrawing {
  private final int TAXI_MARKER_SIZE = 30;
  private final Taxi taxi;

  public static Collection<TaxiDrawing> of(Collection<Taxi> collection) {
    return collection.stream().map(TaxiDrawing::new).collect(Collectors.toSet());
  }

  @Override
  public void printBackgroundShape(
      Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    printTarget(g, canvasWidthRatio, canvasHeightRatio);
    printName(g, canvasWidthRatio, canvasHeightRatio);
  }

  @Override
  public void printForegroundShape(
      Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    printPosition(g, canvasWidthRatio, canvasHeightRatio);
  }

  private void printTarget(Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    g.setColor(Color.DARK_GRAY);
    g.setStroke(
        new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] {9}, 0));
    g.drawLine(
        (int) round(taxi.getPosition().getX() * canvasWidthRatio),
        (int) round(taxi.getPosition().getY() * canvasHeightRatio),
        (int) round(taxi.getTarget().getX() * canvasWidthRatio),
        (int) round(taxi.getTarget().getY() * canvasHeightRatio));
  }

  private void printPosition(Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    g.setColor(Color.RED);
    g.fillOval(
        (int) round(taxi.getPosition().getX() * canvasWidthRatio) - TAXI_MARKER_SIZE / 2,
        (int) round(taxi.getPosition().getY() * canvasHeightRatio) - TAXI_MARKER_SIZE / 2,
        TAXI_MARKER_SIZE,
        TAXI_MARKER_SIZE);
  }

  private void printName(Graphics g, double canvasWidthRatio, double canvasHeightRatio) {
    g.setColor(Color.black);
    g.drawString(
        taxi.getName(),
        (int) round(taxi.getPosition().getX() * canvasWidthRatio + ((double) TAXI_MARKER_SIZE / 2)),
        (int) round(taxi.getPosition().getY() * canvasHeightRatio + TAXI_MARKER_SIZE + 15));
  }
}
