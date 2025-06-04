package de.sikeller.aqs.simulation;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.events.EventDispatcher;
import de.sikeller.aqs.simulation.stats.CollectorMinMaxAverage;
import de.sikeller.aqs.simulation.stats.StatsCollector;
import de.sikeller.aqs.visualization.ResultVisualization;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
  private final WorldObject world;
  private final Algorithm algorithm;
  private final WorldGenerator worldGenerator;
  private final ResultVisualization resultVisualization = new ResultVisualization();
  private final StatsCollector statsCollector = new StatsCollector();
  private final EventDispatcher eventDispatcher = EventDispatcher.instance();
  private final List<SimulationObserver> listeners = new LinkedList<>();
  private volatile boolean running = false;
  private volatile int speed = 15;
  private volatile boolean simulationInitialized = false;
  private volatile boolean simulationFinished = false;

  @SneakyThrows
  @SuppressWarnings(value = "BusyWait")
  public void run() {
    if (!simulationInitialized) {
      return;
    }

    simulationFinished = false;
    simulationInitialized = false;

    WorldSimulator worldSimulator = new WorldSimulator(world);
    var algorithmCalculationTime = CollectorMinMaxAverage.longCollector();
    var customCalculationTime = CollectorMinMaxAverage.longCollector();
    while (!world.isFinished()) {
      int sleepMillis = (int) Math.min(1000, Math.round(Math.pow(100.0 / speed, 2.0) - 1));
      Thread.sleep(sleepMillis);
      if (!running) {
        continue;
      }
      var currentTime = world.getCurrentTime() + 1;
      var startTime = System.nanoTime();
      var result = algorithm.get().nextStep(world);
      var calculationTime = System.nanoTime() - startTime;
      algorithmCalculationTime.collect(calculationTime);
      customCalculationTime.collect(
          result.getCalculationTime() != null ? result.getCalculationTime() : 0);
      log.debug("Step {}: {} in {} nanos", currentTime, result, calculationTime);
      worldSimulator.move(currentTime);
      listeners.forEach(l -> l.onUpdate(world, false));
    }

    eventDispatcher.print();
    statsCollector.collect(
        eventDispatcher,
        world,
        getAlgorithm(),
        algorithmCalculationTime.result(TimeUnit.NANOSECONDS::toMillis),
        customCalculationTime.result(TimeUnit.NANOSECONDS::toMicros));
    statsCollector.print();

    try {
      resultVisualization.showResults(statsCollector.tableResults());
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }
    eventDispatcher.resetEvents();

    simulationFinished = true;

    // A final force update to ensure the last state:
    listeners.forEach(l -> l.onUpdate(world, true));
  }

  public void print() {
    log.debug("=========================");
    log.debug("=== CURRENT SIM STATE ===");
    log.debug("=========================");
    world.getClients().forEach(client -> log.debug("{}", client));
    world.getTaxis().forEach(taxi -> log.debug("{}", taxi));
    log.debug("=========================");
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
    worldGenerator.init(world, parameters);
    algorithm.get().init(world);
    listeners.forEach(l -> l.onUpdate(world, true));
    print();
    simulationFinished = false;
    simulationInitialized = true;
  }

  @Override
  public SimulationConfiguration getSimulationParameters() {
    return algorithm.get().getParameters();
  }

  public void showResultVisualization() {
    this.resultVisualization.openResults();
  }
}
