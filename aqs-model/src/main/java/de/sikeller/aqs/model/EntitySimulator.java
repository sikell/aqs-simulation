package de.sikeller.aqs.model;

import lombok.Data;

@Data
public class EntitySimulator {
  private final Entity entity;

  public void move(long currentTime) {
    var timePassed = currentTime - entity.getLastUpdate();
    if (timePassed == 0) return;
    if (!entity.isMoving()) return;
    var movedDistance = entity.getCurrentSpeed() * timePassed;
    var newPosition = entity.getPosition().moveTowards(entity.getTarget(), movedDistance);
    entity.updatePosition(newPosition, currentTime);
  }
}
