package de.sikeller.aqs.model;

import lombok.Data;

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
