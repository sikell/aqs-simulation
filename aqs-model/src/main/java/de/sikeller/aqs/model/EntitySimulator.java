package de.sikeller.aqs.model;

import lombok.Data;

/**
 * A simulator for a single {@link Entity}. This class is used by the world simulator to the given
 * entity in the world and update their state to a specific point in time.
 */
@Data
public class EntitySimulator {
  private final Entity entity;

  public void move(long currentTime) {
    var timePassed = currentTime - entity.getLastUpdate();
    if (timePassed == 0) return;
    var movedDistance = entity.getCurrentSpeed() * timePassed;
    var newPosition = entity.getPosition().moveTowards(entity.getTarget(), movedDistance);
    entity.updatePosition(newPosition, currentTime);
  }
}
