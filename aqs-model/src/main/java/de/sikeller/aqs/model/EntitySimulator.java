package de.sikeller.aqs.model;

import lombok.Data;

/**
 * A simulator for a single {@link Entity}. This class is used by the world simulator to the given
 * entity in the world and update their state to a specific point in time.
 */
@Data
public class EntitySimulator {
  /** 1 distance = 1m, 1 time = 1s, 1 speed = 1km/h */
  private static final double speedQuotient = 1000.0 / 3600.0;

  private final Entity entity;

  public void move(long currentTime) {
    var timePassed = currentTime - entity.getLastUpdate();
    if (timePassed == 0) return;
    var movedDistance = speedQuotient * entity.getCurrentSpeed() * timePassed;
    var newPosition = entity.getPosition().moveTowards(entity.getTarget(), movedDistance);
    entity.updatePosition(newPosition, currentTime);
  }

  /** Time needed to move a given distance. Does not modify the entity! */
  public static long timePassed(Entity entity, double movedDistance) {
    return Math.round(movedDistance / (speedQuotient * entity.getCurrentSpeed()));
  }
}
