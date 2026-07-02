package de.sikeller.aqs.simulation;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.events.Event;
import de.sikeller.aqs.model.events.EventClientEntersTaxi;
import de.sikeller.aqs.model.events.EventClientFinished;
import de.sikeller.aqs.model.TickDataPoint;
import de.sikeller.aqs.model.events.EventDispatcher;
import de.sikeller.aqs.simulation.result.SimulationResultSink;
import de.sikeller.aqs.simulation.stats.CollectorMinMaxAverage;
import de.sikeller.aqs.simulation.stats.CollectorTimeSeries;
import de.sikeller.aqs.simulation.stats.StatsCollector;
import de.sikeller.aqs.visualization.ResultVisualization;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Setter
@Getter
public class SimulationRunner implements SimulationControl {
  private final WorldObject world;
  private final Algorithm algorithm;
  private final WorldGenerator worldGenerator;
  private final ResultVisualization resultVisualization;
  private final SimulationResultSink resultSink;
  private final StatsCollector statsCollector = new StatsCollector();
  private final List<SimulationObserver> listeners = new LinkedList<>();
  private volatile boolean running = false;
  private volatile int speed = 15;
  private volatile boolean simulationInitialized = false;
  private volatile boolean simulationFinished = false;
  private volatile ResultTable latestResultTable;
  private volatile List<TickDataPoint> latestTickDataPoints = List.of();
  private volatile List<RequestDataPoint> latestRequestDataPoints = List.of();
  private volatile boolean realtimeVisualizationEnabled = true;

  public SimulationRunner(WorldObject world, Algorithm algorithm, WorldGenerator worldGenerator) {
    this(world, algorithm, worldGenerator, null);
  }

  public SimulationRunner(
      WorldObject world,
      Algorithm algorithm,
      WorldGenerator worldGenerator,
      SimulationResultSink resultSink) {
    this.world = world;
    this.algorithm = algorithm;
    this.worldGenerator = worldGenerator;
    this.resultVisualization = new ResultVisualization();
    this.resultSink = resultSink == null ? this.resultVisualization::showResults : resultSink;
  }

  @Override
  public SimulationControl newIsolated(TaxiAlgorithm algorithm) {
    return new SimulationRunner(
        WorldObject.builder().build(),
        new Algorithm(algorithm),
        new WorldGeneratorScenario(),
        table -> {});
  }

  @SneakyThrows
  @SuppressWarnings(value = "BusyWait")
  public void run() {
    run(0L);
  }

  @Override
  public void runUntilFinished(long timeoutMs) throws Exception {
    start();
    long deadlineNanos =
        timeoutMs <= 0 ? 0L : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    run(deadlineNanos);
  }

  @SuppressWarnings(value = "BusyWait")
  private void run(long deadlineNanos) throws Exception {
    if (!simulationInitialized) {
      return;
    }

    EventDispatcher eventDispatcher = EventDispatcher.instance();
    eventDispatcher.resetEvents();
    latestResultTable = null;
    latestTickDataPoints = List.of();
    latestRequestDataPoints = List.of();
    simulationFinished = false;
    simulationInitialized = false;

    WorldSimulator worldSimulator = new WorldSimulator(world, EntitySimulator.defaultInstance());
    var algorithmCalculationTime = CollectorMinMaxAverage.longCollector();
    var customCalculationTime = CollectorMinMaxAverage.longCollector();
    var simulationCalculationTime = CollectorMinMaxAverage.longCollector();
    CollectorTimeSeries.Collector<TickDataPoint> tickDataPoints =
        CollectorTimeSeries.newCollector();
    int seenEventCount = eventDispatcher.getAll().size();
    while (!world.isFinished()) {
      int sleepMillis =
          Math.max(0, (int) Math.min(1000, Math.round(Math.pow(100.0 / speed, 2.0) - 1)));
      Thread.sleep(sleepMillis);
      if (!running) {
        continue;
      }
      if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
        running = false;
        simulationFinished = true;
        eventDispatcher.resetEvents();
        throw new IllegalStateException("Simulation timeout.");
      }
      var simulationStartTime = System.nanoTime();
      var currentTime = world.getCurrentTime() + 1;
      var startTime = System.nanoTime();
      var result = algorithm.get().nextStep(world);
      var calculationTime = System.nanoTime() - startTime;
      algorithmCalculationTime.collect(calculationTime);
      customCalculationTime.collect(
          result.getCalculationTime() != null ? result.getCalculationTime() : 0);
      log.debug("Step {}: {} in {} nanos", currentTime, result, calculationTime);
      worldSimulator.move(currentTime);
      List<Event> events = eventDispatcher.getAll();
      TickEventCounts tickEventCounts = tickEventCounts(events, seenEventCount);
      seenEventCount = events.size();
      tickDataPoints.collect(
          new TickDataPoint(
              currentTime,
              calculationTime,
              world.getActiveClientsCount(),
              tickEventCounts.servedRequestCount(),
              tickEventCounts.waitingTimeSum(),
              tickEventCounts.waitingTimeCount(),
              tickEventCounts.finishedRequestCount()));
      if (realtimeVisualizationEnabled) {
        notifyVisualizationListeners(false);
      }
      simulationCalculationTime.collect(System.nanoTime() - simulationStartTime);
    }

    if (realtimeVisualizationEnabled) {
      eventDispatcher.print();
    }
    statsCollector.collect(
        eventDispatcher,
        world,
        getAlgorithm(),
        algorithmCalculationTime.result(TimeUnit.NANOSECONDS::toMillis),
        customCalculationTime.result(TimeUnit.NANOSECONDS::toMicros),
        simulationCalculationTime.result(TimeUnit.NANOSECONDS::toMillis));
    if (realtimeVisualizationEnabled) {
      statsCollector.print();
    }

    latestResultTable = statsCollector.tableResults();
    latestTickDataPoints = tickDataPoints.result();
    latestRequestDataPoints = requestDataPoints(eventDispatcher.getAll());
    try {
      if (realtimeVisualizationEnabled) {
        resultSink.accept(latestResultTable);
      }
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }
    if (realtimeVisualizationEnabled) {
      resultVisualization.showLoadChart(latestTickDataPoints, algorithm.get().getName());
    }
    eventDispatcher.resetEvents();

    simulationFinished = true;
    notifyVisualizationListeners(true);
  }

  private void notifyVisualizationListeners(boolean forceUpdate) {
    listeners.forEach(l -> l.onUpdate(world, forceUpdate));
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
    Map<String, Integer> preparedParameters =
        algorithm.get().prepareWorldParameters(new HashMap<>(parameters));
    worldGenerator.init(world, preparedParameters);
    algorithm.get().init(world);
    notifyVisualizationListeners(true);
    print();
    simulationFinished = false;
    simulationInitialized = true;
  }

  @Override
  public SimulationConfiguration getSimulationParameters() {
    return algorithm.get().getParameters();
  }

  @Override
  public ResultTable getLatestResultTable() {
    return latestResultTable;
  }

  @Override
  public List<TickDataPoint> getLatestTickDataPoints() {
    return latestTickDataPoints;
  }

  @Override
  public List<RequestDataPoint> getLatestRequestDataPoints() {
    return latestRequestDataPoints;
  }

  public void showResultVisualization() {
    this.resultVisualization.openResults();
  }

  private TickEventCounts tickEventCounts(List<Event> events, int fromIndex) {
    int served = 0;
    long waitingSum = 0;
    int waitingCount = 0;
    int finished = 0;
    for (int i = fromIndex; i < events.size(); i++) {
      Event event = events.get(i);
      if (event instanceof EventClientEntersTaxi enter) {
        served++;
        waitingSum += enter.getCurrentTime() - enter.getClient().getSpawnTime();
        waitingCount++;
      } else if (event instanceof EventClientFinished) {
        finished++;
      }
    }
    return new TickEventCounts(served, waitingSum, waitingCount, finished);
  }

  private List<RequestDataPoint> requestDataPoints(List<Event> events) {
    Map<String, EventClientEntersTaxi> pickups = new LinkedHashMap<>();
    Map<String, EventClientFinished> finishes = new HashMap<>();
    for (Event event : events) {
      if (event instanceof EventClientEntersTaxi enter) {
        pickups.putIfAbsent(enter.getClient().getName(), enter);
      } else if (event instanceof EventClientFinished finish) {
        finishes.put(finish.getClient().getName(), finish);
      }
    }
    List<RequestDataPoint> rows = new ArrayList<>();
    for (EventClientEntersTaxi enter : pickups.values()) {
      EventClientFinished finish = finishes.get(enter.getClient().getName());
      Position origin = enter.getClient().getPosition();
      Position target = enter.getClient().getTarget();
      rows.add(
          new RequestDataPoint(
              enter.getClient().getName(),
              enter.getClient().getSpawnTime(),
              enter.getCurrentTime(),
              finish != null ? finish.getCurrentTime() : -1,
              enter.getCurrentTime() - enter.getClient().getSpawnTime(),
              finish != null ? finish.getTravelTime() : -1,
              origin.getX(),
              origin.getY(),
              target.getX(),
              target.getY()));
    }
    return rows;
  }

  private record TickEventCounts(
      int servedRequestCount,
      long waitingTimeSum,
      int waitingTimeCount,
      int finishedRequestCount) {}
}
