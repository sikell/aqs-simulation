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
  private final WorldObject world;

  public void move(long currentTime) {
    var timePassed = currentTime - world.getCurrentTime();
    if (timePassed == 0) return;
    world.setCurrentTime(currentTime);
    // use for loops here to improve performance
    for (Taxi taxi : world.getTaxis()) {
      EntitySimulator simulator = new EntitySimulator(taxi);
      simulator.move(currentTime);
    }
    for (Client client : world.getClientsByMode(ClientMode.WAITING, true)) {
      EntitySimulator simulator = new EntitySimulator(client);
      simulator.move(currentTime);
    }
  }
}
