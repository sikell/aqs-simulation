package de.sikeller.aqs.model;

import java.util.Map;
import java.util.List;

public interface SimulationControl {

  void start();

  void stop();

  void init(Map<String, Integer> parameters);

  SimulationConfiguration getSimulationParameters();

  Algorithm getAlgorithm();

  boolean isSimulationFinished();

  void showResultVisualization();

  int getSpeed();

  void setSpeed(int speed);

  default SimulationControl newIsolated(TaxiAlgorithm algorithm) {
    throw new UnsupportedOperationException("Isolated simulation is not available.");
  }

  default void runUntilFinished(long timeoutMs) throws Exception {
    throw new UnsupportedOperationException("Blocking simulation run is not available.");
  }

  default ResultTable getLatestResultTable() {
    return null;
  }

  default List<TickDataPoint> getLatestTickDataPoints() {
    return List.of();
  }

  default List<RequestDataPoint> getLatestRequestDataPoints() {
    return List.of();
  }

  default void setRealtimeVisualizationEnabled(boolean enabled) {}

  default boolean isRealtimeVisualizationEnabled() {
    return true;
  }
}
