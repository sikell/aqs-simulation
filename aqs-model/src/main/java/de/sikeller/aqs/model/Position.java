package de.sikeller.aqs.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode
public class Position {
  private final int x;
  private final int y;

  public double distance(Position other) {
    return Math.sqrt(Math.pow(x, 2) + Math.pow(y, 2));
  }

  public Position moveTowards(Position target, double moveDistance) {
    int dx = target.getX() - x;
    int dy = target.getY() - y;
    double distance = Math.sqrt(dx * dx + dy * dy);
    if (distance <= moveDistance) return target;

    double directionX = dx / distance;
    double directionY = dy / distance;

    int newX = (int) Math.round(x + directionX * moveDistance);
    int newY = (int) Math.round(y + directionY * moveDistance);
    return new Position(newX, newY);
  }
}
