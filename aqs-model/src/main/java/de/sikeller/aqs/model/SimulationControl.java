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
