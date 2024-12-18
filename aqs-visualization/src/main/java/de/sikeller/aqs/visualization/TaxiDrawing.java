package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.Taxi;
import lombok.RequiredArgsConstructor;

import java.awt.*;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.Math.round;

@RequiredArgsConstructor
public class TaxiDrawing extends EntityDrawing {
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
    printPassengers(g, canvasWidthRatio, canvasHeightRatio);
  }

  private void printTarget(Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    g.setColor(Color.GRAY);
    g.setStroke(
        new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] {9}, 0));
    List<Position> targets = taxi.getTargets().toList();
    for (int i = 0; i < targets.size(); i++) {
      Position prev = i - 1 < 0 ? taxi.getPosition() : targets.get(i - 1);
      Position target = targets.get(i);
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
    g.setColor(Color.RED);
    g.fillOval(
        (int) round(taxi.getPosition().getX() * canvasWidthRatio) - TAXI_MARKER_SIZE / 2,
        (int) round(taxi.getPosition().getY() * canvasHeightRatio) - TAXI_MARKER_SIZE / 2,
        TAXI_MARKER_SIZE,
        TAXI_MARKER_SIZE);
  }

  private void printName(Graphics g, double canvasWidthRatio, double canvasHeightRatio) {
    g.setColor(Color.black);
    g.setFont(defaultFont());
    g.drawString(
        taxi.getName(),
        (int) round(taxi.getPosition().getX() * canvasWidthRatio - ((double) TAXI_MARKER_SIZE / 2)),
        (int)
            round(
                taxi.getPosition().getY() * canvasHeightRatio
                    + ((double) TAXI_MARKER_SIZE / 2)
                    + 15));
  }
}
