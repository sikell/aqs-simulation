package de.sikeller.aqs.model;

import java.util.Map;

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
}
