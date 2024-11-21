package de.sikeller.aqs.model;

public interface Entity {
  Position getPosition();

  Position getTarget();

  long getLastUpdate();

  boolean isMoving();

  double getCurrentSpeed();

  void updatePosition(Position position, long currentTime);
}
