package de.sikeller.aqs.simulation;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.events.EventDispatcher;
import de.sikeller.aqs.simulation.stats.StatsCollector;
import de.sikeller.aqs.visualization.ResultVisualization;
import java.util.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Setter
@Getter
public class SimulationRunner implements SimulationControl {
  private final World world;
  private final Algorithm algorithm;
  private final ResultVisualization resultVisualization = new ResultVisualization();
  private final StatsCollector statsCollector = new StatsCollector();
  private final EventDispatcher eventDispatcher = EventDispatcher.instance();
  private final List<SimulationObserver> listeners = new LinkedList<>();
  private volatile boolean running = false;
  private volatile int speed = 10;
  private volatile boolean simulationFinished;

  private void initWorld(Map<String, Integer> parameters) {

    parameters.forEach(
        (parameter, value) -> {
          if (parameter.contains("client")) {
            for (int i = 0; i < value; i++) {
              world
                  .getClients()
                  .add(
                      Client.builder()
                          .name("c" + i)
                          .position(randomPosition())
                          .target(randomPosition())
                          .build());
            }
          } else if (parameter.contains("taxi")) {
            for (int i = 0; i < value; i++) {
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
        });
  }

  @SneakyThrows
  @SuppressWarnings(value = "BusyWait")
  public void run() {
    simulationFinished = false;
    WorldSimulator worldSimulator = new WorldSimulator(world);
    do {
      while (!world.isFinished()) {
        int sleepMillis = (int) Math.min(1000, Math.round(Math.pow(100.0 / speed, 2.0) - 1));
        Thread.sleep(sleepMillis);
        if (!running) {
          continue;
        }
        var currentTime = world.getCurrentTime() + 1;
        var result = algorithm.getAlgorithm().nextStep(world);
        log.debug("Step {}: {}", currentTime, result);
        worldSimulator.move(currentTime);
        listeners.forEach(l -> l.onUpdate(world));
      }

    } while (world.getClients().isEmpty());

    eventDispatcher.print();
    statsCollector.collect(eventDispatcher, world);
    statsCollector.print();

    resultVisualization.showResults(statsCollector.tableResults());
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

  @Override
  public void init(Map<String, Integer> parameters) {
    if (!world.getClients().isEmpty()) {
      world.reset();
    }
    log.debug("{}", world.getClients());
    log.debug("{}", world.getTaxis());
    initWorld(parameters);
    algorithm.getAlgorithm().init(world);
    listeners.forEach(l -> l.onUpdate(world));
    print();
  }

  @Override
  public SimulationConfiguration getSimulationParameters() {
    return algorithm.getAlgorithm().getParameters();
  }

  @Override
  public void setSimulationFinished(Boolean flag) {
    this.simulationFinished = flag;
  }

  public void showResultVisualization() {
    this.resultVisualization.openResults();
  }
}
