package de.sikeller.aqs.runner;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.taxi.algorithm.TaxiAlgorithm;
import de.sikeller.aqs.visualization.VisualizationRunner;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class Runner {
  private final World world;
  private final TaxiAlgorithm algorithm;
  private final VisualizationRunner visu;

  public void init(int clientCount, int taxiCount) {
    for (int i = 0; i < clientCount; i++) {
      world
          .getClients()
          .add(
              Client.builder()
                  .name("c" + i)
                  .position(randomPosition())
                  .target(randomPosition())
                  .build());
    }
    for (int i = 0; i < taxiCount; i++) {
      Position taxiPosition = randomPosition();
      world
          .getTaxis()
          .add(
              Taxi.builder()
                  .name("t" + i)
                  .capacity(2)
                  .position(taxiPosition)
                  .currentSpeed(1)
                  .build());
    }
    visu.update(world);
  }

  @SneakyThrows
  public void run(long sleep) {
    while (!world.isFinished()) {
      var currentTime = world.getCurrentTime() + 1;
      var result = algorithm.nextStep(world);
      log.debug("Step {}: {}", currentTime, result);
      new WorldSimulator(world).move(currentTime);
      visu.update(world);
      Thread.sleep(sleep);
    }
  }

  public void print() {
    log.info("=========================");
    log.info("=== CURRENT SIM STATE ===");
    log.info("=========================");
    world.getClients().forEach(client -> log.info("{}", client));
    world.getTaxis().forEach(taxi -> log.info("{}", taxi));
    log.info("=========================");
  }

  private Position randomPosition() {
    return new Position(
        world.getRandom().nextInt(world.getMaxX()), world.getRandom().nextInt(world.getMaxY()));
  }
}
