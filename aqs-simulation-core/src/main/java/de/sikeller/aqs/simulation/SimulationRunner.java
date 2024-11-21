package de.sikeller.aqs.simulation;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.taxi.algorithm.TaxiAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class SimulationRunner implements SimulationControl {
  private final World world;
  private final TaxiAlgorithm algorithm;
  private final List<SimulationObserver> listeners = new LinkedList<>();
  private volatile boolean running = false;

  public void init(int clientCount, int taxiCount) {
    initWorld(clientCount, taxiCount);
    algorithm.init(world);
    listeners.forEach(l -> l.onUpdate(world));
  }

  private void initWorld(int clientCount, int taxiCount) {
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
  }

  @SneakyThrows
  @SuppressWarnings(value = "BusyWait")
  public void run(long sleep) {
    while (!world.isFinished()) {
      Thread.sleep(sleep);
      if (!running) {
        continue;
      }
      var currentTime = world.getCurrentTime() + 1;
      var result = algorithm.nextStep(world);
      log.debug("Step {}: {}", currentTime, result);
      new WorldSimulator(world).move(currentTime);
      listeners.forEach(l -> l.onUpdate(world));
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

  public void registerObserver(SimulationObserver observer) {
    listeners.add(observer);
  }

  @Override
  public void start() {
    this.running = true;
  }

  @Override
  public void stop() {
    this.running = false;
  }
}
