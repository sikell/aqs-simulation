package de.sikeller.aqs.model;

import lombok.Value;

/** A position in the world, represented by x and y coordinates. */
@Value
public class Position {
  int x;
  int y;

  /**
   * Calculate the Euclidean distance between this and another position.
   *
   * @param other the other position
   * @return the Euclidean distance between the two positions
   */
  public double distance(Position other) {
    return Math.sqrt(Math.pow((x - other.x), 2) + Math.pow((y - other.y), 2));
  }

  /**
   * Calculate a new position that is closer to the target position by a certain distance.
   *
   * @param target the target position
   * @param moveDistance the distance to move towards the target
   * @return the new position
   */
  public Position moveTowards(Position target, double moveDistance) {
    // use long values here because the dx * dx of two integers could lead to very large numbers
    long dx = target.getX() - x;
    long dy = target.getY() - y;
    double distance = Math.sqrt(dx * dx + dy * dy);
    if (distance <= moveDistance) return target;

    double directionX = dx / distance;
    double directionY = dy / distance;

    int newX = (int) Math.round(x + directionX * moveDistance);
    int newY = (int) Math.round(y + directionY * moveDistance);
    return new Position(newX, newY);
  }

  /**
   * Calculates the angle in degrees between two position.
   *
   * @param other position
   * @return The angle in degrees between the two position.
   */
  public double calculateAngle(Position other) {
    double angleRad = Math.atan2(other.y - y, other.x - x);
    double angleDeg = Math.toDegrees(angleRad);
    if (angleDeg < 0) {
      angleDeg += 360;
    }
    return angleDeg;
  }
}
