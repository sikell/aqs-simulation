package de.sikeller.aqs.model;

import lombok.Getter;

import java.util.Map;
import java.util.Set;

public interface SimulationControl {

  void start();

  void stop();

  void init(Map<String, Integer> parameters);

  SimulationConfiguration getSimulationParameters();

  Algorithm getAlgorithm();

  void setSimulationFinished(Boolean flag);
}
