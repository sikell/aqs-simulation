package de.sikeller.aqs.model;

import lombok.Data;

/**
 * The WorldSimulator class is responsible for simulating the movement of entities within a world.
 * It keeps track of the current state of the world and updates the positions of taxis over time.
 *
 * <p>The move method advances the simulation by a specified amount of time, updating the current
 * time of the world and moving each taxi according to its current speed and target.
 */
@Data
public class WorldSimulator {
  private final World world;

  public void move(long currentTime) {
    var timePassed = currentTime - world.getCurrentTime();
    if (timePassed == 0) return;
    world.setCurrentTime(currentTime);
    world.getTaxis().stream().map(EntitySimulator::new).forEach(taxi -> taxi.move(currentTime));
  }
}
